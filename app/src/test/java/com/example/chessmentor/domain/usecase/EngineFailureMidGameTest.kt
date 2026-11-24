package com.example.chessmentor.domain.usecase

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.TransactionRunner
import com.example.chessmentor.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия на сбой Stockfish посередине партии: первый неудачный поиск должен
 * прерывать разбор целиком, а не пропускать один ход и продолжать остальные.
 * Итоговая оценка, точность и список ошибок при этом не сохраняются, партия
 * переходит в FAILED, а разбор можно запустить повторно только явно.
 */
class EngineFailureMidGameTest {

    companion object {
        private const val GAME_PGN = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7"
    }

    // Движок отвечает на стартовую позицию и два хода, затем возвращает null —
    // симулируем сбой Stockfish посередине партии (на ходу 3, после 2 успешных поисков).
    private class FailingEngine : ChessEngine {
        var calls = 0
            private set

        override val status: StateFlow<EngineStatus> =
            MutableStateFlow(EngineStatus.Ready()).asStateFlow()

        override suspend fun init(): Boolean = true

        override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? {
            calls++
            return if (calls <= 2) {
                AnalysisLine(bestMove = "e2e4", score = 20, mateIn = null, principalVariation = emptyList())
            } else {
                null
            }
        }

        override suspend fun evaluate(fen: String, depthLimit: Int): Int = 20
        override suspend fun getBestMove(fen: String, depthLimit: Int): String? = "e2e4"
        override suspend fun setOption(name: String, value: String) = Unit
        override fun cancelInit() = Unit
        override fun stop() = Unit
        override fun destroy() = Unit
    }

    private class FakeGameRepository(
        private var storedGame: Game? = null
    ) : GameRepository {
        var lastSaved: Game? = null
        var lastUpdated: Game? = null
        var deletedIds = mutableListOf<Long>()

        override suspend fun findById(id: Long): Game? =
            storedGame?.copy(id = id) ?: Game(
                id = id,
                userId = 1L,
                pgnData = EngineFailureMidGameTest.GAME_PGN,
                playerColor = ChessColor.WHITE,
                playedAt = 1L
            )

        override suspend fun findByUserId(userId: Long): List<Game> = emptyList()
        override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> = emptyList()

        override suspend fun save(game: Game): Game {
            lastSaved = game
            return game.copy(id = 42L)
        }

        override suspend fun update(game: Game): Game {
            lastUpdated = game
            return game
        }

        override suspend fun deleteById(id: Long) {
            deletedIds.add(id)
        }
    }

    private class FakeUserRepository : UserRepository {
        override suspend fun findById(id: Long): User? =
            User(id = id, nickname = "Tester", email = "tester@test.local", passwordHash = "hash")
        override suspend fun findByEmail(email: String): User? = null
        override suspend fun findByNickname(nickname: String): User? = null
        override suspend fun save(user: User): User = user
        override suspend fun update(user: User): User = user
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun existsByEmail(email: String): Boolean = false
        override suspend fun existsByNickname(nickname: String): Boolean = false
        override suspend fun findAll(): List<User> = emptyList()
        override suspend fun findBySkillLevel(skillLevel: com.example.chessmentor.domain.entity.SkillLevel): List<User> = emptyList()
        override suspend fun findTopByRating(limit: Int): List<User> = emptyList()
    }

