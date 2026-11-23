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
        assertNull(state.sessionSummary)
        assertEquals(TrainingMode.All, state.mode)
    }

    @Test fun entryRouteKeysRoundTripToExpectedModes() {
        assertEquals(TrainingMode.Daily, TrainingMode.fromRouteKey(TrainingMode.Daily.routeKey))
        assertEquals(TrainingMode.SmartSession, TrainingMode.fromRouteKey(TrainingMode.SmartSession.routeKey))
        assertEquals(TrainingMode.All, TrainingMode.fromRouteKey(TrainingMode.All.routeKey))
        assertEquals(TrainingMode.MyMistakes, TrainingMode.fromRouteKey(TrainingMode.MyMistakes.routeKey))
    }
}
