package com.example.chessmentor.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.data.repository.room.RoomAnalyzedMoveRepository
import com.example.chessmentor.data.repository.room.RoomExerciseRepository
import com.example.chessmentor.data.repository.room.RoomGameRepository
import com.example.chessmentor.data.repository.room.RoomMistakeRepository
import com.example.chessmentor.data.repository.room.RoomUserRepository
import com.example.chessmentor.domain.backup.UserProgressBackupCodec as Codec
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.TransactionRunner
import com.example.chessmentor.domain.session.AppThemeMode
import com.example.chessmentor.domain.usecase.ImportUserBackupUseCase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestorePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "restore-test-${UUID.randomUUID()}.db"
    private suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, filename).build()
        return try { block(db) } finally { db.close() }
    }

    @After fun deleteTestDatabase() { context.deleteDatabase(filename) }

    private fun importer(db: AppDatabase) = ImportUserBackupUseCase(
        RoomUserRepository(db.userDao()), RoomGameRepository(db.gameDao()),
        RoomMistakeRepository(db.mistakeDao()), RoomAnalyzedMoveRepository(db.analyzedMoveDao()),
        RoomExerciseRepository(db.exerciseDao()),
        engineSettingsRepository = null,
        // Боевая проводка: восстановление применяется одной транзакцией. Без этого
        // тесты не заметили бы регрессию в RoomTransactionRunner (например, смену
        // диспетчера внутри транзакции).
        transactionRunner = RoomTransactionRunner(db)
    )

    private val pgn = "1. e4 e5 2. Nf3 Nc6 *"
    private val fen = "4k3/1q6/8/5N2/8/8/8/K7 w - - 0 1"
    private val solution = listOf("f5d6")

    private fun snapshot() = Codec.BackupSnapshot(
        exportedAtMs = 9000,
        profile = Codec.ProfileSection("Source", 1675, false, "Синяя", AppThemeMode.DARK),
        games = listOf(Codec.GameEntry(pgnData = pgn, playerColor = ChessColor.WHITE, playedAt = 1000)),
        personalExercises = listOf(Codec.PersonalExerciseEntry(
            fenPosition = fen, solutionMoves = solution, tacticalPattern = TacticalPattern.FORK_KNIGHT,
            patternClusterId = "FORK_KNIGHT_MEDIUM", difficultyRating = 40,
            sourceGameIndex = 0, sourceMoveNumber = 2, createdAt = 2000
        )),
        attempts = listOf(Codec.AttemptEntry(personalExerciseIndex = 0, solved = true,
            timeSpentSeconds = 7, movesMade = solution, attemptedAt = 3000))
    )

    private suspend fun seedProgress(raw: String) = withDatabase { db ->
        val id = db.userDao().insert(User(nickname = "Local", email = "local@example.com", passwordHash = "local-hash"))
        val result = importer(db).execute(ImportUserBackupUseCase.Input(id, raw))
        assertTrue(result.toString(), result is ImportUserBackupUseCase.Result.Success)
        id
    }

    @Test fun restoredProgressAndItsLinksSurviveClosingTheDatabase() = runBlocking {
        val raw = Codec.encode(snapshot())
        val userId = seedProgress(raw)
        withDatabase { db ->
            val user = requireNotNull(db.userDao().findById(userId))
            assertEquals(1675, user.rating)
            assertEquals("local-hash", user.passwordHash)
            val game = db.gameDao().findByUserId(userId).single()
            assertEquals(pgn, game.pgnData)
            val puzzle = db.exerciseDao().findByUserId(userId).single()
            assertEquals(requireNotNull(game.id), puzzle.sourceGameId)
            assertEquals(fen, puzzle.fenPosition)
            assertEquals(solution, puzzle.solutionMoves)
            val attempt = db.exerciseDao().getAttemptsByUserId(userId).single()
            assertEquals(requireNotNull(puzzle.id), attempt.exerciseId)
            assertEquals(solution, attempt.movesMade)
            assertEquals(3000L, attempt.attemptedAt)
            assertTrue(attempt.solved)

            // Deduplication must use persisted rows, not state from the first importer.
            val repeat = importer(db).execute(ImportUserBackupUseCase.Input(userId, raw))
            assertTrue(repeat.toString(), repeat is ImportUserBackupUseCase.Result.Success)
            assertEquals(0, (repeat as ImportUserBackupUseCase.Result.Success).summary.totalChanges)
            assertEquals(game, db.gameDao().findByUserId(userId).single())
            assertEquals(puzzle, db.exerciseDao().findByUserId(userId).single())
            assertEquals(attempt, db.exerciseDao().getAttemptsByUserId(userId).single())
        }
    }

    private data class Progress(val user: User, val game: Game, val puzzle: Exercise, val attempt: ExerciseAttempt)

    private suspend fun readProgress(userId: Long) = withDatabase { db ->
        Progress(
            requireNotNull(db.userDao().findById(userId)),
            db.gameDao().findByUserId(userId).single(),
            db.exerciseDao().findByUserId(userId).single(),
            db.exerciseDao().getAttemptsByUserId(userId).single()
        )
    }

    private suspend fun assertRejectedWithoutChanges(corrupt: (Codec.BackupSnapshot) -> String) {
        val baseline = snapshot()
        val userId = seedProgress(Codec.encode(baseline))
        val before = readProgress(userId)
        // If even part of this incoming snapshot is applied, the persisted state will differ.
        val incoming = baseline.copy(
            profile = baseline.profile.copy(rating = 2100, preferredBoardTheme = "Дерево"),
            games = listOf(baseline.games.single().copy(pgnData = "1. d4 d5 *")),
            attempts = listOf(baseline.attempts.single().copy(attemptedAt = 8000))
        )
        withDatabase { db ->
            val result = importer(db).execute(ImportUserBackupUseCase.Input(userId, corrupt(incoming)))
            assertTrue("Expected import rejection, got $result", result is ImportUserBackupUseCase.Result.Error)
        }
        // Read through a new Room connection; compare full persisted rows, including IDs and links.
        assertEquals(before, readProgress(userId))
    }

    @Test fun truncatedJsonLeavesExistingProgressIntact() = runBlocking {
        assertRejectedWithoutChanges { Codec.encode(it).dropLast(1) }
    }

    @Test fun checksumMismatchLeavesExistingProgressIntact() = runBlocking {
        assertRejectedWithoutChanges { Codec.encode(it).replace("1. d4 d5 *", "1. c4 c5 *") }
    }

    @Test fun danglingGameReferenceWithValidChecksumLeavesExistingProgressIntact() = runBlocking {
        assertRejectedWithoutChanges { incoming ->
            val orphan = incoming.personalExercises.single().copy(sourceGameIndex = incoming.games.size)
            Codec.encode(incoming.copy(personalExercises = listOf(orphan)))
        }
    }

    @Test fun rollbackAfterSuccessfulWriteLeavesExistingProgressIntact() = runBlocking {
        val raw = Codec.encode(snapshot())
        val userId = seedProgress(raw)
        val before = readProgress(userId)

        // Входящая копия с другими данными будет записана в транзакцию, но затем
        // транзакция упадёт (симуляция сбоя посередине записи). Ожидаем откат:
        // в БД не должно остаться ни одной записи из входящей копии.
        val incoming = snapshot().copy(
            profile = snapshot().profile.copy(rating = 2100),
            games = listOf(snapshot().games.single().copy(pgnData = "1. d4 d5 *"))
        )
        val incomingRaw = Codec.encode(incoming)

        withDatabase { db ->
            val runner = object : TransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T {
                    return RoomTransactionRunner(db).run {
                        val result = block()
                        throw RuntimeException("имитация сбоя после записи в транзакцию")
                    }
                }
            }
            val importer = ImportUserBackupUseCase(
                RoomUserRepository(db.userDao()), RoomGameRepository(db.gameDao()),
                RoomMistakeRepository(db.mistakeDao()), RoomAnalyzedMoveRepository(db.analyzedMoveDao()),
                RoomExerciseRepository(db.exerciseDao()),
                engineSettingsRepository = null,
                transactionRunner = runner
            )
            val result = importer.execute(ImportUserBackupUseCase.Input(userId, incomingRaw))
            assertTrue("Ожидалась ошибка импорта, получено $result", result is ImportUserBackupUseCase.Result.Error)
        }

        // База не изменилась: откат транзакции отменил все записи, включая рейтинг и партии.
        assertEquals(before, readProgress(userId))
    }

    @Test fun roomMistakeRepositoryHandlesMoreThan999GameIdsWithoutBindOverflow() = runBlocking {
        withDatabase { db ->
            val userId = db.userDao().insert(
                User(nickname = "ChunkTester", email = "chunk@test.local", passwordHash = "hash")
            )
            val gameId = db.gameDao().insert(
                Game(userId = userId, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = pgn)
            )
            val mistakeRepo = RoomMistakeRepository(db.mistakeDao())
            mistakeRepo.save(
                com.example.chessmentor.domain.entity.Mistake(
                    gameId = gameId,
                    themeId = 1L,
                    moveNumber = 2,
                    color = ChessColor.WHITE,
                    evaluationLoss = 300,
                    mistakeType = com.example.chessmentor.domain.entity.MistakeType.BLUNDER,
                    bestMove = "f5d6",
                    userMove = "f5h4",
                    fenBefore = fen
                )
            )
            val overBindLimitIds = listOf(gameId) + (10_000L..11_200L).toList()
            val found = mistakeRepo.findByGameIds(overBindLimitIds)
            assertEquals(1, found.size)
            assertEquals(gameId, found.single().gameId)
        }
    }
}
