package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.presentation.ui.components.screen.SummaryHeader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SummaryHeaderColorFixTest {
    @get:Rule val ui = createComposeRule()

    @Test fun analyzedSummaryWarnsBeforeChangingSideAndCancelMakesNoChanges() {
        val game = Game(
            id = 42L,
            userId = 1L,
            pgnData = "[White \"Alpha\"]\n[Black \"Beta\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            playerColor = ChessColor.WHITE,
            playedAt = 0L,
            analysisStatus = AnalysisStatus.COMPLETED,
            accuracy = 82.0
        )
        val info = PgnParser.extractGameCardInfo(game)
        val changes = mutableListOf<ChessColor>()

        ui.setContent {
            SummaryHeader(
                game = game,
                cardInfo = info,
                onChangePlayerColor = { changes.add(it) }
            )
        }

        ui.onNodeWithText("Дата игры неизвестна", substring = true).assertIsDisplayed()
        ui.onNodeWithText("Исправить мою сторону (сейчас: Белые)")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        ui.onNodeWithText("Исправить вашу сторону?").assertIsDisplayed()
        ui.onNodeWithText("Прежний разбор", substring = true).assertIsDisplayed()
        ui.onNodeWithText("Отмена").assertHeightIsAtLeast(48.dp).performClick()
        ui.runOnIdle { assertEquals(emptyList<ChessColor>(), changes) }

        ui.onNodeWithText("Исправить мою сторону (сейчас: Белые)").performClick()
        ui.onNodeWithText("Сменить на чёрные").assertHeightIsAtLeast(48.dp).performClick()
        ui.runOnIdle { assertEquals(listOf(ChessColor.BLACK), changes) }
    }
}
