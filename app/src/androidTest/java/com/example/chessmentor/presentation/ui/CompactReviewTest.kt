package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.presentation.ui.components.navigation.ChessMentorBottomBar
import com.example.chessmentor.presentation.ui.components.screen.RetryMistakePanel
import com.example.chessmentor.presentation.viewmodel.RetryMistakeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompactReviewTest {
    @get:Rule val ui = createComposeRule()

    @Test fun narrowLargeTypeBarKeepsAllFourDestinationsNamed() {
        ui.setContent {
            var route by remember { mutableStateOf("upload") }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(280.dp)) { ChessMentorBottomBar(route) { route = it } }
            }
        }
        ui.onNodeWithText("Партии").assertIsSelected()
        for (label in listOf("Статистика", "Главная", "Задачи")) {
            ui.onNodeWithContentDescription(label).performClick()
            ui.onNodeWithText(label).assertIsSelected()
        }
        ui.onNodeWithText("Загрузить").assertDoesNotExist()
    }

    @Test fun hintRevealAndContinuationAreDistinctStates() {
        var state by mutableStateOf(RetryMistakeUiState(0, 1, ChessColor.WHITE, true, "", "a4", "e2e4", "e4", feedback = "Найдите ход"))
        var next = 0
        ui.setContent {
            RetryMistakePanel(state, onHint = { state = state.copy(hintLevel = state.hintLevel + 1) },
                onShowLine = {}, onPracticeInTrainer = {}, onContinueGame = { next++ }, onCancel = {})
        }
        ui.onNodeWithText("Показать ход").assertDoesNotExist()
        ui.onNodeWithText("Подсказка").performClick()
        ui.onNodeWithText("Показать ход").performClick()
        ui.onNodeWithText("Показать ход").assertDoesNotExist()
        ui.onNodeWithText("К партии").assertIsDisplayed()
        ui.runOnIdle { state = state.copy(isSolved = true) }
        ui.onNodeWithText("Посмотреть вариант").assertDoesNotExist()
        ui.onNodeWithContentDescription("Действия разбора").performClick()
        ui.onNodeWithText("Посмотреть вариант").performClick()
        ui.onNodeWithText("Дальше").performClick()
        ui.runOnIdle { assertEquals(1, next) }
    }
}
