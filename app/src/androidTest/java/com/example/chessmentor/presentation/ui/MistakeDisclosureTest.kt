package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.presentation.ui.components.CompactMistakeCard
import com.example.chessmentor.presentation.ui.components.screen.AnalyzedMoveInfoCard
import com.example.chessmentor.presentation.ui.components.screen.RetryMistakePanel
import com.example.chessmentor.presentation.ui.components.screen.ReviewTopHeaderBar
import com.example.chessmentor.presentation.viewmodel.RetryMistakeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MistakeDisclosureTest {
    @get:Rule val ui = createComposeRule()

    @Test fun savedMistakeSeparatesRetryAnswerAndEngine() = checkCard(savedMistake = true)
    @Test fun analyzedMoveSeparatesRetryAnswerAndEngine() = checkCard(savedMistake = false)

    @Test fun structuredCommentShowsReasonBeforeRevealAndSplitsBetterVsRefutationAfterReveal() {
        val structuredComment = "Зевок вилки: теряется ладья. Сильнее: 1. Nf3 Nc6. Опровержение: 1... Qa5+ 2. Bd2 Qxa1."
        ui.setContent {
            var shown by remember { mutableStateOf(false) }
            val reveal: (() -> Unit)? = if (shown) null else ({ shown = true })
            Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                CompactMistakeCard(
                    Mistake(gameId = 1, themeId = 1, moveNumber = 1, color = ChessColor.WHITE,
                        evaluationLoss = 450, mistakeType = MistakeType.BLUNDER,
                        bestMove = "Nf3", userMove = "a3", comment = structuredComment),
                    onRevealAnswer = reveal,
                    onRetryMove = {},
                    onShowEngineLine = {}
                )
            }
        }
        // До раскрытия видна только безспойлерная причина, но не «Сильнее»/«Опровержение» и не ответ
        ui.onNodeWithText("Зевок вилки: теряется ладья.").assertIsDisplayed()
        ui.onNodeWithText("Nf3").assertDoesNotExist()
        ui.onNodeWithText("Сильнее").assertDoesNotExist()
        ui.onNodeWithText("Опровержение").assertDoesNotExist()

        ui.onNodeWithText("Показать лучший ход").performScrollTo().performClick()
        ui.onNodeWithText("Nf3").assertExists()
        ui.onNodeWithText("Сильнее").assertExists()
        ui.onNodeWithText("1. Nf3 Nc6.").assertExists()
        ui.onNodeWithText("Опровержение").assertExists()
        ui.onNodeWithText("1... Qa5+ 2. Bd2 Qxa1.").assertExists()
    }

    private fun checkCard(savedMistake: Boolean) {
        val calls = mutableListOf<String>()
        val comment = "Сыграйте Nf3 вместо хода в партии"
        ui.setContent {
            var shown by remember { mutableStateOf(false) }
            val reveal: (() -> Unit)? = if (shown) null else ({ shown = true })
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    if (savedMistake) {
                        CompactMistakeCard(
                            Mistake(gameId = 1, themeId = 1, moveNumber = 1, color = ChessColor.WHITE,
                                evaluationLoss = 180, mistakeType = MistakeType.MISTAKE,
                                bestMove = "Nf3", userMove = "a3", comment = comment),
                            onRevealAnswer = reveal, onRetryMove = { calls.add("retry") },
                            onShowEngineLine = { calls.add("engine") }, onPracticeInTrainer = { calls.add("trainer") })
                    } else {
                        AnalyzedMoveInfoCard(
                            AnalyzedMove(gameId = 1, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE,
                                quality = MoveQuality.MISTAKE, san = "a3", bestMove = "Nf3", evalBefore = 0,
                                evalAfter = -180, evalLoss = 180, comment = comment),
                            true, null, null, reveal, { calls.add("retry") }, { calls.add("engine") }, null)
                    }
                }
            }
        }
        val answer = if (savedMistake) "Nf3" else "Лучше: Nf3"
        ui.onNodeWithText(answer).assertDoesNotExist()
        ui.onNodeWithText(comment).assertDoesNotExist()
        ui.onNodeWithText("Лучший вариант").assertDoesNotExist()
        ui.onNodeWithText("Попробовать снова").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText(answer).assertDoesNotExist()
        ui.onNodeWithText("Показать лучший ход").performScrollTo().performClick()
        ui.onNodeWithText(answer).assertExists()
        ui.onNodeWithText(comment).assertExists()
        ui.runOnIdle { assertEquals(listOf("retry"), calls) }
        ui.onNodeWithText("Лучший вариант").performScrollTo().performClick()
        if (savedMistake) ui.onNodeWithText("В тренажёр").performScrollTo().performClick()
        else ui.onNodeWithText("В тренажёр").assertDoesNotExist()
        ui.runOnIdle { assertEquals(if (savedMistake) listOf("retry", "engine", "trainer") else listOf("retry", "engine"), calls) }
    }

    @Test fun solvedRetryPanelOffersSaveToRepetitionWithAssistedDisclosure() {
        var saveClicked = false
        ui.setContent {
            Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                RetryMistakePanel(
                    state = RetryMistakeUiState(
                        targetMoveIndex = 4,
                        moveNumber = 3,
                        moverColor = ChessColor.WHITE,
                        isPlayerMistake = true,
                        fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                        playedSan = "Qh5",
                        expectedBestRaw = "Nf3",
                        expectedBestSan = "Nf3",
                        hintLevel = 1,
                        isSolved = true,
                        attemptsCount = 1,
                        feedback = "Верно!",
                        answerRevealedBeforeRetry = true
                    ),
                    onHint = {},
                    onShowLine = null,
                    onPracticeInTrainer = null,
                    onContinueGame = {},
                    onCancel = {},
                    onSaveToRepetition = { saveClicked = true }
                )
            }
        }
        ui.onNodeWithText("без зачёта самостоятельности", substring = true).assertIsDisplayed()
        ui.onNodeWithText("Сохранить к повторению").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(saveClicked)
    }

    @Test fun opponentMistakeCardUsesAnswerActionAndTitle() {
        ui.setContent {
            Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                AnalyzedMoveInfoCard(
                    analyzedMove = AnalyzedMove(
                        gameId = 1,
                        moveIndex = 4,
                        moveNumber = 3,
                        color = ChessColor.WHITE,
                        quality = MoveQuality.BLUNDER,
                        san = "Bxf7+",
                        bestMove = "Nf3",
                        evalBefore = 10,
                        evalAfter = -340,
                        evalLoss = 350,
                        comment = "Зевок фигуры. Сильнее: 3. Nf3. Опровержение: 3... Kxf7."
                    ),
                    isPlayerMove = false,
                    fenBefore = null,
                    clockSeconds = null,
                    onRevealAnswer = {},
                    onRetryMove = {},
                    onShowEngineLine = {},
                    onPracticeInTrainer = null
                )
            }
        }
        ui.onNodeWithText("Найти ответ").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Попробовать снова").assertDoesNotExist()
        ui.onNodeWithText("Зевок соперника").assertExists()
    }

    @Test fun opponentRetryPanelShowsAnswerCopyBeforeAndAfterSolve() {
        var state by mutableStateOf(
            RetryMistakeUiState(
                targetMoveIndex = 4,
                moveNumber = 3,
                moverColor = ChessColor.WHITE,
                isPlayerMistake = false,
                fenBefore = "rnbqkb1r/pppp1Bpp/5n2/4p3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 0 3",
                playedSan = "Bxf7+",
                expectedBestRaw = "e8f7",
                expectedBestSan = "Kxf7",
                feedback = "Соперник ошибся ходом Bxf7+. Найдите лучший ответ за чёрных!"
            )
        )
        ui.setContent {
            Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                RetryMistakePanel(
                    state = state,
                    onHint = {},
                    onShowLine = null,
                    onPracticeInTrainer = null,
                    onContinueGame = {},
                    onCancel = {}
                )
            }
        }
        ui.onNodeWithText("Ответ на ошибку (3)").assertIsDisplayed()
        ui.onNodeWithText("Исправьте ход 3").assertDoesNotExist()
        ui.runOnIdle { state = state.copy(isSolved = true, feedback = "Верно!") }
        ui.onNodeWithText("Ответ на 3-м ходу найден!").assertIsDisplayed()
    }

    @Test fun solvedRetryPanelShowsVerifyOnAnotherPositionWithAccessibleTouchTarget() {
        var verifyClicks = 0
        var state by mutableStateOf(
            RetryMistakeUiState(
                targetMoveIndex = 4,
                moveNumber = 3,
                moverColor = ChessColor.WHITE,
                isPlayerMistake = true,
                fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                playedSan = "Qh5",
                expectedBestRaw = "Nf3",
                expectedBestSan = "Nf3",
                isSolved = false,
                feedback = "Найдите сильнейший ход"
            )
        )
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    RetryMistakePanel(
                        state = state,
                        onHint = {},
                        onShowLine = null,
                        onPracticeInTrainer = null,
                        onContinueGame = {},
                        onCancel = {},
                        onVerifyOnAnotherPosition = { verifyClicks++ }
                    )
                }
            }
        }
        // До решения кнопка переноса навыка скрыта
        ui.onNodeWithText("Проверить на другой позиции").assertDoesNotExist()

        ui.runOnIdle { state = state.copy(isSolved = true, feedback = "Верно!") }
        ui.onNodeWithText("Проверить на другой позиции")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
            .performClick()
        ui.runOnIdle { assertEquals(1, verifyClicks) }
    }

    @Test fun reviewTopHeaderBar_hidesStockfishToggleWhenConcealed() {
        var concealed by mutableStateOf(true)
        ui.setContent {
            ReviewTopHeaderBar(
                isLiveEngineEnabled = false,
                showEngineToggle = !concealed,
                onNavigateBack = {},
                onFlipBoard = {},
                onToggleEngine = {},
                onOpenSummary = null
            )
        }
        ui.onNodeWithContentDescription("Анализ Stockfish").assertDoesNotExist()
        ui.runOnIdle { concealed = false }
        ui.onNodeWithContentDescription("Анализ Stockfish").assertIsDisplayed()
    }
}
