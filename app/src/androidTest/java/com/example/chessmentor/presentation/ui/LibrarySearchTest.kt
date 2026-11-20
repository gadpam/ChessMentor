package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.presentation.ui.components.screen.GamesListScreen
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibrarySearchTest {
    @get:Rule val ui = createComposeRule()
    private val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
    private val model = GameViewModel(container)

    private fun show(games: List<Game>, onImport: () -> Unit = {}) {
        model.userGames.addAll(games)
        ui.setContent { GamesListScreen(PaddingValues(), model, container, onGameClick = {}, onNavigateToUpload = onImport) }
    }

    private fun visibleText(text: String): SemanticsNodeInteraction {
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(text))
        return ui.onNodeWithText(text)
    }

    @Test fun combinedMissExplainsBothCriteriaAndResetKeepsRecordsAndSort() {
        val win = Game(id = 1, userId = 1, playedAt = 1, playerColor = ChessColor.WHITE,
            pgnData = "[Black \"Rival\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0")
        val games = listOf(win, win.copy(id = 2, playedAt = 2, pgnData = "[Black \"Other\"]\n[Result \"0-1\"]\n\n1. d4 d5 0-1",
            analysisStatus = AnalysisStatus.FAILED))
        show(games)
        ui.onNodeWithContentDescription("Сортировка").performClick()
        ui.onNodeWithText("Сначала старые").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Rival")
        ui.onNodeWithText("Поражения").performScrollTo().performClick()
        visibleText("Запрос: «Rival»\nФильтр: Поражения").assertIsDisplayed()
        ui.onNodeWithText("Импортировать партии").assertDoesNotExist()
        visibleText("Показать все партии").performClick()
        visibleText("Показано: 2 из 2 · Сначала старые").assertIsDisplayed()
        ui.onNodeWithContentDescription("Очистить поиск").assertDoesNotExist()
        ui.onNodeWithText("Все").assertIsSelected()
        ui.runOnIdle { assertEquals(games, model.userGames.toList()) }
    }

    @Test fun clearingSearchKeepsTheQueueFilterAndOnlyPendingGamesMatch() {
        val pending = Game(id = 1, userId = 1, playedAt = 1, playerColor = ChessColor.WHITE, pgnData = "1. e4 *")
        show(listOf(pending, pending.copy(id = 2, analysisStatus = AnalysisStatus.IN_PROGRESS),
            pending.copy(id = 3, analysisStatus = AnalysisStatus.FAILED)))
        ui.onNode(hasText("В очереди") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).performTextInput("missing")
        visibleText("Запрос: «missing»\nФильтр: В очереди").assertIsDisplayed()
        ui.onNodeWithContentDescription("Очистить поиск").performScrollTo().performClick()
        visibleText("Показано: 1 из 3 · Сначала новые").assertIsDisplayed()
        visibleText("Показать все партии").performClick()
        ui.onNodeWithText("Все").assertIsSelected()
    }

    @Test fun emptyLibraryOffersImportInsteadOfReset() {
        var imports = 0
        show(emptyList()) { imports++ }
        visibleText("Партий пока нет").assertIsDisplayed()
        ui.onNodeWithText("Показать все партии").assertDoesNotExist()
        visibleText("Импортировать партии").performClick()
        ui.runOnIdle { assertEquals(1, imports) }
    }
}
