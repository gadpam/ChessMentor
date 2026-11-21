package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.chessmentor.domain.analysis.GameMoveClassifier.SideAnalysisSummary
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.presentation.ui.components.screen.GameCard
import com.example.chessmentor.presentation.ui.components.screen.GameMetricsCard
import com.example.chessmentor.presentation.ui.components.screen.MiniGameCard
import org.junit.Rule
import org.junit.Test

class GameAccuracyTest {
    @get:Rule val ui = createComposeRule()
    private val game = Game(id = 3, userId = 1, pgnData = "1. e4 e5 *", playedAt = 1,
        playerColor = ChessColor.WHITE, analysisStatus = AnalysisStatus.COMPLETED)
    private val opponent = SideAnalysisSummary(ChessColor.BLACK, 91.2, 18, 1, 0, 0, 0, 0, emptyMap())

    @Test fun sameRecordKeepsMissingZeroAndFractionAcrossAllThreeCards() {
        var record by mutableStateOf(game)
        ui.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MiniGameCard(record) {}
                GameCard(game = record, onClick = {}, onDelete = {})
                GameMetricsCard(record, opponentSummary = opponent)
            }
        }
        ui.onNodeWithText("Точность не рассчитана").assertExists()
        ui.onAllNodesWithContentDescription("Точность не рассчитана").assertCountEquals(2)
        ui.onNodeWithText("Плохо").assertDoesNotExist()
        ui.onNodeWithText("0%").assertDoesNotExist()
        ui.onNodeWithText("0 сП").assertDoesNotExist()
        ui.onNodeWithText("91.2%").assertExists()
        ui.runOnIdle { record = game.copy(accuracy = 0.0, averageEvaluationLoss = 0) }
        ui.onAllNodesWithContentDescription("Точность: 0%").assertCountEquals(2)
        ui.onAllNodesWithText("0%").assertCountEquals(2) // indicator and player's side
        ui.onNodeWithText("0 сП").assertExists()
        ui.onNodeWithText("Точность не рассчитана").assertDoesNotExist()
        ui.runOnIdle { record = game.copy(accuracy = 83.75) }
        ui.onAllNodesWithContentDescription("Точность: 83.8%").assertCountEquals(2)
        ui.onAllNodesWithText("83.8%").assertCountEquals(2)
    }
}
