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
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.User
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
    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, filename).build()

    @After fun deleteTestDatabase() { context.deleteDatabase(filename) }

    private fun importer(db: AppDatabase) = ImportUserBackupUseCase(
        RoomUserRepository(db.userDao()), RoomGameRepository(db.gameDao()),
        RoomMistakeRepository(db.mistakeDao()), RoomAnalyzedMoveRepository(db.analyzedMoveDao()),
        RoomExerciseRepository(db.exerciseDao())
    )

    @Test fun restoredProgressAndItsLinksSurviveClosingTheDatabase() = runBlocking {
        val pgn = "1. e4 e5 2. Nf3 Nc6 *"
        val fen = "4k3/1q6/8/5N2/8/8/8/K7 w - - 0 1"
        val solution = listOf("f5d6")
        val raw = Codec.encode(Codec.BackupSnapshot(
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
        ))

        val userId = openDatabase().use { db ->
            val id = db.userDao().insert(User(nickname = "Local", email = "local@example.com", passwordHash = "local-hash"))
            val result = importer(db).execute(ImportUserBackupUseCase.Input(id, raw))
            assertTrue(result.toString(), result is ImportUserBackupUseCase.Result.Success)
            id
        }

        openDatabase().use { db ->
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
}
