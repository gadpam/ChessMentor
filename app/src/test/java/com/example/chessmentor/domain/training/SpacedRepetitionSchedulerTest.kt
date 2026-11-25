package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionSchedulerTest {

    private val sampleExercise = Exercise(
        id = 42L,
        fenPosition = "6k1/r4ppp/8/8/8/8/P4PPP/3R2K1 w - - 0 1",
        solutionMoves = listOf("d1d8"),
        tacticalPattern = TacticalPattern.BACK_RANK_MATE,
        patternClusterId = "BACK_RANK_MATE_EASY",
        difficultyRating = 30,
        theme = TacticalTheme.MATE
    )

    @Test
    fun attemptMetadata_roundTripsAllPedagogicalTagsCleanly() {
        val tagged = AttemptMetadata.buildTaggedMoves(
            rawUciMoves = listOf("d5c7", "e8d7", "c7a8"),
            hintUsage = HintUsage.PIECE_HIGHLIGHT,
            retryCount = 2,
            firstMoveThinkMs = 2450L,
            wasImpulsive = true
        )
        val attempt = ExerciseAttempt(
            userId = 1L,
            exerciseId = 42L,
            solved = true,
            timeSpentSeconds = 18,
            movesMade = tagged
        )

        assertEquals(listOf("d5c7", "e8d7", "c7a8"), AttemptMetadata.extractActualMoves(attempt))
        assertEquals(HintUsage.PIECE_HIGHLIGHT, AttemptMetadata.extractHintUsage(attempt))
        assertEquals(2, AttemptMetadata.extractRetryCount(attempt))
        assertEquals(2450L, AttemptMetadata.extractFirstMoveThinkMs(attempt))
        assertTrue(AttemptMetadata.isImpulsive(attempt))
        assertFalse(AttemptMetadata.isSolutionRevealed(attempt))
    }

    @Test
    fun retryAfterMistake_isGroupedIntoOneSittingAndReturnsInFourHours_notLearnedForever() {
        val t0 = 1_000_000L
        val attempts = listOf(
            ExerciseAttempt(
                id = 1L, userId = 1L, exerciseId = 42L, solved = false,
                timeSpentSeconds = 6, movesMade = listOf("d1d2"), attemptedAt = t0
            ),
            ExerciseAttempt(
                id = 2L, userId = 1L, exerciseId = 42L, solved = true,
                timeSpentSeconds = 14,
                movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d2", "d1d8"), retryCount = 1),
                attemptedAt = t0 + 8_000L
            )
        )

        val card = SpacedRepetitionScheduler.computeCardState(42L, attempts, sampleExercise)
        assertNotNull(card)
        assertEquals(1, card!!.totalSittings)
        assertEquals(AttemptQuality.RETRY_SOLVE, card.lastQuality)
        assertEquals(1, card.box)
        assertEquals(0, card.cleanFirstTrySolves)
        assertEquals(1, card.lapses)
        assertEquals(SpacedRepetitionScheduler.RETRY_CONSOLIDATION_MS, card.intervalMs)
        assertTrue(card.needsConsolidation)
        assertFalse(card.isMastered)

        // Через 30 минут показывать рано, а через 4 часа — обязательно вернуть на пересдачу!
        assertFalse(card.isDue(t0 + 30 * SpacedRepetitionScheduler.MINUTE_MS))
        assertTrue(card.isDue(t0 + 8_000L + SpacedRepetitionScheduler.RETRY_CONSOLIDATION_MS))
    }

    @Test
    fun failedAndRevealedAttempts_enforceTenMinuteCooldownBeforeReturn() {
        val t0 = 5_000_000L
        val revealed = ExerciseAttempt(
            id = 1L, userId = 1L, exerciseId = 42L, solved = false,
            timeSpentSeconds = 20,
            movesMade = AttemptMetadata.buildTaggedMoves(emptyList(), solutionRevealed = true),
            attemptedAt = t0
        )

        val card = SpacedRepetitionScheduler.computeCardState(42L, listOf(revealed), sampleExercise)!!
        assertEquals(0, card.box)
        assertEquals(AttemptQuality.FAILED, card.lastQuality)
        assertEquals(SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS, card.intervalMs)

        // Через 1 минуту ещё в охлаждении (кратковременная память)
        assertFalse(card.isDue(t0 + SpacedRepetitionScheduler.MINUTE_MS))
        // Через 10 минут готова к повтору
        assertTrue(card.isDue(t0 + SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS))
    }

    @Test
    fun hintSolve_andSlowCleanSolve_scheduleShortReviewsForTrueFluency() {
        val t0 = 10_000_000L
        val hintAttempt = ExerciseAttempt(
            id = 1L, userId = 1L, exerciseId = 42L, solved = true,
            timeSpentSeconds = 12,
            movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d8"), hintUsage = HintUsage.PIECE_HIGHLIGHT),
            attemptedAt = t0
        )
        val hintCard = SpacedRepetitionScheduler.computeCardState(42L, listOf(hintAttempt), sampleExercise)!!
        assertEquals(AttemptQuality.HINT_SOLVE, hintCard.lastQuality)
        assertEquals(1, hintCard.box)
        assertEquals(SpacedRepetitionScheduler.BOX_1_INTERVAL_MS, hintCard.intervalMs)
        assertTrue(hintCard.needsConsolidation)

        // Чистое, но слишком медленное решение (120 с при пороге ~40 с) -> коробка 1 (1 день), а не 3 дня
        val slowAttempt = ExerciseAttempt(
            id = 2L, userId = 1L, exerciseId = 42L, solved = true,
            timeSpentSeconds = 120,
            movesMade = listOf("d1d8"),
            attemptedAt = t0
        )
        val slowCard = SpacedRepetitionScheduler.computeCardState(42L, listOf(slowAttempt), sampleExercise)!!
        assertEquals(AttemptQuality.SLOW_CLEAN_SOLVE, slowCard.lastQuality)
        assertEquals(1, slowCard.box)
        assertEquals(SpacedRepetitionScheduler.BOX_1_INTERVAL_MS, slowCard.intervalMs)
    }

    @Test
    fun cleanSolvesAfterFailure_escalateAlongForgettingCurveToMastery_andResetOnLapse() {
        val history = mutableListOf<ExerciseAttempt>()
        var now = 1_000_000L
        var nextId = 1L

        // 1. Провал -> коробка 0 (10 мин)
        history += ExerciseAttempt(nextId++, 1L, 42L, false, 15, listOf("d1d2"), now)
        var card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(0, card.box)
        assertEquals(SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS, card.intervalMs)

        // 2. Чистое решение через 15 мин -> коробка 1 (1 день)
        now = card.nextReviewAt + 5 * SpacedRepetitionScheduler.MINUTE_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, true, 10, listOf("d1d8"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(1, card.box)
        assertEquals(SpacedRepetitionScheduler.BOX_1_INTERVAL_MS, card.intervalMs)
        assertFalse(card.isMastered)

        // 3. Чистое решение через 1 день -> коробка 2 (3 дня)
        now = card.nextReviewAt + SpacedRepetitionScheduler.HOUR_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, true, 9, listOf("d1d8"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(2, card.box)
        assertEquals(SpacedRepetitionScheduler.BOX_2_INTERVAL_MS, card.intervalMs)

        // 4. Чистое решение через 3 дня -> коробка 3 (7 дней)
        now = card.nextReviewAt + SpacedRepetitionScheduler.HOUR_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, true, 8, listOf("d1d8"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(3, card.box)
        assertEquals(SpacedRepetitionScheduler.BOX_3_INTERVAL_MS, card.intervalMs)
        assertFalse(card.isMastered)

        // 5. Чистое решение через 7 дней -> коробка 4 (~17-21 день, освоено!)
        now = card.nextReviewAt + SpacedRepetitionScheduler.HOUR_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, true, 7, listOf("d1d8"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(4, card.box)
        assertTrue(card.intervalMs >= 14 * SpacedRepetitionScheduler.DAY_MS)
        assertTrue(card.isMastered)

        // 6. Чистое решение через 3 недели -> коробка 5 (>= 30 дней, освоено!)
        now = card.nextReviewAt + SpacedRepetitionScheduler.HOUR_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, true, 6, listOf("d1d8"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(5, card.box)
        assertTrue(card.intervalMs >= 30 * SpacedRepetitionScheduler.DAY_MS)
        assertTrue(card.isMastered)

        // 7. Срыв спустя два месяца -> мгновенный сброс в коробку 0 (10 мин)
        now = card.nextReviewAt + SpacedRepetitionScheduler.DAY_MS
        history += ExerciseAttempt(nextId++, 1L, 42L, false, 20, listOf("g1h1"), now)
        card = SpacedRepetitionScheduler.computeCardState(42L, history, sampleExercise)!!
        assertEquals(0, card.box)
        assertEquals(2, card.lapses)
        assertFalse(card.isMastered)
        assertEquals(SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS, card.intervalMs)
    }

    @Test
    fun attemptMetadata_isIndependentSolve_excludesRevealedAndFullHintEvenIfSolvedTrue() {
        val clean = ExerciseAttempt(1L, 1L, 42L, solved = true, timeSpentSeconds = 8, movesMade = listOf("d1d8"))
        val pieceHint = ExerciseAttempt(
            2L, 1L, 42L, solved = true, timeSpentSeconds = 12,
            movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d8"), hintUsage = HintUsage.PIECE_HIGHLIGHT)
        )
        val fullHint = ExerciseAttempt(
            3L, 1L, 42L, solved = true, timeSpentSeconds = 4,
            movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d8"), hintUsage = HintUsage.FULL_SOLUTION)
        )
        val revealed = ExerciseAttempt(
            4L, 1L, 42L, solved = true, timeSpentSeconds = 2,
            movesMade = AttemptMetadata.buildTaggedMoves(listOf("d1d8"), solutionRevealed = true)
        )

        assertTrue(AttemptMetadata.isIndependentSolve(clean))
        assertTrue(AttemptMetadata.isIndependentSolve(pieceHint))
        assertFalse(AttemptMetadata.isIndependentSolve(fullHint))
        assertFalse(AttemptMetadata.isIndependentSolve(revealed))
    }

    @Test
    fun slowSolveOverTenMinutes_isClassifiedAsSlowCleanSolve_notFast() {
        val attempt = ExerciseAttempt(
            id = 1L, userId = 1L, exerciseId = 42L, solved = true,
            timeSpentSeconds = 900, movesMade = listOf("d1d8"), attemptedAt = 1_000_000L
        )
        val card = SpacedRepetitionScheduler.computeCardState(42L, listOf(attempt), sampleExercise)!!
        assertEquals(AttemptQuality.SLOW_CLEAN_SOLVE, card.lastQuality)
        assertEquals(1, card.box)
    }

    @Test
    fun earlyReviewBeforeScheduledTime_doesNotAdvanceBoxOrMastery() {
        val t0 = 1_000_000L
        val first = ExerciseAttempt(1L, 1L, 42L, solved = true, timeSpentSeconds = 6, movesMade = listOf("d1d8"), attemptedAt = t0)
        // Повтор через 2 часа (раньше 3-дневного интервала!) не должен поднимать коробку до 3 и затем 4 (isMastered)
        val early1 = ExerciseAttempt(
            2L, 1L, 42L, solved = true, timeSpentSeconds = 5, movesMade = listOf("d1d8"),
            attemptedAt = t0 + 2L * SpacedRepetitionScheduler.HOUR_MS
        )
        val early2 = ExerciseAttempt(
            3L, 1L, 42L, solved = true, timeSpentSeconds = 5, movesMade = listOf("d1d8"),
            attemptedAt = t0 + 4L * SpacedRepetitionScheduler.HOUR_MS
        )

        val card = SpacedRepetitionScheduler.computeCardState(42L, listOf(first, early1, early2), sampleExercise)!!
        assertEquals(2, card.box)
        assertFalse(card.isMastered)
    }

    @Test
    fun leechAfterThreeLapses_receivesExtendedCooldownAndGuidance() {
        var now = 1_000_000L
        val attempts = mutableListOf<ExerciseAttempt>()
        for (i in 1..3) {
            attempts += ExerciseAttempt(
                id = i.toLong(),
                userId = 1L,
                exerciseId = 42L,
                solved = false,
                timeSpentSeconds = 15,
                movesMade = listOf("a2a3"),
                attemptedAt = now
            )
            now += 2L * SpacedRepetitionScheduler.HOUR_MS
        }

        val card = SpacedRepetitionScheduler.computeCardState(42L, attempts, sampleExercise)!!
        assertEquals(3, card.lapses)
        assertTrue(card.isLeech)
        assertEquals(SpacedRepetitionScheduler.LEECH_COOLDOWN_MS, card.intervalMs)
        assertFalse(card.isDue(attempts.last().attemptedAt + SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS))
        assertTrue(SpacedRepetitionScheduler.formatReviewBanner(card).contains("Сложный для вас мотив"))
        assertTrue(SpacedRepetitionScheduler.formatNextIntervalNote(card).contains("Трудный мотив"))
    }
}
