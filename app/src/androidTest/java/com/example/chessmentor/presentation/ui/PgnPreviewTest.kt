package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.presentation.ui.components.screen.PgnLivePreviewCard
import com.example.chessmentor.presentation.ui.components.screen.UploadForm
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PgnPreviewTest {
    @get:Rule val ui = createComposeRule()
    private val vm = GameViewModel(AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext))
    private val first = "[White \"Rival\"]\n[Black \"Tester\"]\n[TimeControl \"180+2\"]\n\n1. e4 e5 *"
    private val second = "[White \"Tester\"]\n[Black \"Other\"]\n\n1. d4 d5 *"

    @Test fun editingReplacesNamesAutoColorClockAndFileOrigin() {
        ui.setContent { androidx.compose.material3.Text(vm.timeControl.value) }
        ui.runOnIdle {
            vm.onChessUsernameHintChanged("Tester")
            vm.onPgnInputChanged(first, "first.pgn")
        }
        ui.waitUntil(5000) { vm.importPreview.value?.singleParsed != null }
        ui.runOnIdle {
            assertEquals(ChessColor.BLACK, vm.selectedColor.value)
            assertEquals("Блиц 3+2", vm.timeControl.value)
            vm.onPgnInputChanged(second)
            assertNull(vm.importPreview.value)
            assertFalse(vm.colorAutoDetected.value)
        }
        ui.waitUntil(5000) { vm.importPreview.value?.singleParsed != null }
        ui.runOnIdle {
            assertEquals("Other", vm.importPreview.value!!.singleParsed!!.metadata.blackName)
            assertNull(vm.importPreview.value!!.sourceFileName)
            assertEquals(ChessColor.WHITE, vm.selectedColor.value)
            assertTrue(vm.colorAutoDetected.value)
            assertEquals("", vm.timeControl.value)
            vm.setImportTimeControl("5+0")
            vm.onChessUsernameHintChanged("Nobody")
        }
        ui.waitUntil(5000) { vm.importPreview.value?.singleParsed != null }
        ui.runOnIdle {
            assertFalse(vm.colorAutoDetected.value)
            assertEquals("5+0", vm.timeControl.value)
        }
    }

    @Test fun duplicateOpensEarliestSavedRecordAndCopyRequiresConfirmation() {
        val record = Game(id = 14, userId = 1, pgnData = first, playedAt = 1, playerColor = ChessColor.BLACK)
        val opened = mutableListOf<Long>()
        var copies = 0
        ui.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.importPreview.value?.let {
                    PgnLivePreviewCard(it, ChessColor.BLACK, false, false, { copies++ }, { id -> opened.add(id) })
                }
            }
        }
        ui.runOnIdle {
            vm.userGames.addAll(listOf(record.copy(id = 99), record))
            vm.onPgnInputChanged(first)
        }
        ui.waitUntil(5000) { vm.importPreview.value?.duplicateExistingGame != null }
        ui.onNodeWithText("Открыть партию №14").performScrollTo().performClick()
        ui.onNodeWithText("Создать отдельную копию").performScrollTo().performClick()
        ui.onNodeWithText("Отмена").performClick()
        ui.runOnIdle { assertEquals(listOf(14L), opened); assertEquals(0, copies) }
        ui.onNodeWithText("Создать отдельную копию").performClick()
        ui.onNodeWithText("Создать и разобрать").performClick()
        ui.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun replacingSingleWithArchiveOnlyOffersNewGamesAndNewestAnalysis() {
        val batchActions = mutableListOf<Boolean>()
        ui.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UploadForm(vm.pgnInput.value, vm.importPreview.value, vm.selectedColor.value, vm.timeControl.value,
                    vm.chessUsernameHint.value, false, {}, {}, vm::clearImportForm, { vm.onPgnInputChanged(it) },
                    vm::selectPlayerColorManually, vm::setImportTimeControl, vm::onChessUsernameHintChanged,
                    { error("Одиночный анализ") }, { error("Сохранение одной партии") }, { batchActions.add(it) })
            }
        }
        ui.onNodeWithText("Ваш цвет:").assertDoesNotExist()
        ui.onNodeWithText("Контроль времени").assertDoesNotExist()
        ui.onNodeWithText("Импортировать партию и разобрать").assertDoesNotExist()
        ui.onNodeWithText("Что такое PGN?").performScrollTo().performClick()
        ui.onNodeWithText("PGN — текстовая запись партии", substring = true).assertExists()
        ui.onNodeWithText("Скрыть справку").performScrollTo().performClick()
        ui.onNodeWithText("PGN — текстовая запись партии", substring = true).assertDoesNotExist()
        ui.runOnIdle { vm.onPgnInputChanged(first) }
        ui.waitUntil(5000) { vm.importPreview.value?.singleParsed != null }
        ui.onNodeWithText("Импортировать партию и разобрать").assertIsEnabled()
        ui.runOnIdle {
            vm.onPgnInputChanged("$first\n\n$second\n\n$first")
            assertNull(vm.importPreview.value)
        }
        ui.waitUntil(5000) { vm.importPreview.value?.isBatch == true }
        ui.onNodeWithText("Импортировать партию и разобрать").assertDoesNotExist()
        ui.onNodeWithText("Импортировать в библиотеку (2)").performScrollTo().performClick()
        ui.onNodeWithText("Импортировать (2) и разобрать самую свежую").performScrollTo().performClick()
        ui.runOnIdle {
            assertEquals(listOf(false, true), batchActions)
            assertEquals(1, vm.importPreview.value!!.batchDuplicatesCount)
            vm.onPgnInputChanged(first)
            vm.onPgnInputChanged("1. d4 d5 2. Bb5 a6")
        }
        ui.waitUntil(5000) { vm.importPreview.value?.singleError != null }
        ui.onNodeWithText("Импортировать партию и разобрать").assertIsNotEnabled()
        ui.onNodeWithText("Импортировать в библиотеку (2)").assertDoesNotExist()
    }

    @Test fun batchArchiveOverLimitSupportsWindowPaginationAndErrorInspection() {
        val archive = (1..52).joinToString("\n\n") { idx ->
            if (idx == 52) {
                "[Site \"https://lichess.org/Pag52\"]\n[White \"Hero\"]\n[Black \"BrokenRival\"]\n\n1. e4 e5 2. Bh6 1-0"
            } else {
                "[Site \"https://lichess.org/Pag$idx\"]\n[White \"Hero\"]\n[Black \"Rival$idx\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0"
            }
        }
        ui.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.importPreview.value?.let {
                    PgnLivePreviewCard(
                        preview = it,
                        selectedColor = vm.selectedColor.value,
                        colorAutoDetected = false,
                        isLoading = false,
                        onCopy = {},
                        onOpenExistingGame = {},
                        onNextBatchWindow = { vm.showNextBatchWindow() },
                        onResetBatchWindow = { vm.resetBatchWindow() }
                    )
                }
            }
        }
        ui.runOnIdle { vm.onPgnInputChanged(archive, "big.pgn") }
        ui.waitUntil(5000) { vm.importPreview.value?.totalGames == 52 }

        ui.onNodeWithText("Проверены записи 1–50 из 52").assertIsDisplayed()
        ui.onNodeWithText("Проверить следующие 2").performScrollTo().performClick()

        ui.waitUntil(5000) { vm.importPreview.value?.batchOffset == 50 }
        ui.onNodeWithText("Проверены записи 51–52 из 52").assertIsDisplayed()
        ui.onNodeWithText("Посмотреть ошибки (1)").performScrollTo().performClick()
        ui.onNodeWithText("Запись #52", substring = true).assertIsDisplayed()
        ui.onNodeWithText("К записям 1–50").performScrollTo().performClick()

        ui.waitUntil(5000) { vm.importPreview.value?.batchOffset == 0 }
        ui.onNodeWithText("Проверены записи 1–50 из 52").assertIsDisplayed()
    }

    @Test fun annotatedRevisionAndDifferentContextDuplicateBranchesOfferDirectActions() {
        var updatedGameId: Long? = null
        var addedDifferentGame = 0
        ui.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.importPreview.value?.let {
                    PgnLivePreviewCard(
                        preview = it,
                        selectedColor = vm.selectedColor.value,
                        colorAutoDetected = false,
                        isLoading = false,
                        onCopy = {},
                        onOpenExistingGame = {},
                        onUpdateNotes = { id -> updatedGameId = id },
                        onAddDifferentGame = { addedDifferentGame++ }
                    )
                }
            }
        }

        val plainMoves = "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6 5. d4 exd4 6. cxd4 Bb4+ 7. Bd2 Bxd2+ 8. Nbxd2 d5 1-0"
        val existingRecord = Game(
            id = 14,
            userId = 1,
            pgnData = "[White \"Tester\"]\n[Black \"FirstRival\"]\n[Date \"2026.10.01\"]\n[Result \"1-0\"]\n\n$plainMoves",
            playerColor = ChessColor.WHITE
        )
        val annotatedPgn = "[White \"Tester\"]\n[Black \"FirstRival\"]\n[Date \"2026.10.01\"]\n[Result \"1-0\"]\n\n" +
            "1. e4 { Сильный первый ход } e5 2. Nf3 Nc6 3. Bc4 (3. Bb5 a6) 3... Bc5 4. c3 Nf6 5. d4 exd4 6. cxd4 Bb4+ 7. Bd2 Bxd2+ 8. Nbxd2 d5 1-0"
        val otherOpponentPgn = "[White \"Tester\"]\n[Black \"SecondRival\"]\n[Date \"2026.10.12\"]\n[Result \"1-0\"]\n\n$plainMoves"

        ui.runOnIdle {
            vm.userGames.clear()
            vm.userGames.add(existingRecord)
            vm.onPgnInputChanged(annotatedPgn)
        }
        ui.waitUntil(5000) { vm.importPreview.value?.duplicateExistingGame?.id == 14L }
        ui.onNodeWithText("В партии №14 отличаются комментарии или варианты").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Обновить заметки в партии №14").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(14L, updatedGameId) }

        ui.runOnIdle { vm.onPgnInputChanged(otherOpponentPgn) }
        ui.waitUntil(5000) {
            vm.importPreview.value?.duplicateMatchInfo?.kind ==
                com.example.chessmentor.domain.pgn.PgnParser.DuplicateMatchKind.DIFFERENT_CONTEXT
        }
        ui.onNodeWithText("Совпали ходы с партией №14, но различаются соперник или дата").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Добавить как другую партию").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, addedDifferentGame) }
    }
}
