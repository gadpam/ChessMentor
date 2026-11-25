package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.usecase.GenerateExercisesFromMistakesUseCase
import com.example.chessmentor.domain.usecase.GetTrainingExerciseUseCase
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingUseCasesTest {

    // ================================================================
    // IN-MEMORY FAKE REPOSITORIES (с той же изоляцией по userId, что и ExerciseDao)
    // ================================================================

    private class FakeUserRepo : UserRepository {
        private val users = mutableMapOf<Long, User>()
        private var nextId = 1L

        override suspend fun findById(id: Long): User? = users[id]
        override suspend fun findByEmail(email: String): User? = users.values.firstOrNull { it.email == email }
        override suspend fun findByNickname(nickname: String): User? = users.values.firstOrNull { it.nickname == nickname }
        override suspend fun save(user: User): User {
            val id = user.id ?: nextId++
            return user.copy(id = id).also { users[id] = it }
        }
        override suspend fun update(user: User): User {
            val id = requireNotNull(user.id)
            users[id] = user
            return user
        }
        override suspend fun deleteById(id: Long) { users.remove(id) }
        override suspend fun existsByEmail(email: String): Boolean = findByEmail(email) != null
        override suspend fun existsByNickname(nickname: String): Boolean = findByNickname(nickname) != null
        override suspend fun findAll(): List<User> = users.values.toList()
        override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
            users.values.filter { it.skillLevel == skillLevel }
        override suspend fun findTopByRating(limit: Int): List<User> =
            users.values.sortedByDescending { it.rating }.take(limit)
    }

    private class FakeGameRepo : GameRepository {
        val games = mutableMapOf<Long, Game>()
        private var nextId = 1L

        override suspend fun findById(id: Long): Game? = games[id]
        override suspend fun findByUserId(userId: Long): List<Game> =
            games.values.filter { it.userId == userId }
        override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> =
            games.values.filter { it.userId == userId && it.analysisStatus == status }
        override suspend fun save(game: Game): Game {
            val id = game.id ?: nextId++
            return game.copy(id = id).also { games[id] = it }
        }
        override suspend fun update(game: Game): Game {
            val id = requireNotNull(game.id)
            games[id] = game
            return game
        }
        override suspend fun deleteById(id: Long) { games.remove(id) }
    }

    private class FakeMistakeRepo(private val gameRepo: FakeGameRepo) : MistakeRepository {
        private val items = mutableMapOf<Long, Mistake>()
        private var nextId = 1L

        override suspend fun findById(id: Long): Mistake? = items[id]
        override suspend fun findByGameId(gameId: Long): List<Mistake> =
            items.values.filter { it.gameId == gameId }
        override suspend fun findByUserId(userId: Long): List<Mistake> {
            val gameIds = gameRepo.findByUserId(userId).mapNotNull { it.id }.toSet()
            return items.values.filter { it.gameId in gameIds }
        }
        override suspend fun findByGameIds(gameIds: List<Long>): List<Mistake> =
            items.values.filter { it.gameId in gameIds.toSet() }
        override suspend fun save(mistake: Mistake): Mistake {
            val id = mistake.id ?: nextId++
            return mistake.copy(id = id).also { items[id] = it }
        }
        override suspend fun saveAll(mistakes: List<Mistake>): List<Mistake> =
            mistakes.map { save(it) }
        override suspend fun update(mistake: Mistake): Mistake {
            val id = requireNotNull(mistake.id)
            items[id] = mistake
            return mistake
        }
        override suspend fun deleteById(id: Long) { items.remove(id) }
        override suspend fun deleteByGameId(gameId: Long) {
            items.entries.removeIf { it.value.gameId == gameId }
        }
        override suspend fun findByThemeId(themeId: Long): List<Mistake> =
            items.values.filter { it.themeId == themeId }
        override suspend fun findByGameIdAndType(gameId: Long, type: MistakeType): List<Mistake> =
            items.values.filter { it.gameId == gameId && it.mistakeType == type }
        override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<Mistake> =
            findByUserId(userId).filter { it.themeId == themeId }
        override suspend fun countByGameId(gameId: Long): Int = findByGameId(gameId).size
        override suspend fun countByUserIdAndType(userId: Long, type: MistakeType): Int =
            findByUserId(userId).count { it.mistakeType == type }
        override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): Map<Long, Int> =
            findByUserId(userId).groupingBy { it.themeId }.eachCount()
        override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? =
            findByUserId(userId).takeIf { it.isNotEmpty() }?.map { it.evaluationLoss }?.average()
    }

    private class FakeAnalyzedMoveRepo : AnalyzedMoveRepository {
        private val items = mutableListOf<AnalyzedMove>()
        private var nextId = 1L

        override suspend fun saveAll(moves: List<AnalyzedMove>): List<AnalyzedMove> {
            val saved = moves.map { it.copy(id = it.id ?: nextId++) }
            items.addAll(saved)
            return saved
        }
        override suspend fun findByGameId(gameId: Long): List<AnalyzedMove> =
            items.filter { it.gameId == gameId }.sortedBy { it.moveIndex }
        override suspend fun findMistakesByGameId(gameId: Long): List<AnalyzedMove> =
            findByGameId(gameId).filter { it.isMistake() }
        override suspend fun findGoodMovesByGameId(gameId: Long): List<AnalyzedMove> =
            findByGameId(gameId).filter { it.isGoodMove() }
        override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove? =
            items.firstOrNull { it.gameId == gameId && it.moveIndex == moveIndex }
        override suspend fun deleteByGameId(gameId: Long) {
            items.removeIf { it.gameId == gameId }
        }
    }

    private class FakeExerciseRepo(private val gameRepo: FakeGameRepo) : ExerciseRepository {
        private val exercises = linkedMapOf<Long, Exercise>()
        private val attempts = mutableListOf<ExerciseAttempt>()
        private var nextExId = 1L
        private var nextAttId = 1L

        private fun isOwnedBy(ex: Exercise, userId: Long): Boolean {
            val gid = ex.sourceGameId ?: return false
            return gameRepo.games[gid]?.userId == userId
        }

        private fun isAccessible(ex: Exercise, userId: Long): Boolean =
            ex.sourceGameId == null || isOwnedBy(ex, userId)

        private fun solvedIds(userId: Long): Set<Long> =
            attempts.filter { it.userId == userId && it.solved }.map { it.exerciseId }.toSet()

        override suspend fun findById(id: Long): Exercise? = exercises[id]
        override suspend fun findByIds(ids: List<Long>): List<Exercise> = ids.mapNotNull { exercises[it] }

        override suspend fun findSuitableExercise(userId: Long, userRating: Int, excludeId: Long): Exercise? {
            val solved = solvedIds(userId)
            return exercises.values
                .filter { it.id != excludeId && isAccessible(it, userId) && it.id !in solved }
                .sortedBy { if (it.sourceGameId != null) 0 else 1 }
                .firstOrNull()
        }

        override suspend fun findFailedUnresolvedExercise(userId: Long, excludeId: Long): Exercise? {
            val solved = solvedIds(userId)
            val failedByEx = attempts
                .filter { it.userId == userId && !it.solved && it.exerciseId !in solved }
                .groupBy { it.exerciseId }
                .mapValues { (_, list) -> list.maxOf { it.attemptedAt } }
            return exercises.values
                .filter { it.id != excludeId && isAccessible(it, userId) && it.id in failedByEx }
                .minByOrNull { failedByEx[it.id] ?: Long.MAX_VALUE }
        }

        override suspend fun findUserMistakeExercise(userId: Long, excludeId: Long): Exercise? {
            val solved = solvedIds(userId)
            val owned = exercises.values.filter { it.id != excludeId && isOwnedBy(it, userId) }
            return owned.firstOrNull { it.id !in solved } ?: owned.firstOrNull()
        }

        override suspend fun findExerciseByTheme(
            userId: Long,
            theme: TacticalTheme,
            userRating: Int,
            excludeId: Long
        ): Exercise? {
            val solved = solvedIds(userId)
            val themed = exercises.values.filter {
                it.theme == theme && it.id != excludeId && isAccessible(it, userId)
            }
            return themed.firstOrNull { it.id !in solved } ?: themed.firstOrNull()
        }

        override suspend fun findByTheme(theme: String): List<Exercise> =
            exercises.values.filter { it.theme.name == theme && it.sourceGameId == null }
        override suspend fun findByPattern(pattern: String): List<Exercise> =
            exercises.values.filter { it.tacticalPattern.name == pattern && it.sourceGameId == null }
        override suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise? =
            exercises.values.firstOrNull { it.sourceGameId == gameId && it.sourceMoveNumber == moveNumber }
        override suspend fun findByUserId(userId: Long): List<Exercise> =
            exercises.values.filter { isOwnedBy(it, userId) }
        override suspend fun findAccessibleByUserId(userId: Long): List<Exercise> =
            exercises.values.filter { isAccessible(it, userId) }
        override suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise? =
            exercises.values.firstOrNull { it.id != excludeId && isAccessible(it, userId) }
        override suspend fun findGlobalByFen(fen: String): Exercise? =
            exercises.values.firstOrNull { it.sourceGameId == null && it.fenPosition == fen }
        override suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise? =
            exercises.values.firstOrNull { isOwnedBy(it, userId) && it.fenPosition == fen }
        override suspend fun findByFen(fen: String): Exercise? =
            exercises.values.firstOrNull { it.fenPosition == fen }

        override suspend fun deleteDuplicatesForUser(userId: Long) {
            val keepIds = exercises.values
                .filter { isOwnedBy(it, userId) }
                .groupBy { it.fenPosition }
                .mapValues { (_, list) -> list.minOf { it.id!! } }
                .values.toSet()
            exercises.entries.removeIf { (id, ex) -> isOwnedBy(ex, userId) && id !in keepIds }
        }

        override suspend fun deleteGlobalDuplicates() {
            val keepIds = exercises.values
                .filter { it.sourceGameId == null }
                .groupBy { it.fenPosition }
                .mapValues { (_, list) -> list.minOf { it.id!! } }
                .values.toSet()
            exercises.entries.removeIf { (id, ex) -> ex.sourceGameId == null && id !in keepIds }
        }

        override suspend fun countExercises(): Int = exercises.size
        override suspend fun countGlobalExercises(): Int = exercises.values.count { it.sourceGameId == null }
        override suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean =
            attempts.any { it.userId == userId && it.exerciseId == exerciseId && AttemptMetadata.isIndependentSolve(it) }

        override suspend fun saveExercise(exercise: Exercise): Exercise {
            val id = exercise.id ?: nextExId++
            return exercise.copy(id = id).also { exercises[id] = it }
        }

        override suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt {
            val id = attempt.id ?: nextAttId++
            return attempt.copy(id = id).also { attempts.add(it) }
        }

        override suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt> =
            attempts.filter { it.userId == userId }.sortedBy { it.attemptedAt }

        override suspend fun findByCluster(
            userId: Long,
            clusterId: String,
            excludeId: Long,
            limit: Int
        ): List<Exercise> = exercises.values
            .filter { it.patternClusterId == clusterId && it.id != excludeId && isAccessible(it, userId) }
            .take(limit)

        override suspend fun findSimilarByPattern(
            userId: Long,
            pattern: String,
            excludeId: Long,
            minDifficulty: Int,
            maxDifficulty: Int,
            limit: Int
        ): List<Exercise> = exercises.values
            .filter {
                it.tacticalPattern.name == pattern &&
                    it.id != excludeId &&
                    isAccessible(it, userId) &&
                    it.difficultyRating in minDifficulty..maxDifficulty
            }
            .take(limit)

        override suspend fun findForMistakeRepetition(
            userId: Long,
            pattern: String,
            difficulty: Int,
            excludeId: Long,
            limit: Int
        ): List<Exercise> {
            val solved = solvedIds(userId)
            return exercises.values
                .filter {
                    it.tacticalPattern.name == pattern &&
                        it.id != excludeId &&
                        isAccessible(it, userId) &&
                        it.id !in solved
                }
                .take(limit)
        }

        override suspend fun incrementTimesShown(exerciseId: Long) {
            val ex = exercises[exerciseId] ?: return
            val shown = ex.timesShown + 1
            exercises[exerciseId] = ex.copy(timesShown = shown, successRate = (ex.timesSolved * 100) / shown)
        }

        override suspend fun incrementTimesSolved(exerciseId: Long) {
            val ex = exercises[exerciseId] ?: return
            val solved = ex.timesSolved + 1
            val denom = ex.timesShown.coerceAtLeast(1)
            exercises[exerciseId] = ex.copy(timesSolved = solved, successRate = ((solved * 100) / denom).coerceAtMost(100))
        }
    }

    private class StubEngine(private val lineByFen: Map<String, AnalysisLine> = emptyMap()) : ChessEngine {
        override suspend fun init(): Boolean = true
        override suspend fun evaluate(fen: String, depthLimit: Int): Int = 0
        override suspend fun getBestMove(fen: String, depthLimit: Int): String? = lineByFen[fen]?.bestMove
        override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = lineByFen[fen]
        override suspend fun setOption(name: String, value: String) = Unit
        override fun stop() = Unit
        override fun destroy() = Unit
    }

    // ================================================================
    // TESTS
    // ================================================================

    @Test
    fun userIsolation_preventsLeakingOtherUsersMistakeExercises() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val alice = userRepo.save(User(nickname = "Alice", email = "alice@test.com", passwordHash = "h1", rating = 1400))
        val bob = userRepo.save(User(nickname = "Bob", email = "bob@test.com", passwordHash = "h2", rating = 1400))

        val aliceGame = gameRepo.save(
            Game(userId = alice.id!!, pgnData = "1. e4 e5 *", playerColor = ChessColor.WHITE, playedAt = 1000L)
        )
        val aliceSecretExercise = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                solutionMoves = listOf("h5f7"),
                tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                patternClusterId = "BACK_RANK_MATE_EASY",
                difficultyRating = 20,
                theme = TacticalTheme.MATE,
                sourceGameId = aliceGame.id,
                sourceMoveNumber = 4
            )
        )

        val useCase = GetTrainingExerciseUseCase(userRepo, mistakeRepo, exerciseRepo)

        // У Боба нет собственных задач -> режим MyMistakes пуст, чужая задача Алисы НЕ утекает
        val bobMistakesResult = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = bob.id!!, mode = TrainingMode.MyMistakes)
        )
        assertTrue(bobMistakesResult is GetTrainingExerciseUseCase.Result.NoSuitableExercises)

        // У Алисы режим MyMistakes возвращает её собственную задачу
        val aliceMistakesResult = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = alice.id!!, mode = TrainingMode.MyMistakes)
        )
        assertTrue(aliceMistakesResult is GetTrainingExerciseUseCase.Result.Success)
        assertEquals(aliceSecretExercise.id, (aliceMistakesResult as GetTrainingExerciseUseCase.Result.Success).exercise.id)

        // В общем режиме Боб получает встроенные задачи и НИКОГДА не видит задачу из партии Алисы
        val bobAccessibleIds = exerciseRepo.findAccessibleByUserId(bob.id!!).mapNotNull { it.id }.toSet()
        assertFalse(aliceSecretExercise.id in bobAccessibleIds)
    }

    @Test
    fun dailyMode_andSpacedRepetition_andThemeMode_workAsExpected() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val alice = userRepo.save(User(nickname = "Alice", email = "alice@test.com", passwordHash = "h1", rating = 1500))
        val bob = userRepo.save(User(nickname = "Bob", email = "bob@test.com", passwordHash = "h2", rating = 1500))
        val useCase = GetTrainingExerciseUseCase(userRepo, mistakeRepo, exerciseRepo)

        // Идемпотентный сидинг встроенного банка
        val firstSeed = CuratedExerciseBank.ensureSeeded(exerciseRepo)
        val secondSeed = CuratedExerciseBank.ensureSeeded(exerciseRepo)
        assertEquals(CuratedExerciseBank.puzzles.size, firstSeed)
        assertEquals(0, secondSeed)

        // Ежедневная задача для фиксированной даты
        val day = 20_774L
        val dailyRes1 = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = alice.id!!, mode = TrainingMode.Daily, epochDay = day)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(CuratedExerciseBank.getDailyPuzzle(day).fen, dailyRes1.exercise.fenPosition)
        assertFalse(dailyRes1.isAlreadySolvedDaily)

        // Алиса решает задачу дня -> для неё флаг становится true, а для Боба остаётся false
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = alice.id!!,
                exerciseId = dailyRes1.exercise.id!!,
                solved = true,
                timeSpentSeconds = 15,
                movesMade = dailyRes1.exercise.solutionMoves,
                attemptedAt = 1000L
            )
        )
        val aliceDailyAgain = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = alice.id!!, mode = TrainingMode.Daily, epochDay = day)
        ) as GetTrainingExerciseUseCase.Result.Success
        val bobDaily = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = bob.id!!, mode = TrainingMode.Daily, epochDay = day)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertTrue(aliceDailyAgain.isAlreadySolvedDaily)
        assertFalse(bobDaily.isAlreadySolvedDaily)

        // Режим по теме возвращает строго запрошенную тему
        val pinRes = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = alice.id!!, mode = TrainingMode.ByTheme(TacticalTheme.PIN))
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(TacticalTheme.PIN, pinRes.exercise.theme)

        // Интервальное повторение (SM-2/Лейтнер):
        // 1) Сразу через 1 минуту после ошибки задача в охлаждении — выдаётся ДРУГАЯ новая задача
        val failAt = 10_000_000L
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = alice.id!!,
                exerciseId = pinRes.exercise.id!!,
                solved = false,
                timeSpentSeconds = 20,
                movesMade = listOf("g1h1"),
                attemptedAt = failAt
            )
        )
        val duringCooldown = useCase.execute(
            GetTrainingExerciseUseCase.Input(
                userId = alice.id!!,
                excludeExerciseId = -1L,
                mode = TrainingMode.All,
                nowMs = failAt + SpacedRepetitionScheduler.MINUTE_MS
            )
        ) as GetTrainingExerciseUseCase.Result.Success
        assertNotEquals(pinRes.exercise.id, duringCooldown.exercise.id)

        // 2) Через 10 минут охлаждение истекло — задача возвращается с isReviewOfFailed = true и box = 0
        val reviewRes = useCase.execute(
            GetTrainingExerciseUseCase.Input(
                userId = alice.id!!,
                excludeExerciseId = -1L,
                mode = TrainingMode.All,
                nowMs = failAt + SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS
            )
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(pinRes.exercise.id, reviewRes.exercise.id)
        assertTrue(reviewRes.isReviewOfFailed)
        assertEquals(0, reviewRes.reviewState?.box)

        // 3) Если Алиса дорешала её со 2-й попытки — через 4 часа она СНОВА возвращается для чистой проверки!
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = alice.id!!,
                exerciseId = pinRes.exercise.id!!,
                solved = true,
                timeSpentSeconds = 32,
                movesMade = AttemptMetadata.buildTaggedMoves(listOf("g1h1") + pinRes.exercise.solutionMoves, retryCount = 1),
                attemptedAt = failAt + 2 * SpacedRepetitionScheduler.MINUTE_MS
            )
        )
        val retryConsolidation = useCase.execute(
            GetTrainingExerciseUseCase.Input(
                userId = alice.id!!,
                excludeExerciseId = -1L,
                mode = TrainingMode.All,
                nowMs = failAt + 2 * SpacedRepetitionScheduler.MINUTE_MS + SpacedRepetitionScheduler.RETRY_CONSOLIDATION_MS
            )
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(pinRes.exercise.id, retryConsolidation.exercise.id)
        assertTrue(retryConsolidation.isReviewOfFailed)
        assertEquals(AttemptQuality.RETRY_SOLVE, retryConsolidation.reviewState?.lastQuality)
    }

    @Test
    fun generatorAndStatistics_enforceOddPvPrevMoveAndReportMastery() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val analyzedRepo = FakeAnalyzedMoveRepo()
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val alice = userRepo.save(User(nickname = "Alice", email = "alice@test.com", passwordHash = "h1", rating = 1450))
        val bob = userRepo.save(User(nickname = "Bob", email = "bob@test.com", passwordHash = "h2", rating = 1400))

        val pgn = "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. d3 *"
        val aliceGame = gameRepo.save(
            Game(
                userId = alice.id!!,
                pgnData = pgn,
                playerColor = ChessColor.WHITE,
                playedAt = 1000L,
                analysisStatus = AnalysisStatus.COMPLETED,
                accuracy = 82.0
            )
        )
        val bobGame = gameRepo.save(
            Game(
                userId = bob.id!!,
                pgnData = pgn,
                playerColor = ChessColor.WHITE,
                playedAt = 1100L,
                analysisStatus = AnalysisStatus.COMPLETED,
                accuracy = 78.0
            )
        )

        // Проанализированные ходы партии Алисы (3... Nf6 = g8f6 прямо перед ошибкой 4. d3)
        val scholarsFen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        analyzedRepo.saveAll(
            listOf(
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE, quality = MoveQuality.BOOK, san = "e4", evalBefore = 20, evalAfter = 20, evalLoss = 0),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 1, moveNumber = 1, color = ChessColor.BLACK, quality = MoveQuality.BOOK, san = "e5", evalBefore = 20, evalAfter = 20, evalLoss = 0),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 2, moveNumber = 2, color = ChessColor.WHITE, quality = MoveQuality.GOOD, san = "Bc4", evalBefore = 20, evalAfter = 15, evalLoss = 5),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 3, moveNumber = 2, color = ChessColor.BLACK, quality = MoveQuality.GOOD, san = "Nc6", evalBefore = 15, evalAfter = 15, evalLoss = 0),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 4, moveNumber = 3, color = ChessColor.WHITE, quality = MoveQuality.INACCURACY, san = "Qh5", evalBefore = 15, evalAfter = -40, evalLoss = 55),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 5, moveNumber = 3, color = ChessColor.BLACK, quality = MoveQuality.BLUNDER, san = "Nf6", evalBefore = -40, evalAfter = 99900, evalLoss = 99940),
                AnalyzedMove(gameId = aliceGame.id!!, moveIndex = 6, moveNumber = 4, color = ChessColor.WHITE, quality = MoveQuality.BLUNDER, san = "d3", bestMove = "Qxf7#", evalBefore = 99900, evalAfter = -100, evalLoss = 99900)
            )
        )

        mistakeRepo.save(
            Mistake(
                gameId = aliceGame.id!!, themeId = 4L, moveNumber = 4, color = ChessColor.WHITE,
                evaluationLoss = 99900, mistakeType = MistakeType.BLUNDER,
                bestMove = "Qxf7#", userMove = "d3", fenBefore = scholarsFen
            )
        )
        // Та же самая позиция в партии Боба должна независимо сгенерироваться и для Боба!
        mistakeRepo.save(
            Mistake(
                gameId = bobGame.id!!, themeId = 4L, moveNumber = 4, color = ChessColor.WHITE,
                evaluationLoss = 99900, mistakeType = MistakeType.BLUNDER,
                bestMove = "Qxf7#", userMove = "d3", fenBefore = scholarsFen
            )
        )

        // Движок специально возвращает ЧЁТНЫЙ PV (2 полухода) — генератор обязан обрезать до нечётного
        val forkFen = "q3k3/ppp2ppp/8/3N4/8/8/PPP2PPP/6K1 w - - 0 1"
        mistakeRepo.save(
            Mistake(
                gameId = aliceGame.id!!, themeId = 1L, moveNumber = 18, color = ChessColor.WHITE,
                evaluationLoss = 500, mistakeType = MistakeType.BLUNDER,
                bestMove = "Nc7+", userMove = "Nf4", fenBefore = forkFen
            )
        )
        val engine = StubEngine(
            mapOf(
                forkFen to AnalysisLine(
                    bestMove = "d5c7",
                    score = 500,
                    mateIn = null,
                    principalVariation = listOf("d5c7", "e8d7", "c7a8", "b7b6") // 4 полухода -> должно стать 3!
                )
            )
        )

        val generator = GenerateExercisesFromMistakesUseCase(
            mistakeRepo, analyzedRepo, exerciseRepo, gameRepo, engine
        )
        val aliceGen = generator.execute(GenerateExercisesFromMistakesUseCase.Input(alice.id!!))
            as GenerateExercisesFromMistakesUseCase.Result.Success
        val bobGen = generator.execute(GenerateExercisesFromMistakesUseCase.Input(bob.id!!))
            as GenerateExercisesFromMistakesUseCase.Result.Success

        assertEquals(2, aliceGen.generatedCount)
        assertEquals(1, bobGen.generatedCount)

        val scholarsEx = aliceGen.exercises.first { it.fenPosition == scholarsFen }
        assertEquals("g8f6", ExerciseMetadata.extractPrevMove(scholarsEx))

        val forkEx = aliceGen.exercises.first { it.fenPosition == forkFen }
        assertEquals(listOf("d5c7", "e8d7", "c7a8"), forkEx.solutionMoves)
        assertNotEquals(0, forkEx.solutionMoves.size % 2)

        // Проверяем статистику тренировки в GetUserStatisticsUseCase
        exerciseRepo.saveAttempt(
            ExerciseAttempt(userId = alice.id!!, exerciseId = scholarsEx.id!!, solved = true, timeSpentSeconds = 10, movesMade = listOf("h5f7"), attemptedAt = 100L)
        )
        exerciseRepo.saveAttempt(
            ExerciseAttempt(userId = alice.id!!, exerciseId = forkEx.id!!, solved = false, timeSpentSeconds = 30, movesMade = listOf("d5f4"), attemptedAt = 200L)
        )

        val statsUseCase = GetUserStatisticsUseCase(userRepo, gameRepo, mistakeRepo, exerciseRepo)
        val statsRes = statsUseCase.execute(GetUserStatisticsUseCase.Input(alice.id!!))
            as GetUserStatisticsUseCase.Result.Success
        val training = statsRes.statistics.training

        assertEquals(2, training.totalAttempts)
        assertEquals(1, training.solvedAttempts)
        assertEquals(50, training.solveAccuracyPercent)
        assertEquals(1, training.uniqueExercisesSolved)
        assertEquals(2, training.personalMistakeExercises)
        assertEquals(1, training.unresolvedFailedCount)
        assertEquals(0, training.currentStreak)
        assertEquals(1, training.bestStreak)
        assertEquals(10, training.averageSolveTimeSeconds)
        assertEquals(TacticalPattern.FORK_KNIGHT, training.weakPatterns.first().pattern)
        assertEquals(TacticalPattern.BACK_RANK_MATE, training.strongPatterns.first().pattern)
    }

    @Test
    fun getUserStatistics_excludesRevealedAndFullHintAttemptsFromSolvedStats() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val player = userRepo.save(User(nickname = "Honest", email = "honest@test.com", passwordHash = "h", rating = 1500))
        val ex1 = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
                solutionMoves = listOf("d1d8"),
                tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                patternClusterId = "BACK_RANK_MATE_EASY",
                difficultyRating = 30,
                theme = TacticalTheme.MATE
            )
        )
        val ex2 = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "6k1/5ppp/8/8/8/8/5PPP/2R3K1 w - - 0 1",
                solutionMoves = listOf("c1c8"),
                tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                patternClusterId = "BACK_RANK_MATE_EASY",
                difficultyRating = 30,
                theme = TacticalTheme.MATE
            )
        )

        // Даже если в старой БД лежит solved=true с @REVEALED или @HINT:FULL — это не считается решением
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = player.id!!,
                exerciseId = ex1.id!!,
                solved = true,
                timeSpentSeconds = 2,
                movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d8"), solutionRevealed = true),
                attemptedAt = 100L
            )
        )
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = player.id!!,
                exerciseId = ex2.id!!,
                solved = true,
                timeSpentSeconds = 5,
                movesMade = AttemptMetadata.buildTaggedMoves(listOf("c1c8"), hintUsage = HintUsage.FULL_SOLUTION),
                attemptedAt = 200L
            )
        )

        assertFalse(exerciseRepo.hasUserSolvedExercise(player.id!!, ex1.id!!))
        assertFalse(exerciseRepo.hasUserSolvedExercise(player.id!!, ex2.id!!))

        val statsUseCase = GetUserStatisticsUseCase(userRepo, gameRepo, mistakeRepo, exerciseRepo)
        val statsRes = statsUseCase.execute(GetUserStatisticsUseCase.Input(player.id!!))
            as GetUserStatisticsUseCase.Result.Success
        val training = statsRes.statistics.training

        assertEquals(2, training.totalAttempts)
        assertEquals(0, training.solvedAttempts)
        assertEquals(0, training.solveAccuracyPercent)
        assertEquals(0, training.uniqueExercisesSolved)
        assertEquals(2, training.unresolvedFailedCount)
        assertEquals(0, training.averageSolveTimeSeconds)
    }

    @Test
    fun smartSessionAndCognitiveModes_serveDistinctTargetedExercises() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val exerciseRepo = FakeExerciseRepo(gameRepo)
        val player = userRepo.save(
            User(nickname = "Coach", email = "coach@example.com", passwordHash = "h", rating = 1650)
        )
        val useCase = GetTrainingExerciseUseCase(userRepo, mistakeRepo, exerciseRepo)

        // 1. Полная «Умная сессия» из 10 задач без единого повтора, с проверкой защиты и глубокого расчёта
        val servedIds = mutableSetOf<Long>()
        var lastPattern: String? = null
        var step7Defensive = false
        var step9MultiMove = false

        for (step in 1..TrainingSessionPlanner.DEFAULT_SESSION_SIZE) {
            val phase = TrainingSessionPlanner.phaseForStep(step)
            val res = useCase.execute(
                GetTrainingExerciseUseCase.Input(
                    userId = player.id!!,
                    excludeExerciseIds = servedIds,
                    avoidPattern = lastPattern,
                    sessionPhase = phase,
                    mode = TrainingMode.SmartSession,
                    nowMs = 1_000_000L + step * 30_000L
                )
            )
            assertTrue("Step $step must return Success", res is GetTrainingExerciseUseCase.Result.Success)
            val ex = (res as GetTrainingExerciseUseCase.Result.Success).exercise
            assertTrue("Session must not repeat exercise ${ex.id}", servedIds.add(ex.id!!))
            if (step == 7) {
                step7Defensive = ExerciseMetadata.isDefensiveGoal(ex) || ex.theme == TacticalTheme.KING_SAFETY
            }
            if (step == 9) {
                step9MultiMove = ex.solutionMoves.size >= 3
            }
            lastPattern = ex.tacticalPattern.name
        }

        assertEquals(10, servedIds.size)
        assertTrue("Step 7 must test defensive/saving alertness", step7Defensive)
        assertTrue("Step 9 must require multi-move calculation", step9MultiMove)

        // 2. Режим «Защита»
        val defRes = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = player.id!!, mode = TrainingMode.Defense)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertTrue(
            ExerciseMetadata.isDefensiveGoal(defRes.exercise) ||
                defRes.exercise.theme == TacticalTheme.KING_SAFETY
        )

        // 3. Режим «Зоркость» (короткие паттерны <= 3 полуходов)
        val visionRes = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = player.id!!, mode = TrainingMode.PatternVision)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertTrue(visionRes.exercise.solutionMoves.size <= 3)

        // 4. Режим «Расчёт» (многоходовые комбинации >= 5 полуходов для 1650 Эло)
        val calcRes = useCase.execute(
            GetTrainingExerciseUseCase.Input(userId = player.id!!, mode = TrainingMode.DeepCalculation)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertTrue(calcRes.exercise.solutionMoves.size >= 5)
    }

    @Test
    fun fromGameBridge_generatesForSingleGameServesFocusedMoveFirstAndIsolatesUsers() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val analyzedRepo = FakeAnalyzedMoveRepo()
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val alice = userRepo.save(
            User(nickname = "Alice", email = "a@ex.com", passwordHash = "h", rating = 1500)
        )
        val bob = userRepo.save(
            User(nickname = "Bob", email = "b@ex.com", passwordHash = "h", rating = 1500)
        )

        val game1 = gameRepo.save(
            Game(
                userId = alice.id!!,
                pgnData = "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. d3 *",
                playerColor = ChessColor.WHITE,
                playedAt = 1_000L,
                analysisStatus = AnalysisStatus.COMPLETED
            )
        )
        val game2 = gameRepo.save(
            Game(
                userId = alice.id!!,
                pgnData = "1. d4 d5 *",
                playerColor = ChessColor.WHITE,
                playedAt = 2_000L,
                analysisStatus = AnalysisStatus.COMPLETED
            )
        )

        val fenMove4 = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val fenMove9 = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 9"
        val fenOtherGame = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"

        mistakeRepo.save(
            Mistake(
                gameId = game1.id!!, themeId = 1L, moveNumber = 4, color = ChessColor.WHITE,
                evaluationLoss = 600, mistakeType = MistakeType.BLUNDER,
                bestMove = "Qxf7#", userMove = "d3", fenBefore = fenMove4
            )
        )
        mistakeRepo.save(
            Mistake(
                gameId = game1.id!!, themeId = 1L, moveNumber = 9, color = ChessColor.WHITE,
                evaluationLoss = 550, mistakeType = MistakeType.BLUNDER,
                bestMove = "Rd8#", userMove = "h3", fenBefore = fenMove9
            )
        )
        mistakeRepo.save(
            Mistake(
                gameId = game2.id!!, themeId = 1L, moveNumber = 3, color = ChessColor.WHITE,
                evaluationLoss = 280, mistakeType = MistakeType.MISTAKE,
                bestMove = "Bb5", userMove = "a3", fenBefore = fenOtherGame
            )
        )

        val generator = GenerateExercisesFromMistakesUseCase(
            mistakeRepository = mistakeRepo,
            analyzedMoveRepository = analyzedRepo,
            exerciseRepository = exerciseRepo,
            gameRepository = gameRepo,
            chessEngine = StubEngine()
        )

        // Чужой пользователь не может сгенерировать задачи по чужой партии
        val forbidden = generator.executeForGame(userId = bob.id!!, gameId = game1.id!!)
        assertTrue(forbidden is GenerateExercisesFromMistakesUseCase.Result.Error)

        // Прицельная генерация создаёт задачи ТОЛЬКО для game1 (не трогая game2)
        val genRes = generator.executeForGame(
            userId = alice.id!!,
            gameId = game1.id!!,
            focusMoveNumber = 9
        )
        assertTrue(genRes is GenerateExercisesFromMistakesUseCase.Result.Success)
        val created = (genRes as GenerateExercisesFromMistakesUseCase.Result.Success).exercises
        assertEquals(2, created.size)
        assertTrue(created.all { it.sourceGameId == game1.id })

        val selector = GetTrainingExerciseUseCase(userRepo, mistakeRepo, exerciseRepo)

        // 1. При переходе с карточки 9-го хода первой выдаётся именно задача на 9-й ход
        val first = selector.execute(
            GetTrainingExerciseUseCase.Input(
                userId = alice.id!!,
                mode = TrainingMode.FromGame(gameId = game1.id!!, focusMoveNumber = 9)
            )
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(game1.id, first.exercise.sourceGameId)
        assertEquals(9, first.exercise.sourceMoveNumber)
        assertEquals("d1d8", first.exercise.solutionMoves.first())

        // 2. Следующей выдаётся оставшаяся ошибка той же партии (ход 4), без примеси game2 и общего банка
        val second = selector.execute(
            GetTrainingExerciseUseCase.Input(
                userId = alice.id!!,
                excludeExerciseId = first.exercise.id!!,
                mode = TrainingMode.FromGame(gameId = game1.id!!, focusMoveNumber = 9)
            )
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(game1.id, second.exercise.sourceGameId)
        assertEquals(4, second.exercise.sourceMoveNumber)
        assertEquals("h5f7", second.exercise.solutionMoves.first())

        // 3. Для Боба режим FromGame по партии Алисы строго пуст
        val bobAttempt = selector.execute(
            GetTrainingExerciseUseCase.Input(
                userId = bob.id!!,
                mode = TrainingMode.FromGame(gameId = game1.id!!)
            )
        )
        assertTrue(bobAttempt is GetTrainingExerciseUseCase.Result.NoSuitableExercises)
    }

    @Test
    fun skillTransfer_selectsUnseenSameSkillAndRole_andNeverFallsBackToGeneralQueue() = runBlocking {
        val userRepo = FakeUserRepo()
        val gameRepo = FakeGameRepo()
        val mistakeRepo = FakeMistakeRepo(gameRepo)
        val exerciseRepo = FakeExerciseRepo(gameRepo)

        val user = userRepo.save(User(nickname = "Transfer", email = "transfer@test.local", passwordHash = "h", rating = 1400))
        val uid = user.id!!
        val sourceGame = gameRepo.save(
            Game(userId = uid, pgnData = "1. e4 e5 *", playerColor = ChessColor.WHITE, playedAt = 1_000L)
        )
        val otherGame = gameRepo.save(
            Game(userId = uid, pgnData = "1. d4 d5 *", playerColor = ChessColor.WHITE, playedAt = 2_000L)
        )

        // Предварительно засеваем банк и помечаем решёнными все встроенные вилки и защиты,
        // чтобы изолированно контролировать пул незнакомых позиций для проверки переноса навыка.
        CuratedExerciseBank.ensureSeeded(exerciseRepo)
        val seeded = exerciseRepo.findAccessibleByUserId(uid)
        val forkIds = seeded.filter { it.theme == TacticalTheme.FORK }.mapNotNull { it.id }
        val defenseIds = seeded.filter {
            ExerciseMetadata.isDefensiveGoal(it) || it.theme == TacticalTheme.KING_SAFETY
        }.mapNotNull { it.id }
        for (exId in forkIds + defenseIds) {
            exerciseRepo.saveAttempt(
                ExerciseAttempt(
                    userId = uid,
                    exerciseId = exId,
                    solved = true,
                    timeSpentSeconds = 5,
                    movesMade = listOf("a1a2"),
                    attemptedAt = 10_000L
                )
            )
        }

        // Оставляем в банке НЕЗНАКОМЫЕ задачи других тем (PIN, MATE) — перенос навыка FORK
        // ни в коем случае не должен подменяться ими.
        // Добавляем 4 контролируемые позиции:
        // 1) вилка из исходной партии sourceGame (исключается excludeSourceGameId);
        // 2) знакомая вилка из otherGame (исключается schedule);
        // 3) незнакомая вилка (FORK_KNIGHT, атака) из otherGame — должна быть выбрана первой;
        // 4) незнакомая родственная вилка (FORK_ROOK, тема FORK, атака) — второй.
        val sameGameFork = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1",
                solutionMoves = listOf("e1e8"),
                tacticalPattern = TacticalPattern.FORK_KNIGHT,
                patternClusterId = "FORK_KNIGHT_MEDIUM",
                difficultyRating = 45,
                theme = TacticalTheme.FORK,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = sourceGame.id,
                sourceMoveNumber = 5
            )
        )
        val alreadySeenFork = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
                solutionMoves = listOf("d1d8"),
                tacticalPattern = TacticalPattern.FORK_KNIGHT,
                patternClusterId = "FORK_KNIGHT_MEDIUM",
                difficultyRating = 45,
                theme = TacticalTheme.FORK,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = otherGame.id,
                sourceMoveNumber = 7
            )
        )
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = uid,
                exerciseId = alreadySeenFork.id!!,
                solved = true,
                timeSpentSeconds = 4,
                movesMade = listOf("d1d8"),
                attemptedAt = 20_000L
            )
        )
        val unseenExactFork = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "4r1k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
                solutionMoves = listOf("d1d8"),
                tacticalPattern = TacticalPattern.FORK_KNIGHT,
                patternClusterId = "FORK_KNIGHT_MEDIUM",
                difficultyRating = 45,
                theme = TacticalTheme.FORK,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = otherGame.id,
                sourceMoveNumber = 9
            )
        )
        val unseenFamilyFork = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "2r3k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
                solutionMoves = listOf("d1d8"),
                tacticalPattern = TacticalPattern.FORK_ROOK,
                patternClusterId = "FORK_ROOK_MEDIUM",
                difficultyRating = 45,
                theme = TacticalTheme.FORK,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = otherGame.id,
                sourceMoveNumber = 11
            )
        )
        val unseenDefensive = exerciseRepo.saveExercise(
            Exercise(
                fenPosition = "1r4k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
                solutionMoves = listOf("d1d8"),
                tacticalPattern = TacticalPattern.COUNTER_ATTACK,
                patternClusterId = "COUNTER_ATTACK_MEDIUM",
                difficultyRating = 45,
                patternFeatures = listOf(ExerciseMetadata.defensiveGoalTag()),
                theme = TacticalTheme.KING_SAFETY,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = otherGame.id,
                sourceMoveNumber = 15
            )
        )

        val selector = GetTrainingExerciseUseCase(userRepo, mistakeRepo, exerciseRepo)
        val attackMode = TrainingMode.SkillTransfer(
            pattern = TacticalPattern.FORK_KNIGHT,
            theme = TacticalTheme.FORK,
            isDefensive = false,
            excludeSourceGameId = sourceGame.id,
            excludeExerciseId = sameGameFork.id
        )

        // 1. Первым отдаётся именно незнакомая вилка того же паттерна (не из sourceGame, не alreadySeenFork и не защита)
        val first = selector.execute(GetTrainingExerciseUseCase.Input(userId = uid, mode = attackMode))
            as GetTrainingExerciseUseCase.Result.Success
        assertEquals(unseenExactFork.id, first.exercise.id)

        // Отмечаем как решённую и переходим дальше
        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = uid, exerciseId = unseenExactFork.id!!, solved = true,
                timeSpentSeconds = 3, movesMade = listOf("d1d8"), attemptedAt = 30_000L
            )
        )

        // 2. Далее подбирается незнакомая позиция из семейства того же навыка (FORK_ROOK, атака)
        val second = selector.execute(
            GetTrainingExerciseUseCase.Input(userId = uid, excludeExerciseId = unseenExactFork.id!!, mode = attackMode)
        ) as GetTrainingExerciseUseCase.Result.Success
        assertEquals(unseenFamilyFork.id, second.exercise.id)

        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = uid, exerciseId = unseenFamilyFork.id!!, solved = true,
                timeSpentSeconds = 3, movesMade = listOf("d1d8"), attemptedAt = 40_000L
            )
        )

        // 3. Когда незнакомые позиции этого навыка исчерпаны, возвращается NoSuitableExercises
        // (без подмены задачами PIN/MATE/DEFENSIVE_MOVE из общей очереди)
        val exhausted = selector.execute(
            GetTrainingExerciseUseCase.Input(userId = uid, excludeExerciseId = unseenFamilyFork.id!!, mode = attackMode)
        )
        assertTrue(exhausted is GetTrainingExerciseUseCase.Result.NoSuitableExercises)

        // 4. Защитный перенос выбирает строго незнакомую защитную задачу, не смешиваясь с атакующей очередью
        val defenseMode = TrainingMode.SkillTransfer(
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            isDefensive = true,
            excludeSourceGameId = sourceGame.id
        )
        val defFirst = selector.execute(GetTrainingExerciseUseCase.Input(userId = uid, mode = defenseMode))
            as GetTrainingExerciseUseCase.Result.Success
        assertEquals(unseenDefensive.id, defFirst.exercise.id)

        exerciseRepo.saveAttempt(
            ExerciseAttempt(
                userId = uid, exerciseId = unseenDefensive.id!!, solved = true,
                timeSpentSeconds = 3, movesMade = listOf("d1d8"), attemptedAt = 50_000L
            )
        )
        val defExhausted = selector.execute(
            GetTrainingExerciseUseCase.Input(userId = uid, excludeExerciseId = unseenDefensive.id!!, mode = defenseMode)
        )
        assertTrue(defExhausted is GetTrainingExerciseUseCase.Result.NoSuitableExercises)
    }
}
