package com.example.chessmentor.presentation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.presentation.ui.components.screen.DailyPuzzleWidget
import com.example.chessmentor.presentation.ui.components.screen.ModePicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TrainingEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewAndButtonOpenUnsolvedDailyNotSession() = checkDailyEntries(isSolved = false)

    @Test fun previewAndButtonReplaySolvedDailyNotSession() = checkDailyEntries(isSolved = true)

    private fun checkDailyEntries(isSolved: Boolean) {
        val opened = mutableListOf<TrainingMode>()
        compose.setContent {
            DailyPuzzleWidget(isSolved = isSolved, onClick = { opened.add(TrainingMode.Daily) },
                onStartSmartSession = { opened.add(TrainingMode.SmartSession) })
        }
        compose.onNodeWithContentDescription("Позиция задачи дня.", substring = true)
            .assertHasClickAction().performTouchInput { click(center) }
        compose.onNodeWithText(if (isSolved) "Повторить задачу" else "Решить задачу дня").performClick()
        compose.onNodeWithText("Начать тренировку").performClick()
        compose.runOnIdle { assertEquals(listOf(TrainingMode.Daily, TrainingMode.Daily, TrainingMode.SmartSession), opened) }
    }

    @Test fun topicSelectionChangesModeAndClosesMenu() {
        val expected = TrainingMode.ByTheme(TrainingMode.selectableThemes.first())
        compose.setContent {
            var mode by remember { mutableStateOf<TrainingMode>(TrainingMode.All) }
            ModePicker(mode, dueReviewCount = 0, enabled = true, onSelectMode = { mode = it })
        }
        compose.onNodeWithContentDescription("Сменить режим").performClick()
        compose.onNodeWithText("По тактической теме").performScrollTo().performClick()
        compose.onNodeWithText(expected.displayName).performClick()
        compose.onNodeWithText(expected.displayName).assertIsDisplayed()
        compose.onNodeWithText("Назад к режимам").assertDoesNotExist()
    }
}
