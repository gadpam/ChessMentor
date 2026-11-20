package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso.pressBack
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.presentation.ui.components.screen.GameCard
import com.example.chessmentor.presentation.ui.components.screen.GamesListScreen
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
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
        ui.onNodeWithContentDescription("Поиск и фильтры").performClick()

        ui.onNodeWithContentDescription("Сортировка").performClick()
        ui.onNodeWithText("Сначала новые").assertIsSelected()
        ui.onNodeWithText("Сначала старые").assertIsNotSelected().performClick()
        ui.onNodeWithContentDescription("Сортировка").performClick()
        ui.onNodeWithText("Сначала старые").assertIsSelected()
        pressBack()
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
        ui.onNodeWithContentDescription("Поиск и фильтры").performClick()

        ui.onNode(hasText("В очереди") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).performTextInput("missing")
        visibleText("Запрос: «missing»\nФильтр: В очереди").assertIsDisplayed()
        ui.onNodeWithContentDescription("Очистить поиск").performScrollTo().performClick()
        visibleText("Показано: 1 из 3 · Сначала новые").assertIsDisplayed()
        ui.onNodeWithContentDescription("Поиск и фильтры").performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).assertDoesNotExist()
        visibleText("В очереди · Сначала новые · 1 из 3").performClick()
        visibleText("Показать все партии").performClick()
        ui.onNodeWithText("Все").assertIsSelected()
    }

    private val duplicateGames = listOf(14L, 15L).map { id ->
        Game(id = id, userId = 1, playedAt = 1, playerColor = ChessColor.WHITE,
            pgnData = "[Black \"Rival\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0")
    }

    @Test fun completedGameFitsInACompactRowWithoutTheReport() {
        val game = duplicateGames.first().copy(analysisStatus = AnalysisStatus.COMPLETED, accuracy = 50.9)
        ui.setContent {
            Box(Modifier.width(360.dp)) { GameCard(game, onClick = {}, onDelete = {}) }
        }
        ui.onNodeWithText("Rival").assertHeightIsAtLeast(48.dp)
            .assert(hasContentDescription("Точность: 50.9%"))
        check(ui.onNodeWithText("Rival").getUnclippedBoundsInRoot().height <= 64.dp)
        ui.onNodeWithText("Зевки").assertDoesNotExist()
        ui.onNodeWithText("Удалить партию").assertDoesNotExist()
    }

    @Test fun populatedLibraryAlsoOffersImport() {
        var imports = 0
        show(duplicateGames) { imports++ }
        visibleText("Добавить").performClick()
        ui.runOnIdle { assertEquals(1, imports) }
    }

    private fun openDeleteFor(id: Long) {
        val description = "Действия партии №$id"
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasContentDescription(description))
        ui.onNodeWithContentDescription(description).assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Удалить партию").performClick()
        ui.onNodeWithText("Удалить партию №$id?").assertIsDisplayed()
    }

    @Test fun dismissDeleteRetainsQueryFilterAndBothGames() {
        show(duplicateGames)
        ui.onNodeWithContentDescription("Поиск и фильтры").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Rival")
        ui.onNodeWithText("Победы").performScrollTo().performClick()
        openDeleteFor(15)
        ui.onNodeWithText("Отмена").performClick()
        ui.onNodeWithText("Удалить партию №15?").assertDoesNotExist()
        openDeleteFor(14)
        pressBack()
        ui.onNodeWithText("Удалить партию №14?").assertDoesNotExist()
        visibleText("Показано: 2 из 2 · Сначала новые").assertIsDisplayed()
        ui.onNodeWithText("Победы").assertIsSelected()
        ui.onNode(hasSetTextAction()).assert(hasText("Rival"))
        ui.runOnIdle { assertEquals(duplicateGames, model.userGames.toList()) }
    }

    @Test fun narrowDarkCardAtLargeScaleKeepsDeleteAndCancelReachable() {
        val game = duplicateGames.last().copy(pgnData = "[Black \"ОченьДлинноеИмяСоперника\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0")
        var deletes = 0
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                ChessMentorTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp)) {
                        GameCard(game, onClick = { error("Открылась сводка") }, onDelete = { deletes++ })
                    }
                }
            }
        }
        ui.onNodeWithContentDescription("Действия партии №15").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Удалить партию").performClick()
        ui.onNodeWithText("Отменить удаление нельзя").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Удалить").assertIsDisplayed()
        ui.onNodeWithText("Отмена").assertIsDisplayed().performClick()
        ui.onNodeWithText("Удалить партию №15?").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, deletes) }
    }

    @Test fun confirmTargetsOnlyTheTappedRecordWithTheSameOpponent() {
        val deleted = mutableListOf<Long?>()
        ui.setContent {
            LazyColumn {
                items(duplicateGames, key = { it.id!! }) { game ->
                    GameCard(game, onClick = { error("Открылась партия вместо подтверждения") },
                        onDelete = { deleted.add(game.id) })
                }
            }
        }
        openDeleteFor(15)
        ui.runOnIdle { assertEquals(emptyList<Long?>(), deleted) }
        ui.onNodeWithText("Удалить").performClick()
        ui.runOnIdle { assertEquals(listOf(15L), deleted) }
        ui.onNodeWithText("Удалить партию №15?").assertDoesNotExist()
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
