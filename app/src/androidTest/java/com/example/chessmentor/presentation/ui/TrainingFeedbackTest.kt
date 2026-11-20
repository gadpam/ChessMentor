package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.chessmentor.presentation.ui.components.screen.UnifiedFeedbackPanel
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import org.junit.Rule
import org.junit.Test

class TrainingFeedbackTest {
    @get:Rule val ui = createComposeRule()

    @Test fun retryActionIsVisibleBeforeOpeningExplanation() {
        val reason = "После размена теряется ладья."
        ui.setContent { UnifiedFeedbackPanel(TrainingUiState(feedbackMessage = "Попробуйте ещё раз\n$reason")) }
        ui.onNodeWithText(reason, substring = true).assertDoesNotExist()
        ui.onNodeWithText("Попробуйте ещё раз").assertIsDisplayed().performClick()
        ui.onNodeWithText(reason, substring = true).assertIsDisplayed()
        ui.onNodeWithText("К доске").performClick()
        ui.onNodeWithText("Попробуйте ещё раз").assertIsDisplayed()
    }

    @Test fun revealedSolutionKeepsLineAndScheduleInDetailsOnly() {
        val interval = "Вернёмся к задаче через 10 мин"
        ui.setContent { UnifiedFeedbackPanel(TrainingUiState(isSolutionRevealed = true,
            feedbackMessage = "Решение показано", solutionSanLine = "Nf3", nextReviewNote = interval)) }
        ui.onNodeWithText(interval, substring = true).assertDoesNotExist()
        ui.onNodeWithText("Решение показано").performClick()
        ui.onNodeWithText("Решение показано\n\nNf3\n\n$interval").assertIsDisplayed()
    }
}
