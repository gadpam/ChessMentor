package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.usecase.GenerateExercisesFromMistakesUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingRatingCalculatorTest {

    private fun makeExercise(
        id: Long = 1L,
        difficulty: Int = 50,
        pattern: TacticalPattern = TacticalPattern.FORK_KNIGHT,
        timesShown: Int = 0,
        timesSolved: Int = 0,
        successRate: Int = 0
    ): Exercise = Exercise(
        id = id,
        fenPosition = "6k1/r4ppp/8/8/8/8/P4PPP/3R2K1 w - - 0 1",
        solutionMoves = listOf("d1d8"),
        tacticalPattern = pattern,
        patternClusterId = "${pattern.name}_MEDIUM",
        difficultyRating = difficulty,
        theme = TacticalTheme.FORK,
        rating = TrainingRatingCalculator.difficultyToElo(difficulty),
        timesShown = timesShown,
        timesSolved = timesSolved,
        successRate = successRate
    )

    @Test
    fun difficultyAndEloConversions_areConsistentAndClamped() {
        assertEquals(800, TrainingRatingCalculator.difficultyToElo(0))
        assertEquals(1200, TrainingRatingCalculator.difficultyToElo(25))
        assertEquals(1600, TrainingRatingCalculator.difficultyToElo(50))
        assertEquals(2400, TrainingRatingCalculator.difficultyToElo(100))

        assertEquals(25, TrainingRatingCalculator.eloToDifficulty(1200))
        assertEquals(50, TrainingRatingCalculator.eloToDifficulty(1600))
        assertEquals(10, TrainingRatingCalculator.eloToDifficulty(400))
        assertEquals(95, TrainingRatingCalculator.eloToDifficulty(3200))
    }

    @Test
    fun calculateSolveAndFailureDelta_respectRelativeDifficulty() {
        val playerElo = 1500
        val easy = makeExercise(difficulty = 15)  // ~1040 Elo
        val hard = makeExercise(difficulty = 80)  // ~2080 Elo

        val easyGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo = playerElo,
            exercise = easy,
            hadMistake = false,
            maxHintUsed = HintUsage.NONE
        )
        val hardGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo = playerElo,
            exercise = hard,
            hadMistake = false,
            maxHintUsed = HintUsage.NONE
        )
        assertTrue("Harder puzzle must award more Elo ($hardGain vs $easyGain)", hardGain > easyGain)

        val easyLoss = TrainingRatingCalculator.calculateFailureDelta(playerElo, easy)
        val hardLoss = TrainingRatingCalculator.calculateFailureDelta(playerElo, hard)
        assertTrue("Losses must be negative", easyLoss < 0 && hardLoss < 0)
        assertTrue(
            "Failing an easy puzzle must lose more Elo than failing a hard one ($easyLoss vs $hardLoss)",
            easyLoss < hardLoss
        )
    }

    @Test
    fun calculateSolveDelta_usesEffectiveDifficultyFromHistoricalSuccessRate() {
        val playerElo = 1600
        val overrated = makeExercise(difficulty = 50, timesShown = 10, timesSolved = 9, successRate = 90)
        val underrated = makeExercise(difficulty = 50, timesShown = 10, timesSolved = 2, successRate = 20)

        val gainOverrated = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, overrated, hadMistake = false, maxHintUsed = HintUsage.NONE
        )
        val gainUnderrated = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, underrated, hadMistake = false, maxHintUsed = HintUsage.NONE
        )
        assertTrue(gainUnderrated > gainOverrated)
    }

    @Test
    fun calculateSolveDelta_preventsHintAndRetryRatingFarming() {
        val playerElo = 1600
        val equalPuzzle = makeExercise(difficulty = 50)

        val cleanGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, equalPuzzle, hadMistake = false, maxHintUsed = HintUsage.NONE
        )
        val pieceHintGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, equalPuzzle, hadMistake = false, maxHintUsed = HintUsage.PIECE_HIGHLIGHT
        )
        val fullHintGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, equalPuzzle, hadMistake = false, maxHintUsed = HintUsage.FULL_SOLUTION
        )
        val revealedGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo, equalPuzzle, hadMistake = false, maxHintUsed = HintUsage.NONE, solutionRevealed = true
        )

        assertEquals(0, fullHintGain)
        assertEquals(0, revealedGain)
        assertTrue(pieceHintGain in 1 until cleanGain)

        val failDelta = TrainingRatingCalculator.calculateFailureDelta(playerElo, equalPuzzle)
        val retryGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo + failDelta, equalPuzzle, hadMistake = true, maxHintUsed = HintUsage.NONE
        )
        assertTrue(
            "Fail ($failDelta) + retry solve (+$retryGain) must not yield positive net Elo",
            failDelta + retryGain < 0
        )
    }

    @Test
    fun trimPvToPlayerLastMove_alwaysReturnsOddLengthOrEmpty() {
        assertEquals(emptyList<String>(), TrainingRatingCalculator.trimPvToPlayerLastMove(emptyList()))
        assertEquals(listOf("e2e4"), TrainingRatingCalculator.trimPvToPlayerLastMove(listOf("e2e4")))
        assertEquals(
            listOf("e2e4"),
            TrainingRatingCalculator.trimPvToPlayerLastMove(listOf("e2e4", "e7e5"))
        )
        assertEquals(
            listOf("e2e4", "e7e5", "g1f3"),
            TrainingRatingCalculator.trimPvToPlayerLastMove(listOf("e2e4", "e7e5", "g1f3", "b8c6"))
        )
        assertEquals(
            listOf("m1", "m2", "m3", "m4", "m5"),
            TrainingRatingCalculator.trimPvToPlayerLastMove(
                listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7"),
                maxPlies = 5
            )
        )
        assertEquals(
            listOf("m1", "m2", "m3"),
            TrainingRatingCalculator.trimPvToPlayerLastMove(
                listOf("m1", "m2", "m3", "m4", "m5"),
                maxPlies = 4
            )
        )
    }

    @Test
    fun computePatternMastery_ordersWeakestPatternsFirst() {
        val forkEx = makeExercise(id = 1L, pattern = TacticalPattern.FORK_KNIGHT)
        val pinEx1 = makeExercise(id = 2L, pattern = TacticalPattern.PIN_ABSOLUTE)
        val pinEx2 = makeExercise(id = 3L, pattern = TacticalPattern.PIN_ABSOLUTE)
        val exercises = mapOf(1L to forkEx, 2L to pinEx1, 3L to pinEx2)

        val attempts = listOf(
            ExerciseAttempt(userId = 10L, exerciseId = 1L, solved = true, timeSpentSeconds = 8, movesMade = listOf("d5c7")),
            ExerciseAttempt(userId = 10L, exerciseId = 1L, solved = true, timeSpentSeconds = 6, movesMade = listOf("d5c7")),
            ExerciseAttempt(userId = 10L, exerciseId = 2L, solved = false, timeSpentSeconds = 20, movesMade = listOf("a1b1")),
            ExerciseAttempt(userId = 10L, exerciseId = 3L, solved = false, timeSpentSeconds = 25, movesMade = listOf("a1c1")),
            ExerciseAttempt(userId = 10L, exerciseId = 3L, solved = true, timeSpentSeconds = 12, movesMade = listOf("a1e1"))
        )

        val mastery = TrainingRatingCalculator.computePatternMastery(exercises, attempts)
        assertEquals(2, mastery.size)

        val weakest = mastery.first()
        assertEquals(TacticalPattern.PIN_ABSOLUTE, weakest.pattern)
        assertEquals(MasteryLevel.WEAK, weakest.level)
        assertEquals(33, weakest.accuracyPercent)
        assertEquals(1, weakest.unresolvedCount)

        val strongest = mastery.last()
        assertEquals(TacticalPattern.FORK_KNIGHT, strongest.pattern)
        assertEquals(MasteryLevel.STRONG, strongest.level)
        assertEquals(100, strongest.accuracyPercent)
        assertEquals(100, strongest.masteryPercent)
        assertEquals(0, strongest.unresolvedCount)
    }

    @Test
    fun symmetricKFactor_eliminatesInflationAndCalibratesNewPlayersFaster() {
        val equalPuzzle = makeExercise(difficulty = 50) // 1600 Elo
        for (experience in listOf(0, 5, 15, 45)) {
            val gain = TrainingRatingCalculator.calculateSolveDelta(
                playerElo = 1600,
                exercise = equalPuzzle,
                hadMistake = false,
                maxHintUsed = HintUsage.NONE,
                completedRatedSittings = experience
            )
            val loss = TrainingRatingCalculator.calculateFailureDelta(
                playerElo = 1600,
                exercise = equalPuzzle,
                completedRatedSittings = experience
            )
            assertEquals("Zero Elo drift at 50% win rate (exp=$experience)", 0, gain + loss)
        }

        val provisionalGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo = 1600,
            exercise = equalPuzzle,
            hadMistake = false,
            maxHintUsed = HintUsage.NONE,
            completedRatedSittings = 2
        )
        val establishedGain = TrainingRatingCalculator.calculateSolveDelta(
            playerElo = 1600,
            exercise = equalPuzzle,
            hadMistake = false,
            maxHintUsed = HintUsage.NONE,
            completedRatedSittings = 50
        )
        assertTrue(
            "Provisional K must adapt faster than established K ($provisionalGain vs $establishedGain)",
            provisionalGain > establishedGain
        )
        assertEquals(0, TrainingRatingCalculator.calculateRepeatedGuessPenalty(1))
        assertTrue(TrainingRatingCalculator.calculateRepeatedGuessPenalty(2) < 0)
        assertTrue(TrainingRatingCalculator.calculateRepeatedGuessPenalty(3) < 0)
    }

    @Test
    fun computePatternMastery_rewardsRecentCleanSolvesAndWeightsDifficulty() {
        val dayMs = 86_400_000L
        val exercises = (1L..9L).associateWith { id ->
            makeExercise(id = id, difficulty = 50, pattern = TacticalPattern.FORK_KNIGHT)
        }
        // 3 старых провала месяц назад + 6 свежих чистых решений с 1-й попытки
        val attempts = buildList {
            for (i in 1..3) {
                add(
                    ExerciseAttempt(
                        userId = 1L,
                        exerciseId = i.toLong(),
                        solved = false,
                        timeSpentSeconds = 20,
                        movesMade = listOf("a2a3"),
                        attemptedAt = 1_000_000L + i * dayMs
                    )
                )
            }
            for (i in 4..9) {
                add(
                    ExerciseAttempt(
                        userId = 1L,
                        exerciseId = i.toLong(),
                        solved = true,
                        timeSpentSeconds = 7,
                        movesMade = listOf("d1d8"),
                        attemptedAt = 1_000_000L + (30 + i) * dayMs
                    )
                )
            }
        }

        val pm = TrainingRatingCalculator.computePatternMastery(exercises, attempts, playerElo = 1600).single()
        assertEquals(67, pm.accuracyPercent)
        assertTrue(
            "Recency-weighted mastery (${pm.masteryPercent}%) should exceed raw lifetime average (${pm.accuracyPercent}%)",
            pm.masteryPercent > pm.accuracyPercent
        )
        assertEquals(MasteryLevel.STRONG, pm.level)
    }

    @Test
    fun isImpulsiveMistake_detectsRushedFirstMoveBlundersOnly() {
        val medium = makeExercise(difficulty = 45)
        assertTrue(TrainingRatingCalculator.isImpulsiveMistake(1_800L, medium, isFirstMoveOfPuzzle = true))
        assertFalse(TrainingRatingCalculator.isImpulsiveMistake(9_500L, medium, isFirstMoveOfPuzzle = true))
        assertFalse(TrainingRatingCalculator.isImpulsiveMistake(1_500L, medium, isFirstMoveOfPuzzle = false))
        assertFalse(TrainingRatingCalculator.isImpulsiveMistake(null, medium, isFirstMoveOfPuzzle = true))
    }

    @Test
    fun interleavedPatternQueue_delaysMotifReturnAcrossOtherPatterns() {
        val queue = InterleavedPatternQueue()
        queue.onExerciseServed("FORK_KNIGHT")
        queue.scheduleAfterMistake("FORK_KNIGHT")

        // Сразу на следующем шаге НЕ должен выдать ту же тему (никакого предсказуемого блока подряд)
        assertNull(queue.pollDuePattern())

        // Задача 1 на другую тему (связка)
        queue.onExerciseServed("PIN_ABSOLUTE")
        assertNull(queue.pollDuePattern())

        // Задача 2 на другую тему (мат по 8-й горизонтали) -> теперь подошёл 1-й отложенный повтор!
        queue.onExerciseServed("BACK_RANK_MATE")
        assertEquals("FORK_KNIGHT", queue.pollDuePattern())
        queue.onExerciseServed("FORK_KNIGHT")

        // Сразу после повтора снова тишина до 2-го контрольного слота (ещё 3 других задачи)
        assertNull(queue.pollDuePattern())
        queue.onExerciseServed("DEFLECTION")
        assertNull(queue.pollDuePattern())
        queue.onExerciseServed("SKEWER")
        assertNull(queue.pollDuePattern())
        queue.onExerciseServed("DEFENSIVE_MOVE")
        assertEquals("FORK_KNIGHT", queue.pollDuePattern())
    }

    @Test
    fun trainingMode_routeKeyRoundTripsAllModes() {
        val modes = listOf(
            TrainingMode.All,
            TrainingMode.Daily,
            TrainingMode.MyMistakes,
            TrainingMode.WeakPatterns,
            TrainingMode.Defense
        ) + TrainingMode.selectableThemes.map { TrainingMode.ByTheme(it) }

        for (mode in modes) {
            assertEquals(mode, TrainingMode.fromRouteKey(mode.routeKey))
        }
        assertEquals(TrainingMode.All, TrainingMode.fromRouteKey(null))
        assertEquals(TrainingMode.All, TrainingMode.fromRouteKey("unknown_key"))
    }

    @Test
    fun isMistakeSuitableForExercise_filtersHopelessAndTinyInaccuracies() {
        val sampleFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"

        // 1. Безнадёжно проигранная позиция до хода белых (-700 сП) -> отклоняется
        val hopelessMistake = Mistake(
            gameId = 1L, themeId = 1L, moveNumber = 25, color = ChessColor.WHITE,
            evaluationLoss = 300, mistakeType = MistakeType.BLUNDER,
            bestMove = "Qd2", userMove = "Qa5", fenBefore = sampleFen
        )
        val hopelessAnalyzed = AnalyzedMove(
            gameId = 1L, moveIndex = 48, moveNumber = 25, color = ChessColor.WHITE,
            quality = MoveQuality.BLUNDER, san = "Qa5", bestMove = "Qd2",
            evalBefore = -700, evalAfter = -1000, evalLoss = 300
        )
        assertFalse(GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(hopelessMistake, hopelessAnalyzed))

        // 2. Для чёрных evalBefore = +700 означает -700 со стороны чёрных -> тоже отклоняется
        val blackHopeless = hopelessMistake.copy(color = ChessColor.BLACK)
        val blackHopelessAm = hopelessAnalyzed.copy(color = ChessColor.BLACK, evalBefore = 700, evalAfter = 1000)
        assertFalse(GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(blackHopeless, blackHopelessAm))

        // 3. Форсированный мат против игрока до хода -> отклоняется
        val gettingMatedAm = hopelessAnalyzed.copy(evalBefore = -(ChessEngine.MATE_VALUE - 200))
        assertFalse(GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(hopelessMistake, gettingMatedAm))

        // 4. Мелкая неточность (55 сП) -> отклоняется
        val tinyInaccuracy = hopelessMistake.copy(
            evaluationLoss = 55, mistakeType = MistakeType.INACCURACY
        )
        assertFalse(GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(tinyInaccuracy, null))

        // 5. Настоящий тактический зевок в равной позиции -> принимается!
        val goodBlunder = hopelessMistake.copy(evaluationLoss = 350, mistakeType = MistakeType.BLUNDER)
        val equalPosAm = hopelessAnalyzed.copy(evalBefore = 40, evalAfter = -310, evalLoss = 350)
        assertTrue(GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(goodBlunder, equalPosAm))
    }
}
