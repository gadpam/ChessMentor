package com.example.chessmentor.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.screen.GameViewScreen
import com.example.chessmentor.presentation.ui.components.screen.TrainingScreen
import com.example.chessmentor.presentation.viewmodel.BoardViewModel
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NestedBackHandlerTest {
    @get:Rule
    val ui = createComposeRule()

    @Test
    fun gameViewBackExitsRetryAndVariationBeforeLeavingScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer.getInstance(context)
        val gameViewModel = GameViewModel(container)
        val boardViewModel = BoardViewModel()
        val game = Game(
            id = 77L,
            userId = 1L,
            pgnData = "1. e4 e5 2. Nf3 Nc6 *",
            playerColor = ChessColor.WHITE,
            playedAt = 1L,
            analysisStatus = AnalysisStatus.COMPLETED,
            evaluationsJson = "[20, 25, 20, -40, -35]"
        )
        val moves = listOf(
            AnalyzedMove(gameId = 77L, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE, quality = MoveQuality.GOOD, san = "e4", evalBefore = 20, evalAfter = 25, evalLoss = 0),
            AnalyzedMove(gameId = 77L, moveIndex = 1, moveNumber = 1, color = ChessColor.BLACK, quality = MoveQuality.GOOD, san = "e5", evalBefore = 25, evalAfter = 20, evalLoss = 0),
            AnalyzedMove(gameId = 77L, moveIndex = 2, moveNumber = 2, color = ChessColor.WHITE, quality = MoveQuality.MISTAKE, san = "Nf3", bestMove = "Bc4", evalBefore = 20, evalAfter = -40, evalLoss = 60),
            AnalyzedMove(gameId = 77L, moveIndex = 3, moveNumber = 2, color = ChessColor.BLACK, quality = MoveQuality.GOOD, san = "Nc6", evalBefore = -40, evalAfter = -35, evalLoss = 0)
        )
        val mistakes = listOf(
            Mistake(
                id = 1L,
                gameId = 77L,
                themeId = 1L,
                moveNumber = 2,
                color = ChessColor.WHITE,
                evaluationLoss = 60,
                mistakeType = MistakeType.MISTAKE,
                bestMove = "Bc4",
                userMove = "Nf3",
                fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
            )
        )
        val evaluations = listOf(20, 25, 20, -40, -35)

        ui.runOnUiThread {
            gameViewModel.selectedGame.value = game
            gameViewModel.selectedGameMistakes.clear()
            gameViewModel.selectedGameMistakes.addAll(mistakes)
            gameViewModel.selectedGameAnalyzedMoves.clear()
            gameViewModel.selectedGameAnalyzedMoves.addAll(moves)
            gameViewModel.selectedGameEvaluations.clear()
            gameViewModel.selectedGameEvaluations.addAll(evaluations)
            boardViewModel.loadGame(
                pgnData = game.pgnData,
                gameId = 77L,
                gameMistakes = mistakes,
                gameAnalyzedMoves = moves,
                gameEvaluations = evaluations,
                playerColor = ChessColor.WHITE
            )
        }

        val outerBackCalls = mutableIntStateOf(0)
        var pressBackAction: (() -> Unit)? = null

        ui.setContent {
            val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            pressBackAction = { dispatcher?.onBackPressed() }
            BackHandler { outerBackCalls.intValue++ }
            GameViewScreen(
                paddingValues = PaddingValues(),
                gameViewModel = gameViewModel,
                user = null,
                boardViewModel = boardViewModel,
                chessEngine = null
            )
        }

        // 1. Retry mode consumes Back without triggering outer navigation
        ui.runOnIdle {
            boardViewModel.goToMove(2)
            boardViewModel.startRetryCurrentMove()
            assertNotNull(boardViewModel.retryState.value)
        }
        ui.runOnIdle { pressBackAction?.invoke() }
        ui.runOnIdle {
            assertNull(boardViewModel.retryState.value)
            assertEquals(0, outerBackCalls.intValue)
        }

        // 2. Exploratory variation consumes Back without triggering outer navigation
        ui.runOnIdle {
            assertTrue(boardViewModel.playExploratoryMove(Move(Square.F1, Square.B5)))
            assertTrue(boardViewModel.variationSteps.isNotEmpty())
        }
        ui.runOnIdle { pressBackAction?.invoke() }
        ui.runOnIdle {
            assertTrue(boardViewModel.variationSteps.isEmpty())
            assertEquals(0, outerBackCalls.intValue)
        }

        // 3. Normal review delegates Back to the outer navigation BackHandler
        ui.runOnIdle { pressBackAction?.invoke() }
        ui.runOnIdle {
            assertEquals(1, outerBackCalls.intValue)
        }
    }

    @Test
    fun trainingBackExitsFreeAnalysisBeforeLeavingScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer.getInstance(context)
        val user = runBlocking {
            container.userRepository.save(
                User(nickname = "BackTrainer", email = "back${System.nanoTime()}@test.local", passwordHash = "test")
            )
        }
        val uid = requireNotNull(user.id)
        val outerBackCalls = mutableIntStateOf(0)
        var pressBackAction by mutableStateOf<(() -> Unit)?>(null)

        try {
            ui.setContent {
                val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                pressBackAction = { dispatcher?.onBackPressed() }
                BackHandler { outerBackCalls.intValue++ }
                TrainingScreen(
                    paddingValues = PaddingValues(),
                    container = container,
                    userId = uid,
                    onLeaveTraining = { outerBackCalls.intValue++ }
                )
            }

            ui.waitUntil(timeoutMillis = 15_000) {
                ui.onAllNodesWithText("Помощь").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("Помощь").performClick()
            ui.onNodeWithText("Показать всю линию").performClick()
            ui.onNodeWithText("Анализ").assertIsDisplayed().performClick()
            ui.onNodeWithText("К линии").assertIsDisplayed()

            ui.runOnIdle { pressBackAction?.invoke() }
            ui.onNodeWithText("Анализ").assertIsDisplayed()
            ui.runOnIdle { assertEquals(0, outerBackCalls.intValue) }

            ui.runOnIdle { pressBackAction?.invoke() }
            ui.runOnIdle { assertEquals(1, outerBackCalls.intValue) }
        } finally {
            runBlocking { container.userRepository.deleteById(uid) }
        }
    }
}
