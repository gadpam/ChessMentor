package com.example.chessmentor.presentation.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.presentation.ui.components.HorizontalMoveList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HorizontalMoveListAccessibilityTest {
    @get:Rule val ui = createComposeRule()

    @Test fun startPositionAndMoveChipsExposeRoleSelectedAndSpokenQuality() {
        val clicks = mutableListOf<Int>()
        val analyzed = listOf(
            AnalyzedMove(gameId = 1, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE,
                quality = MoveQuality.BRILLIANT, san = "e4", bestMove = "e2e4", evalBefore = 20, evalAfter = 35, evalLoss = 0),
            AnalyzedMove(gameId = 1, moveIndex = 1, moveNumber = 1, color = ChessColor.BLACK,
                quality = MoveQuality.MISTAKE, san = "e5", bestMove = "c7c5", evalBefore = -35, evalAfter = -180, evalLoss = 145)
        )
        ui.setContent {
            HorizontalMoveList(
                moves = listOf("e4", "e5"),
                currentMoveIndex = 0,
                mistakes = emptyList(),
                analyzedMoves = analyzed,
                onMoveClick = { clicks.add(it) }
            )
        }

        val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        ui.onNodeWithContentDescription("Начальная позиция")
            .assert(isButton)
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        ui.onNodeWithContentDescription("1. e4, блестящий ход")
            .assertIsDisplayed()
            .assert(isButton)
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)

        ui.onNodeWithContentDescription("1... e5, ошибка")
            .assertIsDisplayed()
            .assert(isButton)
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        ui.runOnIdle { assertEquals(listOf(-1, 1), clicks) }
    }
}
