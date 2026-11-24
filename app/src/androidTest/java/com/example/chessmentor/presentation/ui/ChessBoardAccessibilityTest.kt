package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChessBoardAccessibilityTest {
    @get:Rule val ui = createComposeRule()

    @Test fun interactiveBoardExposesOnlyOccupiedAndLegalSquaresAndSupportsFlippedMoves() {
        val board = Board()
        var selected by mutableStateOf<Square?>(null)
        var legalMoves by mutableStateOf<Set<Square>>(emptySet())
        var flipped by mutableStateOf(false)
        val clicked = mutableListOf<Square>()

        ui.setContent {
            ChessBoard(
                board = board,
                selectedSquare = selected,
                legalMoveSquares = legalMoves,
                flipped = flipped,
                onSquareClick = { sq -> clicked.add(sq) },
                modifier = Modifier.size(320.dp)
            )
        }

        ui.onNodeWithContentDescription("e2: белая пешка").assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithContentDescription("Пойти на e4").assertDoesNotExist()
        ui.onNodeWithContentDescription("c3", substring = true).assertDoesNotExist()

        ui.runOnIdle {
            assertEquals(listOf(Square.E2), clicked)
            selected = Square.E2
            legalMoves = setOf(Square.E3, Square.E4)
            flipped = true
        }

        ui.onNodeWithContentDescription("e2: белая пешка, выбрано").assertIsSelected()
        ui.onNodeWithContentDescription("Пойти на e4").assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        ui.runOnIdle {
            assertEquals(listOf(Square.E2, Square.E4), clicked)
        }
    }

    @Test fun readOnlyBoardDoesNotExposePerSquareSemantics() {
        ui.setContent {
            ChessBoard(
                board = Board(),
                onSquareClick = null,
                modifier = Modifier.size(160.dp)
            )
        }
        ui.onNodeWithContentDescription("e2: белая пешка").assertDoesNotExist()
    }
}
