package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.screen.UserHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileHeaderTest {
    @get:Rule val ui = createComposeRule()

    @Test fun longNameLeavesSpaceForNamedSettingsButton() {
        val nickname = "Я".repeat(50)
        var opened = 0
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    UserHeader(User(nickname = nickname, email = "header@example.test", passwordHash = "")) { opened++ }
                }
            }
        }
        val profile = ui.onNodeWithText(nickname).assertIsDisplayed()
        ui.onNodeWithText("Рейтинг", substring = true).assertDoesNotExist()
        val button = ui.onNodeWithContentDescription("Открыть настройки").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTrue(profile.fetchSemanticsNode().boundsInRoot.right <= button.fetchSemanticsNode().boundsInRoot.left)
        ui.onNodeWithText("Я").assertDoesNotExist()
        button.performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, opened) }
    }
}
