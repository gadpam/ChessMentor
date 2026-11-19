package com.example.chessmentor.domain.statistics

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.GamePhaseKind
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.StatsPeriod
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.TrendDirection
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PlayerAnalyticsCalculatorTest {

    private val dayMs = 86_400_000L
    private val fixedNow = 1_794_873_600_000L // 2026-11-17 00:00:00 UTC

    @Test
    fun `color split computes WDL, chess score, accuracy, ACPL and best win Elo`() {
        val games = listOf(
            sampleGame(
                id = 1L,
                color = ChessColor.WHITE,
                result = "1-0",
                eco = "C50",
                opening = "Итальянская партия",
                opponentElo = 1520,
                timeControl = "180+2",
                playedAt = fixedNow - 5 * dayMs,
                accuracy = 88.0,
                acpl = 24,
                blunders = 0
            ),
            sampleGame(
                id = 2L,
                color = ChessColor.WHITE,
                result = "1/2-1/2",
                eco = "C50",
                opening = "Итальянская партия",
                opponentElo = 1610,
                timeControl = "180+2",
                playedAt = fixedNow - 3 * dayMs,
                accuracy = 82.0,
                acpl = 32,
                blunders = 1
            ),
            sampleGame(
                id = 3L,
                color = ChessColor.BLACK,
                result = "1-0", // поражение чёрными
                eco = "B20",
                opening = "Сицилианская защита",
                opponentElo = 1580,
                timeControl = "600+0",
                playedAt = fixedNow - 2 * dayMs,
                accuracy = 64.0,
                acpl = 68,
                blunders = 2
            ),
            sampleGame(
                id = 4L,
                color = ChessColor.BLACK,
                result = "0-1", // победа чёрными над 1650!
                eco = "B12",
                opening = "Защита Каро-Канн",
                opponentElo = 1650,
                timeControl = "600+0",
                playedAt = fixedNow - dayMs,
                accuracy = 90.0,
                acpl = 20,
                blunders = 0
            )
        )

        val report = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = games,
            analyzedMovesByGameId = emptyMap(),
            mistakesByGameId = emptyMap(),
            period = StatsPeriod.ALL,
            nowMs = fixedNow
        )

        // Все партии: 2W 1D 1L = 2.5 / 4 = 62.5% очков, лучшая победа = 1650
        assertEquals(4, report.overall.gamesCount)
        assertEquals(2, report.overall.wins)
        assertEquals(1, report.overall.draws)
        assertEquals(1, report.overall.losses)
        assertEquals(62.5, report.overall.scorePercent!!, 0.01)
        assertEquals(81.0, report.overall.averageAccuracy!!, 0.01)
        assertEquals(36, report.overall.averageAcpl)
        assertEquals(1650, report.overall.bestWinOpponentElo)

        // Белые: 1W 1D 0L = 75.0%, точность 85.0%, лучшая победа = 1520 (1610 была ничьей)
        assertEquals(75.0, report.asWhite.scorePercent!!, 0.01)
        assertEquals(85.0, report.asWhite.averageAccuracy!!, 0.01)
        assertEquals(1520, report.asWhite.bestWinOpponentElo)

        // Чёрные: 1W 0D 1L = 50.0%, точность 77.0%, 1.0 зевок/партию
        assertEquals(50.0, report.asBlack.scorePercent!!, 0.01)
        assertEquals(77.0, report.asBlack.averageAccuracy!!, 0.01)
        assertEquals(1.0, report.asBlack.blundersPerGame!!, 0.01)

        // Разбивка по контролям времени: Блиц (2) и Рапид (2)
        assertEquals(2, report.timeControls.size)
        assertEquals(PgnParser.TimeControlCategory.BLITZ, report.timeControls[0].category)
        assertEquals(75.0, report.timeControls[0].scorePercent!!, 0.01)
        assertEquals(PgnParser.TimeControlCategory.RAPID, report.timeControls[1].category)
        assertEquals(50.0, report.timeControls[1].scorePercent!!, 0.01)
    }

    @Test
    fun `opening repertoire groups ECO variations, flips exit eval for Black and picks crown and weak lines`() {
        val games = listOf(
            // Сильный дебют за чёрных: Защита Каро-Канн (2 победы)
            sampleGame(1L, ChessColor.BLACK, "0-1", "B12", "Защита Каро-Канн: закрытый вариант", 1500, "300+3", fixedNow - 6 * dayMs, 91.0, 18, 0),
            sampleGame(2L, ChessColor.BLACK, "0-1", "B10", "Защита Каро-Канн: разменный вариант", 1530, "300+3", fixedNow - 5 * dayMs, 89.0, 21, 0),
            // Уязвимый дебют за чёрных: Сицилианская защита (2 поражения и плохая оценка на 10-м ходу)
            sampleGame(3L, ChessColor.BLACK, "1-0", "B90", "Сицилианская защита: вариант Найдорфа", 1520, "300+3", fixedNow - 4 * dayMs, 62.0, 74, 2),
            sampleGame(4L, ChessColor.BLACK, "1-0", "B70", "Сицилианская защита: вариант Дракона", 1490, "300+3", fixedNow - 3 * dayMs, 65.0, 66, 2),
            // Партия за белых только с ECO-кодом C60 без тега [Opening]
            sampleGame(5L, ChessColor.WHITE, "1-0", "C60", null, 1510, "300+3", fixedNow - dayMs, 86.0, 25, 0)
        )

        // В партиях 1 и 3 добавим ходы 10-го номера:
        // evalAfter всегда в сантипешках со стороны БЕЛЫХ!
        // В Каро-Канне evalAfter = -70 (выгодно чёрным -> +70 для игрока),
        // в Сицилианской evalAfter = +180 (выгодно белым -> -180 для игрока).
        val movesByGame = mapOf(
            1L to listOf(
                playerMove(1L, 1, 1, ChessColor.BLACK, "c6", MoveQuality.BOOK, 20, 15),
                playerMove(1L, 3, 2, ChessColor.BLACK, "d5", MoveQuality.BOOK, 15, 10),
                playerMove(1L, 19, 10, ChessColor.BLACK, "c5", MoveQuality.BEST_MOVE, -50, -70)
            ),
            3L to listOf(
                playerMove(3L, 1, 1, ChessColor.BLACK, "c5", MoveQuality.BOOK, 20, 20),
                playerMove(3L, 19, 10, ChessColor.BLACK, "b5", MoveQuality.MISTAKE, 40, 180)
            )
        )

        val rep = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = games,
            analyzedMovesByGameId = movesByGame,
            mistakesByGameId = emptyMap(),
            nowMs = fixedNow
        ).repertoire

        assertEquals(5, rep.recognizedGamesCount)
        assertEquals(2, rep.blackOpenings.size)
        assertEquals(1, rep.whiteOpenings.size)

        val caro = rep.blackOpenings.first { it.openingName == "Защита Каро-Канн" }
        assertEquals(2, caro.gamesCount)
        assertEquals(100.0, caro.scorePercent!!, 0.01)
        assertEquals(listOf("B10", "B12"), caro.ecoCodes)
        assertEquals(70, caro.averageExitEvalCp)
        assertEquals("+0.7", caro.formattedExitEval())
        assertEquals(2.0, caro.averageBookMoves!!, 0.01)

        val sicilian = rep.blackOpenings.first { it.openingName == "Сицилианская защита" }
        assertEquals(0.0, sicilian.scorePercent!!, 0.01)
        assertEquals(-180, sicilian.averageExitEvalCp)
        assertEquals("-1.8", sicilian.formattedExitEval())
        assertEquals(1, sicilian.earlyMistakesCount)

        // Коронный и уязвимый дебют за чёрных определены точно
        assertEquals("Защита Каро-Канн", rep.bestBlack?.openingName)
        assertEquals("Сицилианская защита", rep.weakestBlack?.openingName)

        // ECO C60 успешно раскрылся в русское имя «Испанская партия»
        assertEquals("Испанская партия", rep.whiteOpenings.first().openingName)

        // Тома ECO: B (4 партии) и C (1 партия)
        assertEquals(listOf('B', 'C'), rep.ecoFamilies.map { it.code })
        assertEquals(4, rep.ecoFamilies.first { it.code == 'B' }.gamesCount)
    }

    @Test
    fun `phases, opponent blunder punishment and clock time-trouble drop are computed accurately`() {
        val clockedPgn = """
            [Event "Rated Blitz"]
            [White "Hero"]
            [Black "Rival"]
            [Result "1-0"]
            [TimeControl "180+0"]
            [ECO "C50"]

            1. e4 {[%clk 0:02:58]} e5 {[%clk 0:02:57]} 2. Nf3 {[%clk 0:02:52]} Nc6 {[%clk 0:02:50]} 3. Bc4 {[%clk 0:00:15]} Nf6 {[%clk 0:02:40]} 4. Ng5 {[%clk 0:00:08]} d5 {[%clk 0:02:30]} 1-0
        """.trimIndent()

        val game = Game(
            id = 10L,
            userId = 1L,
            pgnData = clockedPgn,
            playerColor = ChessColor.WHITE,
            timeControl = "180+0",
            playedAt = fixedNow - dayMs,
            analysisStatus = AnalysisStatus.COMPLETED,
            accuracy = 78.0
        )

        val moves = listOf(
            // Обычное время (>20с): идеальные ходы на moveIndex 0 и 2
            playerMove(10L, 0, 1, ChessColor.WHITE, "e4", MoveQuality.BOOK, 20, 25),
            playerMove(10L, 2, 2, ChessColor.WHITE, "Nf3", MoveQuality.BEST_MOVE, 25, 30),
            // Цейтнот (<20с): грубые ошибки на moveIndex 4 и 6
            playerMove(10L, 4, 3, ChessColor.WHITE, "Bc4", MoveQuality.BLUNDER, 80, -220),
            playerMove(10L, 6, 4, ChessColor.WHITE, "Ng5", MoveQuality.MISTAKE, 50, -90),
            // Миттельшпиль (ходы 14..18): отличная точность + наказание зевка соперника
            playerMove(10L, 26, 14, ChessColor.WHITE, "Rfe1", MoveQuality.BEST_MOVE, 40, 45),
            playerMove(10L, 27, 14, ChessColor.BLACK, "b6", MoveQuality.BLUNDER, 45, 340),
            playerMove(10L, 28, 15, ChessColor.WHITE, "Nxe5", MoveQuality.GREAT_MOVE, 340, 350),
            playerMove(10L, 30, 16, ChessColor.WHITE, "Rad1", MoveQuality.BEST_MOVE, 350, 355),
            playerMove(10L, 31, 16, ChessColor.BLACK, "h6", MoveQuality.BLUNDER, 355, 620),
            playerMove(10L, 32, 17, ChessColor.WHITE, "a3", MoveQuality.BLUNDER, 620, 50), // упустил наказание
            // Эндшпиль (ходы 32..35): слабая точность
            playerMove(10L, 62, 32, ChessColor.WHITE, "Kf2", MoveQuality.MISTAKE, 120, -40),
            playerMove(10L, 64, 33, ChessColor.WHITE, "Ke3", MoveQuality.BLUNDER, 60, -250),
            playerMove(10L, 66, 34, ChessColor.WHITE, "g4", MoveQuality.MISTAKE, -50, -210),
            playerMove(10L, 68, 35, ChessColor.WHITE, "h4", MoveQuality.INACCURACY, -80, -150)
        )

        val report = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = listOf(game),
            analyzedMovesByGameId = mapOf(10L to moves),
            mistakesByGameId = emptyMap(),
            nowMs = fixedNow
        )

        // Фазы партии: миттельшпиль заметно сильнее эндшпиля
        assertEquals(GamePhaseKind.MIDDLEGAME, report.phases.strongest?.phase)
        assertEquals(GamePhaseKind.ENDGAME, report.phases.weakest?.phase)
        assertTrue((report.phases.gapPercent ?: 0.0) > 25.0)

        // Наказание зевков соперника: 1 из 2 = 50%
        assertEquals(2, report.moveQuality.opponentBlundersTotal)
        assertEquals(1, report.moveQuality.opponentBlundersPunished)
        assertEquals(50, report.moveQuality.opponentBlunderPunishPercent)

        // Диагностика цейтнота: в норме ~99–100%, в цейтноте резкая просадка
        assertEquals(1, report.timeTrouble.gamesWithClocks)
        assertEquals(2, report.timeTrouble.normalMovesCount)
        assertEquals(2, report.timeTrouble.timeTroubleMovesCount)
        assertTrue(report.timeTrouble.hasComparison)
        assertTrue((report.timeTrouble.accuracyDropPercent ?: 0.0) > 40.0)
    }

    @Test
    fun `period filtering, accuracy trend, rating curve and daily streak stay consistent`() {
        val accuracies = listOf(60.0, 62.0, 64.0, 82.0, 86.0, 90.0)
        val games = accuracies.mapIndexed { idx, acc ->
            // Первые 2 партии старше 30 дней, остальные 4 — в пределах последних 4 дней
            val ageDays = if (idx < 2) 45L - idx else (6 - idx).toLong()
            sampleGame(
                id = (idx + 1).toLong(),
                color = if (idx % 2 == 0) ChessColor.WHITE else ChessColor.BLACK,
                result = "1-0",
                eco = "C50",
                opening = "Итальянская партия",
                opponentElo = 1400 + idx * 20,
                timeControl = "300+0",
                playedAt = fixedNow - ageDays * dayMs,
                accuracy = acc,
                acpl = 30,
                blunders = 0
            )
        }

        val allReport = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = games,
            analyzedMovesByGameId = emptyMap(),
            mistakesByGameId = emptyMap(),
            period = StatsPeriod.ALL,
            nowMs = fixedNow
        )
        assertEquals(6, allReport.overall.gamesCount)
        assertEquals(TrendDirection.IMPROVING, allReport.accuracyTrendDirection)
        assertEquals(24.0, allReport.accuracyTrendDelta!!, 0.01)
        assertEquals(6, allReport.accuracySeries.size)

        val monthReport = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = games,
            analyzedMovesByGameId = emptyMap(),
            mistakesByGameId = emptyMap(),
            period = StatsPeriod.LAST_30_DAYS,
            nowMs = fixedNow
        )
        assertEquals(4, monthReport.overall.gamesCount)

        // Серия активных дней: партии в дни -4, -3, -2, -1 и решённая задача сегодня (0) => 5 дней подряд
        val ex = Exercise(
            id = 100L,
            fenPosition = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
            solutionMoves = listOf("g8f6"),
            tacticalPattern = TacticalPattern.KNIGHT_FORK,
            patternClusterId = "FORK_KNIGHT_MEDIUM",
            difficultyRating = 50,
            theme = TacticalTheme.FORK
        )
        val attempts = listOf(
            ExerciseAttempt(
                id = 1L,
                userId = 1L,
                exerciseId = 100L,
                solved = true,
                timeSpentSeconds = 12,
                movesMade = listOf("g8f6"),
                attemptedAt = fixedNow - 2 * dayMs
            ),
            ExerciseAttempt(
                id = 2L,
                userId = 1L,
                exerciseId = 100L,
                solved = true,
                timeSpentSeconds = 10,
                movesMade = listOf("g8f6"),
                attemptedAt = fixedNow - 1_000L
            )
        )

        val (activity, streak) = PlayerAnalyticsCalculator.computeDailyActivity(
            games = games,
            attempts = attempts,
            nowMs = fixedNow,
            zoneId = ZoneOffset.UTC
        )
        assertEquals(14, activity.size)
        assertEquals(5, streak)

        val ratingCurve = PlayerAnalyticsCalculator.computeRatingHistory(
            currentRating = 1345,
            attempts = attempts,
            exercisesById = mapOf(100L to ex)
        )
        assertEquals(2, ratingCurve.size)
        assertEquals(1345, ratingCurve.last().rating)
        assertTrue(ratingCurve.all { it.delta > 0 })
    }

    @Test
    fun `GetUserStatisticsUseCase aggregates repertoire, batch moves and actionable coaching`() = runBlocking {
        val user = User(
            id = 7L,
            nickname = "Karpov_Student",
            email = "student@example.com",
            passwordHash = "pbkdf2",
            rating = 1480,
            skillLevel = SkillLevel.INTERMEDIATE
        )
        val games = listOf(
            sampleGame(1L, ChessColor.WHITE, "1-0", "D35", "Ферзевый гамбит", 1520, "600+5", fixedNow - 2 * dayMs, 89.0, 22, 0),
            sampleGame(2L, ChessColor.BLACK, "1-0", "B20", "Сицилианская защита", 1500, "180+0", fixedNow - dayMs, 61.0, 75, 2)
        )
        val moves = listOf(
            playerMove(1L, 0, 1, ChessColor.WHITE, "d4", MoveQuality.BOOK, 20, 25),
            playerMove(2L, 1, 1, ChessColor.BLACK, "c5", MoveQuality.BOOK, 20, 20),
            playerMove(2L, 15, 8, ChessColor.BLACK, "Qc7", MoveQuality.BLUNDER, 10, 260)
        )
        val mistakes = listOf(
            Mistake(
                id = 1L,
                gameId = 2L,
                moveNumber = 8,
                color = ChessColor.BLACK,
                fenBefore = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                userMove = "Qc7",
                bestMove = "Nf6",
                evaluationLoss = 250,
                mistakeType = MistakeType.BLUNDER,
                themeId = 1L, // FORK
                comment = "Пропущена коневая вилка"
            )
        )

        var batchCalls = 0
        val useCase = GetUserStatisticsUseCase(
            userRepository = fakeUserRepo(user),
            gameRepository = fakeGameRepo(games),
            mistakeRepository = fakeMistakeRepo(mistakes),
            exerciseRepository = fakeExerciseRepo(),
            analyzedMoveRepository = object : AnalyzedMoveRepository {
                override suspend fun saveAll(moves: List<AnalyzedMove>): List<AnalyzedMove> = moves
                override suspend fun findByGameId(gameId: Long): List<AnalyzedMove> =
                    moves.filter { it.gameId == gameId }
                override suspend fun findByGameIds(gameIds: List<Long>): List<AnalyzedMove> {
                    batchCalls++
                    return moves.filter { it.gameId in gameIds }
                }
                override suspend fun findMistakesByGameId(gameId: Long): List<AnalyzedMove> =
                    moves.filter { it.gameId == gameId && it.isMistake() }
                override suspend fun findGoodMovesByGameId(gameId: Long): List<AnalyzedMove> =
                    moves.filter { it.gameId == gameId && it.isGoodMove() }
                override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove? =
                    moves.firstOrNull { it.gameId == gameId && it.moveIndex == moveIndex }
                override suspend fun deleteByGameId(gameId: Long) = Unit
            }
        )

        val res = useCase.execute(
            GetUserStatisticsUseCase.Input(userId = 7L, period = StatsPeriod.ALL, nowMs = fixedNow)
        )
        assertTrue(res is GetUserStatisticsUseCase.Result.Success)
        val stats = (res as GetUserStatisticsUseCase.Result.Success).statistics

        // Ровно 1 пакетный запрос к analyzed_moves (без N+1)
        assertEquals(1, batchCalls)
        assertEquals(2, stats.totalGames)
        assertEquals(2, stats.analyzedGames)
        assertEquals("Ферзевый гамбит", stats.gameAnalytics.repertoire.whiteOpenings.single().openingName)
        assertEquals("Сицилианская защита", stats.gameAnalytics.repertoire.blackOpenings.single().openingName)

        // Тема «Вилка» автоматически привязана к TrainingMode.ByTheme(FORK)
        val topTheme = stats.actionableProblemThemes.single()
        assertEquals("Вилка", topTheme.themeName)
        assertEquals(TrainingMode.ByTheme(TacticalTheme.FORK), topTheme.suggestedMode)
        assertNotNull(stats.coachingActions.firstOrNull())
    }

    // ==================== ТЕСТОВЫЕ ХЕЛПЕРЫ ====================

    private fun sampleGame(
        id: Long,
        color: ChessColor,
        result: String,
        eco: String?,
        opening: String?,
        opponentElo: Int,
        timeControl: String,
        playedAt: Long,
        accuracy: Double,
        acpl: Int,
        blunders: Int
    ): Game {
        val whiteName = if (color == ChessColor.WHITE) "Player" else "Opp"
        val blackName = if (color == ChessColor.BLACK) "Player" else "Opp"
        val whiteElo = if (color == ChessColor.WHITE) 1500 else opponentElo
        val blackElo = if (color == ChessColor.BLACK) 1500 else opponentElo
        val ecoTag = eco?.let { "[ECO \"$it\"]\n" }.orEmpty()
        val openingTag = opening?.let { "[Opening \"$it\"]\n" }.orEmpty()
        val pgn = """
            [Event "Rated Game"]
            [White "$whiteName"]
            [Black "$blackName"]
            [WhiteElo "$whiteElo"]
            [BlackElo "$blackElo"]
            [Result "$result"]
            [TimeControl "$timeControl"]
            $ecoTag$openingTag
            1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 $result
        """.trimIndent()

        return Game(
            id = id,
            userId = 1L,
            pgnData = pgn,
            playerColor = color,
            timeControl = timeControl,
            playedAt = playedAt,
            analysisStatus = AnalysisStatus.COMPLETED,
            analyzedAt = playedAt + 60_000L,
            accuracy = accuracy,
            averageEvaluationLoss = acpl,
            totalMistakes = blunders,
            blundersCount = blunders
        )
    }

    private fun playerMove(
        gameId: Long,
        moveIndex: Int,
        moveNumber: Int,
        color: ChessColor,
        san: String,
        quality: MoveQuality,
        evalBeforeWhiteCp: Int,
        evalAfterWhiteCp: Int
    ): AnalyzedMove = AnalyzedMove(
        id = gameId * 1000L + moveIndex,
        gameId = gameId,
        moveIndex = moveIndex,
        moveNumber = moveNumber,
        color = color,
        quality = quality,
        san = san,
        evalBefore = evalBeforeWhiteCp,
        evalAfter = evalAfterWhiteCp,
        evalLoss = (if (color == ChessColor.WHITE) evalBeforeWhiteCp - evalAfterWhiteCp else evalAfterWhiteCp - evalBeforeWhiteCp)
            .coerceAtLeast(0)
    )

    private fun fakeUserRepo(user: User) = object : UserRepository {
        override suspend fun findById(id: Long): User? = user.takeIf { it.id == id }
        override suspend fun findByEmail(email: String): User? = user.takeIf { it.email == email }
        override suspend fun findByNickname(nickname: String): User? = user.takeIf { it.nickname == nickname }
        override suspend fun save(user: User): User = user
        override suspend fun update(user: User): User = user
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun existsByEmail(email: String): Boolean = user.email == email
        override suspend fun existsByNickname(nickname: String): Boolean = user.nickname == nickname
        override suspend fun findAll(): List<User> = listOf(user)
        override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
            listOf(user).filter { it.skillLevel == skillLevel }
        override suspend fun findTopByRating(limit: Int): List<User> = listOf(user).take(limit)
    }

    private fun fakeGameRepo(games: List<Game>) = object : GameRepository {
        override suspend fun findById(id: Long): Game? = games.firstOrNull { it.id == id }
        override suspend fun findByUserId(userId: Long): List<Game> = games
        override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> =
            games.filter { it.analysisStatus == status }
        override suspend fun save(game: Game): Game = game
        override suspend fun update(game: Game): Game = game
        override suspend fun deleteById(id: Long) = Unit
    }

    private fun fakeMistakeRepo(mistakes: List<Mistake>) = object : MistakeRepository {
        override suspend fun findById(id: Long): Mistake? = mistakes.firstOrNull { it.id == id }
        override suspend fun findByGameId(gameId: Long): List<Mistake> = mistakes.filter { it.gameId == gameId }
        override suspend fun findByUserId(userId: Long): List<Mistake> = mistakes
        override suspend fun findByGameIds(gameIds: List<Long>): List<Mistake> =
            mistakes.filter { it.gameId in gameIds }
        override suspend fun save(mistake: Mistake): Mistake = mistake
        override suspend fun saveAll(mistakes: List<Mistake>): List<Mistake> = mistakes
        override suspend fun update(mistake: Mistake): Mistake = mistake
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun deleteByGameId(gameId: Long) = Unit
        override suspend fun findByThemeId(themeId: Long): List<Mistake> = mistakes.filter { it.themeId == themeId }
        override suspend fun findByGameIdAndType(gameId: Long, type: MistakeType): List<Mistake> =
            mistakes.filter { it.gameId == gameId && it.mistakeType == type }
        override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<Mistake> =
            mistakes.filter { it.themeId == themeId }
        override suspend fun countByGameId(gameId: Long): Int = mistakes.count { it.gameId == gameId }
        override suspend fun countByUserIdAndType(userId: Long, type: MistakeType): Int =
            mistakes.count { it.mistakeType == type }
        override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): Map<Long, Int> =
            mistakes.groupingBy { it.themeId }.eachCount()
        override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? =
            mistakes.takeIf { it.isNotEmpty() }?.map { it.evaluationLoss }?.average()
    }

    private fun fakeExerciseRepo(): ExerciseRepository {
        val stored = mutableListOf<Exercise>()
        val attempts = mutableListOf<ExerciseAttempt>()
        var nextId = 1L
        return object : ExerciseRepository {
            override suspend fun findById(id: Long): Exercise? = stored.firstOrNull { it.id == id }
            override suspend fun findByIds(ids: List<Long>): List<Exercise> = stored.filter { it.id in ids }
            override suspend fun findSuitableExercise(userId: Long, userRating: Int, excludeId: Long): Exercise? =
                stored.firstOrNull { it.id != excludeId }
            override suspend fun findFailedUnresolvedExercise(userId: Long, excludeId: Long): Exercise? = null
            override suspend fun findUserMistakeExercise(userId: Long, excludeId: Long): Exercise? =
                stored.firstOrNull { it.sourceGameId != null && it.id != excludeId }
            override suspend fun findExerciseByTheme(
                userId: Long,
                theme: TacticalTheme,
                userRating: Int,
                excludeId: Long
            ): Exercise? = stored.firstOrNull { it.theme == theme && it.id != excludeId }
            override suspend fun findByTheme(theme: String): List<Exercise> =
                stored.filter { it.theme.name == theme }
            override suspend fun findByPattern(pattern: String): List<Exercise> =
                stored.filter { it.tacticalPattern.name == pattern }
            override suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise? =
                stored.firstOrNull { it.sourceGameId == gameId && it.sourceMoveNumber == moveNumber }
            override suspend fun findByUserId(userId: Long): List<Exercise> =
                stored.filter { it.sourceGameId != null }
            override suspend fun findAccessibleByUserId(userId: Long): List<Exercise> = stored.toList()
            override suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise? =
                stored.firstOrNull { it.id != excludeId }
            override suspend fun findGlobalByFen(fen: String): Exercise? =
                stored.firstOrNull { it.sourceGameId == null && it.fenPosition == fen }
            override suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise? =
                stored.firstOrNull { it.sourceGameId != null && it.fenPosition == fen }
            override suspend fun findByFen(fen: String): Exercise? =
                stored.firstOrNull { it.fenPosition == fen }
            override suspend fun deleteDuplicatesForUser(userId: Long) = Unit
            override suspend fun deleteGlobalDuplicates() = Unit
            override suspend fun deleteBySourceGameId(gameId: Long) {
                stored.removeAll { it.sourceGameId == gameId }
            }
            override suspend fun countExercises(): Int = stored.size
            override suspend fun countGlobalExercises(): Int = stored.count { it.sourceGameId == null }
            override suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean =
                attempts.any { it.userId == userId && it.exerciseId == exerciseId && it.solved }
            override suspend fun saveExercise(exercise: Exercise): Exercise {
                val saved = if (exercise.id == null) exercise.copy(id = nextId++) else exercise
                stored.removeAll { it.id == saved.id }
                stored.add(saved)
                return saved
            }
            override suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt {
                attempts.add(attempt)
                return attempt
            }
            override suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt> =
                attempts.filter { it.userId == userId }
            override suspend fun findByCluster(
                userId: Long,
                clusterId: String,
                excludeId: Long,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun findSimilarByPattern(
                userId: Long,
                pattern: String,
                excludeId: Long,
                minDifficulty: Int,
                maxDifficulty: Int,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun findForMistakeRepetition(
                userId: Long,
                pattern: String,
                difficulty: Int,
                excludeId: Long,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun incrementTimesShown(exerciseId: Long) = Unit
            override suspend fun incrementTimesSolved(exerciseId: Long) = Unit
        }
    }
}
