package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.presentation.ui.components.EvaluationBar
import com.example.chessmentor.presentation.ui.components.EvaluationGraph
import com.example.chessmentor.presentation.ui.components.NnueManagementCard
import com.example.chessmentor.presentation.ui.components.navigation.ChessMentorBottomBar
import com.example.chessmentor.presentation.ui.components.screen.KeyMomentsCard
import com.example.chessmentor.presentation.ui.components.screen.ReviewNavigationBar
import com.example.chessmentor.presentation.ui.components.screen.TrainingHelpControls
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResponsiveLayoutAndTargetTest {
    @get:Rule val ui = createComposeRule()

    @Test fun bottomBarCapsBadgeAtNinePlusAndPreservesTouchTargets() {
        var count by mutableStateOf(14)
        ui.setContent {
            ChessMentorTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(320.dp)) {
                        ChessMentorBottomBar(
                            currentScreen = "home",
                            dueReviewCount = count,
                            onNavigate = {}
                        )
                    }
                }
            }
        }
        ui.onNodeWithText("9+").assertIsDisplayed()
        ui.onNodeWithContentDescription("Задачи").assertHeightIsAtLeast(48.dp)
        ui.runOnIdle { count = 9 }
        ui.onNodeWithText("9").assertIsDisplayed()
        ui.onNodeWithText("9+").assertDoesNotExist()
    }

    @Test fun evaluationBarFlipsAdvantageSideAndProvidesSemantics() {
        var flipped by mutableStateOf(false)
        ui.setContent {
            ChessMentorTheme {
                Box(Modifier.width(240.dp)) {
                    EvaluationBar(evaluation = 250, flipped = flipped)
                }
            }
        }
        ui.onNodeWithContentDescription("Оценка позиции: +2.5").assertIsDisplayed()
        val unflippedLeft = ui.onNodeWithText("+2.5").getUnclippedBoundsInRoot().left
        ui.runOnIdle { flipped = true }
        ui.waitForIdle()
        val flippedLeft = ui.onNodeWithText("+2.5").getUnclippedBoundsInRoot().left
        assertTrue(
            "Expected flipped white advantage text to move right ($unflippedLeft -> $flippedLeft)",
            flippedLeft > unflippedLeft
        )
    }

    @Test fun trainingHelpControlsStackWithLargeFontAtNarrowWidthAndKeep48DpHeight() {
        ui.setContent {
            ChessMentorTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(320.dp)) {
                        TrainingHelpControls(
                            state = TrainingUiState(mode = TrainingMode.Daily),
                            enabled = true,
                            onHint = {},
                            onBetterMove = {},
                            onReveal = {},
                            onSkip = {}
                        )
                    }
                }
            }
        }
        val helpNode = ui.onNodeWithText("Помощь").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val skipNode = ui.onNodeWithText("К другим задачам").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue(skipNode.getUnclippedBoundsInRoot().top >= helpNode.getUnclippedBoundsInRoot().bottom - 1.dp)
    }

    @Test fun reviewNavigationBarKeepsAllTargetsAtLeast48DpAtNarrowLargeFont() {
        ui.setContent {
            ChessMentorTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(320.dp)) {
                        ReviewNavigationBar(
                            currentMoveIndex = 114,
                            totalMoves = 120,
                            isInOverlay = false,
                            hasPreviousKeyMoment = true,
                            hasNextKeyMoment = true,
                            onGoToStart = {},
                            onPreviousKeyMoment = {},
                            onGoToPrevious = {},
                            onGoToNext = {},
                            onNextKeyMoment = {},
                            onGoToEnd = {}
                        )
                    }
                }
            }
        }
        ui.onNodeWithContentDescription("Предыдущий ход").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        ui.onNodeWithText("115/120").assertIsDisplayed()
        ui.onNodeWithContentDescription("Следующий ход").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        ui.onNodeWithContentDescription("Другие переходы").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        ui.onNodeWithText("К моменту").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun nnueManagementCardShowsModalConfirmDialogAndKeep48DpButtons() {
        var redownloads = 0
        ui.setContent {
            ChessMentorTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        NnueManagementCard(
                            status = EngineStatus.Ready(netSizeBytes = 68_000_000L),
                            onDownloadOrRetry = {},
                            onCancelDownload = {},
                            onVerifyChecksum = {},
                            onForceRedownload = { redownloads++ }
                        )
                    }
                }
            }
        }
        ui.onNodeWithText("Проверить SHA-256").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithText("Перекачать").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Перекачать нейросеть NNUE?").assertIsDisplayed()
        ui.onNodeWithText("Отмена").assertHeightIsAtLeast(48.dp).performClick()
        ui.runOnIdle { assertEquals(0, redownloads) }
    }

    @Test fun evaluationGraphAndKeyMomentsStayReadableAtNarrowLargeFont() {
        val moments = listOf(
            KeyMoment(
                moveIndex = 1,
                san = "e5",
                quality = MoveQuality.MISTAKE,
                evaluationBefore = 20,
                evaluationAfter = 180,
                evaluationChange = -160,
                isPlayerMove = true,
                bestMove = "c5",
                evalLoss = 160
            ),
            KeyMoment(
                moveIndex = 3,
                san = "Qh5",
                quality = MoveQuality.MISSED_WIN,
                evaluationBefore = 450,
                evaluationAfter = 30,
                evaluationChange = -420,
                isPlayerMove = true,
                bestMove = "Nd5",
                evalLoss = 420
            )
        )
        var openedReviewMove: Int? = null
        ui.setContent {
            ChessMentorTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.layout.Column {
                            EvaluationGraph(
                                evaluations = listOf(0, 20, 180, 450, 30),
                                keyMoments = moments,
                                currentMoveIndex = 1,
                                playerColor = ChessColor.WHITE,
                                onMoveClick = {},
                                onOpenReview = { openedReviewMove = it }
                            )
                            KeyMomentsCard(
                                keyMoments = moments,
                                onMomentClick = {}
                            )
                        }
                    }
                }
            }
        }
        ui.onNodeWithText("На доске").assertHeightIsAtLeast(48.dp).performClick()
        ui.runOnIdle { assertEquals(1, openedReviewMove) }
        ui.onNodeWithText("Зевок").assertIsDisplayed()
        ui.onNodeWithText("Ошибка").assertIsDisplayed()
        ui.onNodeWithText("Неточ.").assertIsDisplayed()
        ui.onNodeWithText("Упущ.").assertIsDisplayed()
    }
}
