package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.training.SessionSummary
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.presentation.ui.components.screen.BottomControlPanel
import com.example.chessmentor.presentation.ui.components.screen.SessionSummaryCard
import com.example.chessmentor.presentation.ui.components.screen.SmartSessionProgressBar
import com.example.chessmentor.presentation.ui.components.screen.SolutionResultInlineCard
import com.example.chessmentor.presentation.ui.components.screen.UnifiedFeedbackPanel
import com.example.chessmentor.presentation.viewmodel.SessionStats
import com.example.chessmentor.presentation.viewmodel.SmartSessionStepStatus
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import org.junit.Assert.assertTrue
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

    @Test fun solutionResultInlineCardDisplaysExplanationLineAndScheduleWithoutDialog() {
        ui.setContent {
            SolutionResultInlineCard(
                TrainingUiState(
                    isSolved = true,
                    patternDisplayName = "Вилка",
                    postSolveExplanation = "Конь нападает одновременно на короля и ладью.",
                    solutionSanLine = "1. Nc7+ Kd8 2. Nxa8",
                    nextReviewNote = "Повтор через 1 день"
                )
            )
        }
        ui.onNodeWithText("Мотив: Вилка").assertIsDisplayed()
        ui.onNodeWithText("Повтор через 1 день").assertIsDisplayed()
        ui.onNodeWithText("Конь нападает одновременно на короля и ладью.").assertIsDisplayed()
        ui.onNodeWithText("Решение: 1. Nc7+ Kd8 2. Nxa8").assertIsDisplayed()
    }

    @Test fun solutionResultInlineCardShowsSaveWarningWhenPersistenceFails() {
        val warning = "Не удалось сохранить результат в локальную базу — проверьте свободную память."
        ui.setContent {
            SolutionResultInlineCard(
                TrainingUiState(
                    isSolved = true,
                    saveWarningMessage = warning
                )
            )
        }
        ui.onNodeWithText(warning).assertIsDisplayed()
    }

    @Test fun pausedTrainingPanelShowsResumeAndHidesHints() {
        var resumed = false
        ui.setContent {
            BottomControlPanel(
                uiState = TrainingUiState(
                    hasActiveExercise = true,
                    isInputLocked = true,
                    isPaused = true
                ),
                bottomInset = 0.dp,
                startInset = 0.dp,
                endInset = 0.dp,
                onRetryCheck = {},
                onReturnFromCheck = {},
                onNextExercise = {},
                onHint = {},
                onShowBetterMove = {},
                onRevealSolution = {},
                onSkip = {},
                onReviewFirst = {},
                onReviewPrev = {},
                onReviewNext = {},
                onReviewLast = {},
                onToggleFreeAnalysis = {},
                onUndoAnalysis = {},
                onPlayEngineBest = {},
                onResumeTraining = { resumed = true }
            )
        }
        ui.onNodeWithText("Помощь").assertDoesNotExist()
        ui.onNodeWithText("Пропустить").assertDoesNotExist()
        ui.onNodeWithText("Продолжить задачу").assertIsDisplayed().performClick()
        assertTrue(resumed)
    }

    @Test fun smartSessionProgressBar_displaysSessionScopedCountsNotGlobalCleanCount() {
        ui.setContent {
            SmartSessionProgressBar(
                TrainingUiState(
                    mode = TrainingMode.SmartSession,
                    sessionStepNumber = 5,
                    sessionTotalSteps = 10,
                    sessionCleanCount = 2,
                    sessionFailedCount = 1,
                    sessionSkippedCount = 1,
                    sessionStepStatuses = listOf(
                        SmartSessionStepStatus.CLEAN,
                        SmartSessionStepStatus.CLEAN,
                        SmartSessionStepStatus.FAILED,
                        SmartSessionStepStatus.SKIPPED,
                        SmartSessionStepStatus.CURRENT,
                        SmartSessionStepStatus.PENDING,
                        SmartSessionStepStatus.PENDING,
                        SmartSessionStepStatus.PENDING,
                        SmartSessionStepStatus.PENDING,
                        SmartSessionStepStatus.PENDING
                    ),
                    sessionStats = SessionStats(cleanFirstTrySolved = 99)
                )
            )
        }
        ui.onNodeWithText("Задача 5 из 10", substring = true).assertIsDisplayed()
        ui.onNodeWithText("Чисто: 2 · Ошибок: 1 · Проп.: 1").assertIsDisplayed()
        ui.onNodeWithText("Чисто: 99", substring = true).assertDoesNotExist()
    }

    @Test fun sessionSummaryCard_reportsCleanFailedAndSkippedDistinctlyWithoutCallingFailuresSolved() {
        ui.setContent {
            SessionSummaryCard(
                summary = SessionSummary(
                    totalExercises = 10,
                    cleanFirstTryCount = 2,
                    solvedCount = 2,
                    failedCount = 5,
                    skippedCount = 3,
                    impulsiveMistakeCount = 1,
                    firstTryAccuracyPercent = 29,
                    averageTimeSeconds = 18,
                    netRatingDelta = -10,
                    missedPatternNames = listOf("Вилка конём"),
                    headline = "Сложная сессия — точки роста найдены",
                    coachTakeaway = "Пропущенные без хода позиции (3) не засчитаны как ошибки и не отмечены слабыми темами."
                ),
                onRestartSession = {},
                onSwitchToFreeTraining = {},
                onPracticeWeakPatterns = {}
            )
        }
        ui.onNodeWithText("решено 7", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Попыток: 7 из 10 · чисто: 2, с ошибками: 5, пропущено: 3").assertIsDisplayed()
        ui.onNodeWithText("Поспешных ходов: 1").assertIsDisplayed()
    }
}
