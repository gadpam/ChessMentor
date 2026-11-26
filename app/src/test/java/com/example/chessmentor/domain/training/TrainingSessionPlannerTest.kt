package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingSessionPlannerTest {

    @Test
    fun phaseForStep_structuresTenPuzzleDeliberatePracticeArc() {
        val phases = (1..10).map { TrainingSessionPlanner.phaseForStep(it, 10) }

        assertEquals(SessionPhase.WARMUP, phases[0])
        assertEquals(SessionPhase.WARMUP, phases[1])
        assertEquals(SessionPhase.REVIEW_AND_MISTAKES, phases[2])
        assertEquals(SessionPhase.REVIEW_AND_MISTAKES, phases[3])
        assertEquals(SessionPhase.REVIEW_AND_MISTAKES, phases[4])
        assertEquals(SessionPhase.CORE_INTERLEAVED, phases[5])
        assertEquals(SessionPhase.DEFENSE_CHECK, phases[6])
        assertEquals(SessionPhase.CORE_INTERLEAVED, phases[7])
        assertEquals(SessionPhase.DEEP_CALCULATION, phases[8])
        assertEquals(SessionPhase.DEEP_CALCULATION, phases[9])

        // До решения 7-й задачи не должно быть спойлера, что она именно на защиту
        assertFalse(SessionPhase.DEFENSE_CHECK.preSolveLabel.contains("защит", ignoreCase = true))
    }

    @Test
    fun formatCognitiveModeFeedback_providesCalmPostSolveFeedbackOnlyOnCleanSolves() {
        val shortExercise = Exercise(
            id = 1L,
            fenPosition = "6k1/r4ppp/8/8/8/8/P4PPP/3R2K1 w - - 0 1",
            solutionMoves = listOf("d1d8"),
            tacticalPattern = TacticalPattern.BACK_RANK_MATE,
            patternClusterId = "BACK_RANK_EASY",
            difficultyRating = 24,
            theme = TacticalTheme.MATE
        )
        val deepExercise = shortExercise.copy(
            id = 2L,
            solutionMoves = listOf("c1c8", "b7c8", "d5e7", "g8h8", "e7c8"),
            difficultyRating = 68
        )

        val fastVision = TrainingSessionPlanner.formatCognitiveModeFeedback(
            mode = TrainingMode.PatternVision,
            exercise = shortExercise,
            solveTimeSeconds = 5,
            isCleanFirstTry = true
        )
        assertNotNull(fastVision)

        val failedVision = TrainingSessionPlanner.formatCognitiveModeFeedback(
            mode = TrainingMode.PatternVision,
            exercise = shortExercise,
            solveTimeSeconds = 5,
            isCleanFirstTry = false
        )
        assertNull(failedVision)

        val deepFeedback = TrainingSessionPlanner.formatCognitiveModeFeedback(
            mode = TrainingMode.DeepCalculation,
            exercise = deepExercise,
            solveTimeSeconds = 42,
            isCleanFirstTry = true
        )
        assertNotNull(deepFeedback)
        assertTrue(deepFeedback!!.contains("3"))
    }

    @Test
    fun buildSessionSummary_flagsImpulsivePlayAndMissedMotifs() {
        val rushed = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 6,
            solvedTotalCount = 8,
            failedOrRevealedCount = 4,
            impulsiveMistakeCount = 3,
            netRatingDelta = -8,
            totalTimeSeconds = 90,
            missedPatternNames = listOf("Вилка конём", "Абсолютная связка", "Вилка конём")
        )
        assertEquals(60, rushed.firstTryAccuracyPercent)
        assertEquals(9, rushed.averageTimeSeconds)
        assertEquals(listOf("Вилка конём", "Абсолютная связка"), rushed.missedPatternNames)
        assertTrue(rushed.coachTakeaway.contains("поспеш", ignoreCase = true))

        val sharp = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 9,
            solvedTotalCount = 10,
            failedOrRevealedCount = 1,
            impulsiveMistakeCount = 0,
            netRatingDelta = 28,
            totalTimeSeconds = 140,
            missedPatternNames = emptyList()
        )
        assertEquals(90, sharp.firstTryAccuracyPercent)
        assertEquals(14, sharp.averageTimeSeconds)
    }

    @Test
    fun buildSessionSummary_separatesSkippedPuzzlesFromMistakes() {
        val partialSkip = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 6,
            solvedTotalCount = 6,
            failedOrRevealedCount = 0,
            impulsiveMistakeCount = 0,
            netRatingDelta = 30,
            totalTimeSeconds = 72,
            missedPatternNames = emptyList(),
            skippedCount = 4
        )
        assertEquals(4, partialSkip.skippedCount)
        assertEquals(100, partialSkip.firstTryAccuracyPercent)
        assertEquals(12, partialSkip.averageTimeSeconds)
        assertTrue(partialSkip.missedPatternNames.isEmpty())
        assertTrue(partialSkip.coachTakeaway.contains("Пропущенные", ignoreCase = true))

        val allSkipped = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 0,
            solvedTotalCount = 0,
            failedOrRevealedCount = 0,
            impulsiveMistakeCount = 0,
            netRatingDelta = 0,
            totalTimeSeconds = 0,
            missedPatternNames = emptyList(),
            skippedCount = 10
        )
        assertEquals(10, allSkipped.skippedCount)
        assertEquals(0, allSkipped.firstTryAccuracyPercent)
        assertEquals("Все позиции пропущены", allSkipped.headline)
    }

    @Test
    fun buildSessionSummary_capsHeadlineWhenMajoritySkippedAndChecksPhaseOffsets() {
        val majoritySkipped = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 4,
            solvedTotalCount = 4,
            failedOrRevealedCount = 0,
            impulsiveMistakeCount = 0,
            netRatingDelta = 20,
            totalTimeSeconds = 40,
            missedPatternNames = emptyList(),
            skippedCount = 6
        )
        assertEquals(100, majoritySkipped.firstTryAccuracyPercent)
        assertEquals("Частичная сессия (4 из 10)", majoritySkipped.headline)

        val earlyAborted = TrainingSessionPlanner.buildSessionSummary(
            totalExercises = 10,
            cleanFirstTryCount = 2,
            solvedTotalCount = 2,
            failedOrRevealedCount = 0,
            impulsiveMistakeCount = 0,
            netRatingDelta = 10,
            totalTimeSeconds = 12,
            missedPatternNames = emptyList(),
            skippedCount = 8
        )
        assertEquals("Сессия завершена досрочно", earlyAborted.headline)
        assertFalse(earlyAborted.coachTakeaway.contains("режим «Расчёт»"))

        assertEquals(-10, SessionPhase.WARMUP.difficultyOffset)
        assertEquals(12, SessionPhase.DEEP_CALCULATION.difficultyOffset)
    }
}
