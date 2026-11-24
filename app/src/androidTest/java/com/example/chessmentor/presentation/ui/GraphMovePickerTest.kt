package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.presentation.ui.components.screen.GraphMovePicker
import com.example.chessmentor.presentation.ui.components.screen.KeyMomentsCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GraphMovePickerTest {
    @get:Rule val ui = createComposeRule()

    @Test fun listOpensExactPlyAndNeverTreatsStartingEvalAsAMove() {
        val opened = mutableListOf<Int>()
        ui.setContent {
            GraphMovePicker(listOf("17... c5", "18. Nf3", "18... d6"), listOf(0, -130, 70), -1) { opened.add(it) }
        }
        ui.onNodeWithText("Выбрать ход из списка").performClick()
        ui.onNodeWithText("18... d6").assertDoesNotExist()
        ui.onNodeWithText("Текущий ход").assertDoesNotExist()
        ui.onNodeWithText("17... c5").assert(hasText("-1.3")).assertIsNotSelected()
        ui.onNodeWithText("Закрыть").performClick()
        ui.runOnIdle { assertEquals(emptyList<Int>(), opened) }
        ui.onNodeWithText("Выбрать ход из списка").performClick()
        ui.onNodeWithText("18. Nf3").assert(hasText("+0.7")).performClick()
        ui.onNodeWithText("Открыть ход на доске").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf(1), opened) }
    }

    @Test fun listMarksCurrentPlyAndSelectedStateWhenInsideGame() {
        ui.setContent {
            GraphMovePicker(listOf("17... c5", "18. Nf3"), listOf(0, -130, 70), currentMoveIndex = 1) {}
        }
        ui.onNodeWithText("Выбрать ход из списка").performClick()
        ui.onNodeWithText("Текущий ход").assertIsDisplayed()
        ui.onNodeWithText("18. Nf3").assertIsSelected()
        ui.onNodeWithText("17... c5").assertIsNotSelected()
    }

    @Test fun filteredExpandedListSurvivesTripToSelectedMoment() {
        val moments = (0..7).map { KeyMoment(it, "Nf3", MoveQuality.MISTAKE, 0, -100, -100, it != 7) }
        ui.setContent {
            val holder = rememberSaveableStateHolder()
            var opened by remember { mutableStateOf<Int?>(null) }
            if (opened == null) holder.SaveableStateProvider("summary") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KeyMomentsCard(moments, onMomentClick = { opened = it.moveIndex })
                }
            } else Button(onClick = { opened = null }) { Text("Назад с полухода $opened") }
        }
        ui.onNodeWithText("Ваши ошибки (7)").performScrollTo().performClick()
        ui.onNodeWithText("Показать все моменты (7)").performScrollTo().performClick()
        ui.onNodeWithText("4. Nf3").performScrollTo().performClick()
        ui.onNodeWithText("Назад с полухода 6").performClick()
        ui.onNodeWithText("Ваши ошибки (7)").assertIsSelected()
        ui.onNodeWithText("4. Nf3").assertExists()
        ui.onNodeWithText("4... Nf3").assertDoesNotExist()
    }
}
