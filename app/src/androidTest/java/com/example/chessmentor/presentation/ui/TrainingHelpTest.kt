package com.example.chessmentor.presentation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.presentation.ui.components.screen.TrainingHelpControls
import com.example.chessmentor.presentation.viewmodel.HintLevel
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TrainingHelpTest {
    @get:Rule val ui = createComposeRule()
    private val state = mutableStateOf(TrainingUiState())
    private val events = mutableListOf<String>()

    private fun show() = ui.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            TrainingHelpControls(state.value, !state.value.isInputLocked,
                { events.add("hint") }, { events.add("better") }, { events.add("line") }, { events.add("skip") })
        }
    }
    private fun open() = ui.onNodeWithText("Помощь").performClick()

    @Test fun showsCostBeforeEitherHintAndDismissDoesNotApplyIt() {
        show(); open()
        ui.onNodeWithText("Прибавка за решение будет меньше").assertIsDisplayed()
        ui.onNodeWithText("К доске").performClick()
        ui.runOnIdle { assertEquals(emptyList<String>(), events) }
        open(); ui.onNodeWithText("Подсказать фигуру").performClick()
        ui.runOnIdle { state.value = state.value.copy(hintLevel = HintLevel.PIECE_HIGHLIGHT) }
        open(); ui.onNodeWithText("Без прибавки к рейтингу").assertIsDisplayed()
        ui.onNodeWithText("Показать ход").performClick()
        ui.runOnIdle { assertEquals(listOf("hint", "hint"), events) }
    }

    @Test fun mistakeHintAndLineInvokeDistinctActionsAndShownMoveIsNotOfferedAgain() {
        state.value = state.value.copy(showBetterMoveHint = true)
        show(); open()
        ui.onNodeWithText("Без прибавки к рейтингу").assertIsDisplayed()
        ui.onNodeWithText("Показать ход").performClick()
        ui.runOnIdle { state.value = state.value.copy(betterMoveShown = true) }
        open(); ui.onNodeWithText("Показать ход").assertDoesNotExist()
        ui.onNodeWithText("Подсказать фигуру").assertDoesNotExist()
        ui.onNodeWithText("Показать всю линию").performClick()
        ui.runOnIdle { assertEquals(listOf("better", "line"), events) }
    }

    @Test fun dailyReplayDoesNotPromiseReducedGainAndSkipNamesDestination() {
        state.value = state.value.copy(mode = TrainingMode.Daily, isDailyAlreadySolved = true)
        show(); open()
        ui.onNodeWithText("Повтор без изменения рейтинга").assertIsDisplayed()
        ui.onNodeWithText("К доске").performClick()
        ui.onNodeWithText("К другим задачам").performClick()
        ui.runOnIdle { assertEquals(listOf("skip"), events) }
    }
}
