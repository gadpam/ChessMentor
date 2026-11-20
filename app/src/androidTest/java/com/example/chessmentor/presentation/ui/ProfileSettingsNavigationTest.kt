package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.example.chessmentor.MainActivity
import org.junit.Rule
import org.junit.Test

class ProfileSettingsNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun toolbarAndAndroidBackBothReturnFromSettingsToHome() {
        val gear = ui.onNodeWithContentDescription("Открыть настройки")
        // The clean debug install restores its tester profile asynchronously.
        ui.waitUntil(timeoutMillis = 15_000) {
            ui.onAllNodesWithContentDescription("Открыть настройки").fetchSemanticsNodes().isNotEmpty()
        }
        gear.performClick()
        ui.onNodeWithText("Настройки").assertIsDisplayed()
        ui.onNodeWithContentDescription("Назад").performClick()
        gear.assertIsDisplayed().performClick()
        ui.onNodeWithText("Настройки").assertIsDisplayed()
        pressBack()
        gear.assertIsDisplayed()
    }
}
