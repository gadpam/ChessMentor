package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.presentation.ui.components.PawnPromotionDialog
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PawnPromotionDialogTest {
    @get:Rule val ui = createComposeRule()

    @Test fun blackPromotionShowsBlackPiecesAllowsKnightSelectionAndCancelsCleanly() {
        val selected = mutableListOf<PieceType>()
        var dismissed = 0
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                Box(Modifier.width(320.dp)) {
                    PawnPromotionDialog(
                        side = Side.BLACK,
                        onSelect = { selected.add(it) },
                        onDismiss = { dismissed++ }
                    )
                }
            }
        }

        ui.onNodeWithText("♛ Ферзь").performScrollTo().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithContentDescription("Превратить пешку чёрных в: Конь").assertExists()
        ui.onNodeWithText("♞ Конь").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Отмена").assertHeightIsAtLeast(48.dp).performClick()

        ui.runOnIdle {
            assertEquals(listOf(PieceType.KNIGHT), selected)
            assertEquals(1, dismissed)
        }
    }
}
