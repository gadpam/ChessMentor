package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.presentation.ui.components.screen.MiniGameCard
import com.example.chessmentor.presentation.ui.components.screen.SummaryMiniBoardCard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecentGameCardTest {
    @get:Rule val ui = createComposeRule()

    private val game = Game(id = 14, userId = 1, playedAt = 1, playerColor = ChessColor.BLACK,
        pgnData = "[White \"Rival\"]\n[Black \"Player\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0")

    @Test fun identicalOpponentsOpenTheTappedRecordAndNullIsNotZero() {
        val first = game.copy(analysisStatus = AnalysisStatus.COMPLETED)
        val second = first.copy(id = 15, accuracy = 0.0)
        val opened = mutableListOf<Long?>()
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Column(Modifier.width(320.dp)) {
                    for (record in listOf(first, second)) MiniGameCard(record) { opened.add(it.id) }
                }
            }
        }
        val firstCard = ui.onNodeWithText("№14 ·", substring = true)
        val secondCard = ui.onNodeWithText("№15 ·", substring = true)
        firstCard.assert(hasContentDescription("Поражение")).assert(hasContentDescription("Точность не рассчитана"))
        secondCard.assert(hasContentDescription("Точность: 0%")).performClick()
        firstCard.performClick()
        ui.runOnIdle { assertEquals(listOf(15L, 14L), opened) }
    }

    @Test fun absentResultIsNotADraw() {
        var result by mutableStateOf("*")
        ui.setContent { MiniGameCard(game.copy(pgnData = "1. e4 e5 $result")) {} }
        ui.onNodeWithContentDescription("Без результата").assertExists()
        ui.runOnIdle { result = "1/2-1/2" }
        ui.onNodeWithContentDescription("Ничья").assertExists()
    }

    @Test fun analysisStateDoesNotOverwriteOutcomeOrShowStaleAccuracy() {
        var status by mutableStateOf(AnalysisStatus.PENDING)
        ui.setContent { MiniGameCard(game.copy(playerColor = ChessColor.WHITE, analysisStatus = status, accuracy = 83.0)) {} }
        for ((state, label) in listOf(AnalysisStatus.PENDING to "Ожидает анализа",
            AnalysisStatus.IN_PROGRESS to "Идёт анализ", AnalysisStatus.FAILED to "Ошибка анализа",
            AnalysisStatus.COMPLETED to "Точность: 83%")) {
            ui.runOnIdle { status = state }
            ui.onNode(hasText(label) or hasContentDescription(label)).assert(hasContentDescription("Победа")).assert(hasContentDescription("Вы белыми"))
            if (state != AnalysisStatus.COMPLETED) ui.onNodeWithContentDescription("Точность: 83%").assertDoesNotExist()
        }
    }

    @Test fun miniGameCardCanTriggerAnalyzeWhenProvided_andSummaryMiniBoardOpensReview() {
        val triggered = mutableListOf<Pair<Long, Boolean>>()
        var summaryClicked = 0
        ui.setContent {
            Column(Modifier.width(320.dp)) {
                MiniGameCard(
                    game = game.copy(analysisStatus = AnalysisStatus.PENDING),
                    onOpen = {},
                    onAnalyze = { id, retry -> triggered.add(id to retry) }
                )
                SummaryMiniBoardCard(
                    board = Board(),
                    lastMove = Square.E2 to Square.E4,
                    flipped = false,
                    title = "Переломный ход 14. Qd2",
                    subtitle = "Шансы: 58% → 12%",
                    evalCp = -240,
                    onClick = { summaryClicked++ }
                )
            }
        }
        ui.onNodeWithText("Разобрать").assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Переломный ход 14. Qd2").assertIsDisplayed()
        ui.onNodeWithText("-2.4").assertIsDisplayed()
        ui.onNodeWithText("Разобрать позицию на доске").performClick()
        ui.runOnIdle {
            assertEquals(listOf(14L to false), triggered)
            assertEquals(1, summaryClicked)
        }
    }
}
