package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.chessmentor.presentation.ui.components.screen.ReviewNavigationBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewNavigationTest {
    @get:Rule val ui = createComposeRule()

    @Test fun stepAndMomentHaveSeparateActionsAndRareJumpsCloseMenu() {
        val calls = mutableListOf<String>()
        ui.setContent {
            ReviewNavigationBar(7, 32, false, true, true,
                onGoToStart = { calls.add("start") }, onPreviousKeyMoment = { calls.add("previous moment") },
                onGoToPrevious = { calls.add("back") }, onGoToNext = { calls.add("step") },
                onNextKeyMoment = { calls.add("moment") }, onGoToEnd = { calls.add("end") })
        }
        ui.onNodeWithContentDescription("Следующий ход").performClick()
        ui.onNodeWithText("К моменту").performClick()
        for (label in listOf("Предыдущий ключевой момент", "В начало партии", "В конец партии")) {
            ui.onNodeWithContentDescription("Другие переходы").performClick()
            ui.onNodeWithText(label).performClick()
            ui.onNodeWithText(label).assertDoesNotExist()
        }
        ui.runOnIdle { assertEquals(listOf("step", "moment", "previous moment", "start", "end"), calls) }
    }

    @Test fun startPositionWithoutMomentsDoesNotOfferInvalidJumps() {
        ui.setContent { ReviewNavigationBar(-1, 10, false, false, false, {}, {}, {}, {}, {}, {}) }
        ui.onNodeWithContentDescription("Предыдущий ход").assertIsNotEnabled()
        ui.onNodeWithText("К моменту").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Другие переходы").performClick()
        ui.onNodeWithText("В начало партии").assertIsNotEnabled()
        ui.onNodeWithText("Предыдущий ключевой момент").assertIsNotEnabled()
    }
}
