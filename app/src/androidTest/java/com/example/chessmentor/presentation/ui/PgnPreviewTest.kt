package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
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
}
