package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase.AnalysisProgress
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase.Result
import com.example.chessmentor.presentation.ui.components.screen.GameCard
import com.example.chessmentor.presentation.ui.components.screen.LibraryAnalysisFeedback
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryAnalysisTest {
    @get:Rule val ui = createComposeRule()
    private val pending = Game(id = 14, userId = 1, playedAt = 1, playerColor = ChessColor.WHITE, pgnData = "1. e4 *")
    private val failed = pending.copy(id = 15, analysisStatus = AnalysisStatus.FAILED)
    private fun success(game: Game) = AnalysisProgress.Completed(Result.Success(game, emptyList(), emptyList(), emptyList()))

    @Test fun longErrorKeepsCloseAvailableWithLargeType() {
        var closed = false
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                ChessMentorTheme(darkTheme = true) {
                    AnalysisProgressDialog(AnalysisProgress.Failed("Длинное сообщение об ошибке. ".repeat(80)),
                        onDismiss = { closed = true }, gameId = 15)
                }
            }
        }
        ui.onNodeWithText("Ошибка анализа").assertIsDisplayed()
        ui.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals(true, closed) }
    }

    @Test fun startRetryCancelAndCompletionAreBoundToTheTappedGame() {
        val starts = mutableListOf<Pair<Long, Boolean>>()
        var progress by mutableStateOf<AnalysisProgress?>(null)
        var cancels = 0
        var summaries = 0
        ui.setContent {
            var requested by remember { mutableStateOf<Long?>(null) }
            LazyColumn {
                items(listOf(pending, failed)) { game ->
                    GameCard(game, onClick = { error("Открылась карточка вместо анализа") },
                        onAnalyze = { id, retry ->
                            starts.add(id to retry)
                            requested = id
                            progress = AnalysisProgress.Starting
                        }, onDelete = {})
                }
            }
            LibraryAnalysisFeedback(requested, progress, { requested = null }, { progress = null },
                { cancels++; progress = null }, { summaries++ })
        }
        fun start(label: String) {
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(label))
            ui.onNodeWithText(label).assertHeightIsAtLeast(48.dp).performClick()
        }
        start("Разобрать")
        ui.onNodeWithText("Партия №14").assertIsDisplayed()
        ui.runOnIdle { progress = AnalysisProgress.InProgress(1, 2, "e4") }
        ui.onNodeWithText("Отменить анализ").performClick()
        ui.runOnIdle { assertEquals(1, cancels); progress = success(pending) }
        ui.runOnIdle { assertEquals(0, summaries) }
        start("Повторить")
        ui.onNodeWithText("Партия №15").assertIsDisplayed()
        ui.runOnIdle { progress = success(pending) }
        ui.runOnIdle { assertEquals(0, summaries) }
        start("Повторить")
        ui.runOnIdle { progress = AnalysisProgress.Failed("Движок недоступен") }
        ui.onNodeWithText("Закрыть").performClick()
        ui.runOnIdle { assertEquals(0, summaries) }
        start("Повторить")
        ui.runOnIdle { progress = success(failed.copy(analysisStatus = AnalysisStatus.COMPLETED)) }
        ui.runOnIdle {
            assertEquals(1, summaries)
            assertEquals(listOf(14L to false, 15L to true, 15L to true, 15L to true), starts)
        }
    }
}
