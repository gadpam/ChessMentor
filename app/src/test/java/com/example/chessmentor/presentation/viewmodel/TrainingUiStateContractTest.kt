package com.example.chessmentor.presentation.viewmodel

import com.example.chessmentor.domain.training.TrainingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingUiStateContractTest {
    @Test fun defaultTrainingUiStateMatchesTestContracts() {
        val state = TrainingUiState()
        assertFalse(state.isLoading)
        assertFalse(state.isInitializationError)
        assertFalse(state.hasActiveExercise)
        assertFalse(state.isPaused)
        assertNull(state.sessionSummary)
        assertEquals(TrainingMode.All, state.mode)
    }

    @Test fun pauseClockExcludesIdleDurationWithoutResettingActiveElapsedTime() {
        val startMs = 1_000_000L
        val pausedAtMs = 1_012_000L // 12 сек расчёта до паузы
        val resumedAtMs = 1_192_000L // 180 сек пользователь отсутствовал
        val finishedAtMs = 1_200_000L // ещё 8 сек расчёта после возврата

        // Пока активна пауза, счётчик заморожен на 12 секундах
        assertEquals(12, TrainingPauseClock.elapsedSeconds(startMs, pausedAtMs, resumedAtMs))

        val adjustedStart = TrainingPauseClock.adjustStartAfterResume(startMs, pausedAtMs, resumedAtMs)
        assertEquals(20, TrainingPauseClock.elapsedSeconds(adjustedStart, null, finishedAtMs))
    }

    @Test fun entryRouteKeysRoundTripToExpectedModes() {
        assertEquals(TrainingMode.Daily, TrainingMode.fromRouteKey(TrainingMode.Daily.routeKey))
        assertEquals(TrainingMode.SmartSession, TrainingMode.fromRouteKey(TrainingMode.SmartSession.routeKey))
        assertEquals(TrainingMode.All, TrainingMode.fromRouteKey(TrainingMode.All.routeKey))
        assertEquals(TrainingMode.MyMistakes, TrainingMode.fromRouteKey(TrainingMode.MyMistakes.routeKey))
    }
}
