package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.presentation.ui.components.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.EmptyState
import com.example.chessmentor.presentation.ui.components.screen.TrainingScreen
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TrainingSafetyTest {
    @get:Rule val ui = createComposeRule()

    @Test fun brandEmptyStateAndEmptyStateAcceptNullIconsWithoutCrash() {
        var clicks = 0
        ui.setContent {
            ChessMentorTheme {
                BrandEmptyState(
                    icon = null,
                    painter = null,
                    title = "Задачи пока недоступны",
                    subtitle = "Попробуйте другой режим",
                    actionLabel = "Переключить",
                    onAction = { clicks++ }
                )
            }
        }
        ui.onNodeWithText("Задачи пока недоступны").assertIsDisplayed()
        ui.onNodeWithText("Переключить").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun emptyStateRendersWithAndWithoutIcon() {
        var currentIcon by mutableStateOf<ImageVector?>(null)
        var currentTitle by mutableStateOf("Без иконки")
        var showAction by mutableStateOf(false)
        ui.setContent {
            ChessMentorTheme {
                EmptyState(
                    icon = currentIcon,
                    title = currentTitle,
                    actionLabel = if (showAction) "Ок" else null,
                    onAction = if (showAction) ({}) else null
                )
            }
        }
        ui.onNodeWithText("Без иконки").assertIsDisplayed()
        ui.runOnIdle {
            currentIcon = Icons.Default.Star
            currentTitle = "С иконкой"
            showAction = true
        }
        ui.onNodeWithText("С иконкой").assertIsDisplayed()
        ui.onNodeWithText("Ок").assertIsDisplayed()
    }

    @Test fun trainingScreenWithInvalidUserIdShowsErrorAndLeavesCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer.getInstance(context)
        var leftCount = 0
        var consumedCount = 0
        ui.setContent {
            ChessMentorTheme {
                TrainingScreen(
                    paddingValues = PaddingValues(),
                    container = container,
                    userId = -1L,
                    onLeaveTraining = { leftCount++ },
                    initialModeKey = "daily",
                    onModeConsumed = { consumedCount++ }
                )
            }
        }
        ui.onNodeWithText("Войдите в профиль, чтобы начать тренировку.").assertIsDisplayed()
        ui.onNodeWithText("Вернуться назад").assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals(1, leftCount)
            assertEquals(1, consumedCount)
        }
    }
}