    private class FakeMistakeRepository : MistakeRepository {
        var savedAll: List<com.example.chessmentor.domain.entity.Mistake>? = null
        override suspend fun findById(id: Long): com.example.chessmentor.domain.entity.Mistake? = null
        override suspend fun findByGameId(gameId: Long): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun findByUserId(userId: Long): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun findByGameIds(gameIds: List<Long>): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun save(mistake: com.example.chessmentor.domain.entity.Mistake): com.example.chessmentor.domain.entity.Mistake = mistake
        override suspend fun saveAll(mistakes: List<com.example.chessmentor.domain.entity.Mistake>): List<com.example.chessmentor.domain.entity.Mistake> {
            savedAll = mistakes
            return mistakes
        }
        override suspend fun update(mistake: com.example.chessmentor.domain.entity.Mistake): com.example.chessmentor.domain.entity.Mistake = mistake
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteByGameId(gameId: Long) = Unit
        override suspend fun findByThemeId(themeId: Long): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun findByGameIdAndType(gameId: Long, type: com.example.chessmentor.domain.entity.MistakeType): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<com.example.chessmentor.domain.entity.Mistake> = emptyList()
        override suspend fun countByGameId(gameId: Long): Int = 0
        override suspend fun countByUserIdAndType(userId: Long, type: com.example.chessmentor.domain.entity.MistakeType): Int = 0
        override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): Map<Long, Int> = emptyMap()
        override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? = null
    }

    private class FakeAnalyzedMoveRepository : AnalyzedMoveRepository {
        var savedAll: List<com.example.chessmentor.domain.entity.AnalyzedMove>? = null
        override suspend fun saveAll(moves: List<com.example.chessmentor.domain.entity.AnalyzedMove>): List<com.example.chessmentor.domain.entity.AnalyzedMove> {
            savedAll = moves
            return moves
        }
        override suspend fun findByGameId(gameId: Long): List<com.example.chessmentor.domain.entity.AnalyzedMove> = emptyList()
        override suspend fun findByGameIds(gameIds: List<Long>): List<com.example.chessmentor.domain.entity.AnalyzedMove> = emptyList()
        override suspend fun findMistakesByGameId(gameId: Long): List<com.example.chessmentor.domain.entity.AnalyzedMove> = emptyList()
        override suspend fun findGoodMovesByGameId(gameId: Long): List<com.example.chessmentor.domain.entity.AnalyzedMove> = emptyList()
        override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): com.example.chessmentor.domain.entity.AnalyzedMove? = null
        override suspend fun deleteByGameId(gameId: Long) = Unit
    }

    private class FakeEngineSettingsRepository : EngineSettingsRepository {
        override fun getSettings(): com.example.chessmentor.domain.entity.EngineSettings =
            com.example.chessmentor.domain.entity.EngineSettings.default()
        override fun saveSettings(settings: com.example.chessmentor.domain.entity.EngineSettings) = Unit
        override fun resetToDefault() = Unit
        override fun updateDepth(depth: Int) = Unit
        override fun updateThreads(threads: Int) = Unit
        override fun updateHashSize(hashSizeMb: Int) = Unit
    }

    @Test
    fun `engine failure in the middle stops analysis and does not save partial results`() = runBlocking {
        val engine = FailingEngine()
        val gameRepo = FakeGameRepository()
        val mistakeRepo = FakeMistakeRepository()
        val analyzedMoveRepo = FakeAnalyzedMoveRepository()
        val userRepo = FakeUserRepository()
        val settingsRepo = FakeEngineSettingsRepository()

        val useCase = AnalyzeGameUseCase(
            gameRepository = gameRepo,
            mistakeRepository = mistakeRepo,
            analyzedMoveRepository = analyzedMoveRepo,
            userRepository = userRepo,
            chessEngine = engine,
            engineSettingsRepository = settingsRepo
        )

        val result = useCase.execute(AnalyzeGameUseCase.Input(gameId = 7L))

        // Движок вызван на стартовую позицию и два хода, затем вернул null — разбор остановлен.
        assertEquals(3, engine.calls)

        // Результат — ошибка, разбор не завершён успешно.
        assertTrue(result is AnalyzeGameUseCase.Result.Error)

        // партия переведена в FAILED (не COMPLETED): последний update зафиксировал неудачу.
        val lastUpdated = gameRepo.lastUpdated
        assertNotNull(lastUpdated)
        assertEquals(AnalysisStatus.FAILED, lastUpdated?.analysisStatus)

        // Никакие analysed moves / ошибки не сохранились: анализ прервался до saveAll.
        assertNull(analyzedMoveRepo.savedAll)
        assertNull(mistakeRepo.savedAll)
    }

    @Test
    fun `cancelling forceReanalyze before deleting old rows preserves COMPLETED status`() = runBlocking {
        val completedGame = Game(
            id = 8L,
            userId = 1L,
            pgnData = GAME_PGN,
            playerColor = ChessColor.WHITE,
            playedAt = 1L,
            analysisStatus = AnalysisStatus.COMPLETED,
            accuracy = 87.5,
            evaluationsJson = "[20, 15]"
        )
        val cancellingEngine = object : ChessEngine {
            override val status: StateFlow<EngineStatus> =
                MutableStateFlow(EngineStatus.Idle).asStateFlow()
            override suspend fun init(): Boolean = throw kotlinx.coroutines.CancellationException("cancelled")
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = null
            override suspend fun evaluate(fen: String, depthLimit: Int): Int = 0
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = null
            override suspend fun setOption(name: String, value: String) = Unit
            override fun cancelInit() = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        val gameRepo = FakeGameRepository(storedGame = completedGame)
        val useCase = AnalyzeGameUseCase(
            gameRepository = gameRepo,
            mistakeRepository = FakeMistakeRepository(),
            analyzedMoveRepository = FakeAnalyzedMoveRepository(),
            userRepository = FakeUserRepository(),
            chessEngine = cancellingEngine,
            engineSettingsRepository = FakeEngineSettingsRepository()
        )

        var caughtCancel = false
        try {
            useCase.execute(AnalyzeGameUseCase.Input(gameId = 8L, forceReanalyze = true))
        } catch (_: kotlinx.coroutines.CancellationException) {
            caughtCancel = true
        }

        assertTrue(caughtCancel)
        assertEquals(AnalysisStatus.COMPLETED, gameRepo.lastUpdated?.analysisStatus)
    }

    @Test
    fun `transaction failure during forceReanalyze rolls back deletes and preserves previous analysis`() = runBlocking {
        val initialMoves = mutableListOf(
            com.example.chessmentor.domain.entity.AnalyzedMove(
                gameId = 9L,
                moveIndex = 0,
                moveNumber = 1,
                color = ChessColor.WHITE,
                quality = com.example.chessmentor.domain.entity.MoveQuality.GOOD,
                san = "e4",
                evalBefore = 20,
                evalAfter = 20,
                evalLoss = 0
            )
        )
        val initialMistakes = mutableListOf(
            com.example.chessmentor.domain.entity.Mistake(
                gameId = 9L,
                themeId = 1L,
                moveNumber = 1,
                color = ChessColor.WHITE,
                evaluationLoss = 150,
                mistakeType = com.example.chessmentor.domain.entity.MistakeType.MISTAKE,
                bestMove = "d4",
                userMove = "e4",
                fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            )
        )
        val storedGame = Game(
            id = 9L,
            userId = 1L,
            pgnData = "1. e4 e5",
            playerColor = ChessColor.WHITE,
            playedAt = 1L,
            analysisStatus = AnalysisStatus.COMPLETED,
            accuracy = 88.0,
            evaluationsJson = "[20, 15, 20]"
        )

        var currentGameState = storedGame
        val transactionalGameRepo = object : GameRepository {
            override suspend fun findById(id: Long): Game? = currentGameState.copy(id = id)
            override suspend fun findByUserId(userId: Long): List<Game> = listOf(currentGameState)
            override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> = emptyList()
            override suspend fun save(game: Game): Game = game
            override suspend fun update(game: Game): Game {
                currentGameState = game
                return game
            }
            override suspend fun deleteById(id: Long) = Unit
        }
        val rollbackableMistakeRepo = object : MistakeRepository {
            override suspend fun findById(id: Long) = null
            override suspend fun findByGameId(gameId: Long) = initialMistakes.toList()
            override suspend fun findByUserId(userId: Long) = initialMistakes.toList()
            override suspend fun findByGameIds(gameIds: List<Long>) = initialMistakes.toList()
            override suspend fun save(mistake: com.example.chessmentor.domain.entity.Mistake) = mistake
            override suspend fun saveAll(mistakes: List<com.example.chessmentor.domain.entity.Mistake>) =
                throw IllegalStateException("Simulated DB write failure")
            override suspend fun update(mistake: com.example.chessmentor.domain.entity.Mistake) = mistake
            override suspend fun deleteById(id: Long) = Unit
            override suspend fun deleteByGameId(gameId: Long) { initialMistakes.clear() }
            override suspend fun findByThemeId(themeId: Long) = emptyList<com.example.chessmentor.domain.entity.Mistake>()
            override suspend fun findByGameIdAndType(gameId: Long, type: com.example.chessmentor.domain.entity.MistakeType) =
                emptyList<com.example.chessmentor.domain.entity.Mistake>()
            override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long) =
                emptyList<com.example.chessmentor.domain.entity.Mistake>()
            override suspend fun countByGameId(gameId: Long) = initialMistakes.size
            override suspend fun countByUserIdAndType(userId: Long, type: com.example.chessmentor.domain.entity.MistakeType) = 0
            override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int) = emptyMap<Long, Int>()
            override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? = null
        }
        val rollbackableMovesRepo = object : AnalyzedMoveRepository {
            override suspend fun saveAll(moves: List<com.example.chessmentor.domain.entity.AnalyzedMove>): List<com.example.chessmentor.domain.entity.AnalyzedMove> {
                initialMoves.addAll(moves)
                return moves
            }
            override suspend fun findByGameId(gameId: Long) = initialMoves.toList()
            override suspend fun findByGameIds(gameIds: List<Long>) = initialMoves.toList()
            override suspend fun findMistakesByGameId(gameId: Long) = emptyList<com.example.chessmentor.domain.entity.AnalyzedMove>()
            override suspend fun findGoodMovesByGameId(gameId: Long) = emptyList<com.example.chessmentor.domain.entity.AnalyzedMove>()
            override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int) = null
            override suspend fun deleteByGameId(gameId: Long) { initialMoves.clear() }
        }
        val rollbackRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                val savedGame = currentGameState
                val savedMoves = initialMoves.toList()
                val savedMistakes = initialMistakes.toList()
                return try {
                    block()
                } catch (t: Throwable) {
                    currentGameState = savedGame
                    initialMoves.clear(); initialMoves.addAll(savedMoves)
                    initialMistakes.clear(); initialMistakes.addAll(savedMistakes)
                    throw t
                }
            }
        }
        val readyEngine = object : ChessEngine {
            override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Ready()).asStateFlow()
            override suspend fun init(): Boolean = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int) =
                AnalysisLine(bestMove = "e2e4", score = 20, mateIn = null, principalVariation = emptyList())
            override suspend fun evaluate(fen: String, depthLimit: Int) = 20
            override suspend fun getBestMove(fen: String, depthLimit: Int) = "e2e4"
            override suspend fun setOption(name: String, value: String) = Unit
            override fun cancelInit() = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }

        val useCase = AnalyzeGameUseCase(
            gameRepository = transactionalGameRepo,
            mistakeRepository = rollbackableMistakeRepo,
            analyzedMoveRepository = rollbackableMovesRepo,
            userRepository = FakeUserRepository(),
            chessEngine = readyEngine,
            engineSettingsRepository = FakeEngineSettingsRepository(),
            transactionRunner = rollbackRunner
        )

        val result = useCase.execute(AnalyzeGameUseCase.Input(gameId = 9L, forceReanalyze = true))
        assertTrue(result is AnalyzeGameUseCase.Result.Error)
        // Транзакционный откат сохранил прежние 1 ход и 1 ошибку вместо пустой таблицы
        assertEquals(1, initialMoves.size)
        assertEquals(1, initialMistakes.size)
    }
}
