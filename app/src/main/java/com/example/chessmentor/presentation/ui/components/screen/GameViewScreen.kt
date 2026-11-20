package com.example.chessmentor.presentation.ui.components.screen

import android.util.Log
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.*
import com.example.chessmentor.presentation.viewmodel.BoardViewModel
import com.example.chessmentor.presentation.viewmodel.EngineLineUiState
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.example.chessmentor.presentation.viewmodel.RetryMistakeUiState
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val TAG = "GameViewScreen"

@Composable
fun GameViewScreen(
    paddingValues: PaddingValues,
    gameViewModel: GameViewModel,
    user: User?,
    boardViewModel: BoardViewModel = viewModel(),
    chessEngine: ChessEngine? = null,
    onOpenSummary: (() -> Unit)? = null,
    onPracticeMistake: ((gameId: Long, moveNumber: Int) -> Unit)? = null
) {
    val game by gameViewModel.selectedGame

    if (game == null) {
        NoGameSelectedPlaceholder(paddingValues = paddingValues)
        return
    }

    key(game!!.id) {
        GameViewScreenContent(
            paddingValues = paddingValues,
            game = game!!,
            gameViewModel = gameViewModel,
            boardViewModel = boardViewModel,
            user = user,
            chessEngine = chessEngine,
            onOpenSummary = onOpenSummary,
            onPracticeMistake = onPracticeMistake
        )
    }
}

@Composable
private fun NoGameSelectedPlaceholder(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Игра не выбрана",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GameViewScreenContent(
    paddingValues: PaddingValues,
    game: Game,
    gameViewModel: GameViewModel,
    boardViewModel: BoardViewModel,
    user: User?,
    chessEngine: ChessEngine?,
    onOpenSummary: (() -> Unit)?,
    onPracticeMistake: ((gameId: Long, moveNumber: Int) -> Unit)?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mistakes = gameViewModel.selectedGameMistakes
    val analyzedMoves = gameViewModel.selectedGameAnalyzedMoves
    val gameEvaluations = gameViewModel.selectedGameEvaluations
    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    val isBackgroundAnalyzing = analysisProgress != null

    val soundManager = remember(context) {
        SoundManager(context.applicationContext)
    }

    LaunchedEffect(user?.isSoundEnabled) {
        soundManager.setEnabled(user?.isSoundEnabled ?: true)
    }

    DisposableEffect(soundManager) {
        boardViewModel.soundManager = soundManager
        onDispose {
            Log.d(TAG, "Releasing SoundManager")
            if (boardViewModel.soundManager === soundManager) {
                boardViewModel.soundManager = null
            }
            soundManager.release()
        }
    }

    val selectedTheme = remember(user?.preferredTheme) {
        BoardThemes.getByName(user?.preferredTheme)
    }

    val cardInfo = remember(game.id, game.pgnData, game.playerColor, game.timeControl) {
        PgnParser.extractGameCardInfo(game)
    }

    LaunchedEffect(game.id, game.analyzedAt, mistakes.size, analyzedMoves.size, gameEvaluations.size) {
        boardViewModel.loadGame(
            game = game,
            gameMistakes = mistakes.toList(),
            gameAnalyzedMoves = analyzedMoves.toList(),
            gameEvaluations = gameEvaluations.toList(),
            preserveMoveIndex = true
        )
    }

    val board by boardViewModel.board
    val currentMoveIndex by boardViewModel.currentMoveIndex
    val highlightedSquares by boardViewModel.highlightedSquares
    val lastMove by boardViewModel.lastMove
    val isFlipped by boardViewModel.isBoardFlipped
    val retryState by boardViewModel.retryState
    val isLiveEngine by boardViewModel.isLiveEngineEnabled
    val engineLine by boardViewModel.engineLineState
    val variationSteps = boardViewModel.variationSteps.toList()
    val isInVariation = variationSteps.isNotEmpty()

    var selectedSquare by remember { mutableStateOf<Square?>(null) }
    var pendingPromotion by remember { mutableStateOf<Pair<Square, Square>?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    // Сбрасываем выделение клетки при любом изменении позиции на доске
    LaunchedEffect(board.fen, retryState?.targetMoveIndex, retryState?.isSolved, isInVariation) {
        selectedSquare = null
        pendingPromotion = null
    }

    val currentEvaluation = boardViewModel.getCurrentEvaluation()
    val currentMistake = boardViewModel.getCurrentMistake()
    val currentAnalyzedMove = boardViewModel.getCurrentAnalyzedMove()
    val canRetry = boardViewModel.canRetryCurrentMove()
    var answerRevealed by remember(game.id, game.pgnData, currentMoveIndex, retryState != null) { mutableStateOf(false) }
    val concealAnswer = canRetry && !answerRevealed
    val revealAnswer: (() -> Unit)? = if (concealAnswer) ({ answerRevealed = true }) else null
    val displayedMoves = boardViewModel.sanMoves.toList()
    val totalMoves = boardViewModel.getTotalMoves()
    val hasRealEvaluations = boardViewModel.hasRealEvaluations() || engineLine?.evalWhiteCp != null
    val currentMoveClock = boardViewModel.getCurrentMoveClock()
    val fenBeforeCurrent = remember(currentMoveIndex, totalMoves) {
        boardViewModel.getFenBeforeMove(currentMoveIndex)
    }

    // Легальные ходы выбранной фигуры (для исправления ошибки и свободного анализа вариантов)
    val canMoveOnBoard = remember(retryState) {
        val r = retryState
        r == null || (!r.isSolved && !r.isCheckingEngine)
    }
    val (legalMoveSquares, legalCaptureSquares) = remember(board.fen, selectedSquare, canMoveOnBoard) {
        val from = selectedSquare
        if (!canMoveOnBoard || from == null) {
            emptySet<Square>() to emptySet<Square>()
        } else {
            val movingPiece = board.getPiece(from)
            val legal = runCatching { board.legalMoves() }.getOrDefault(emptyList())
                .filter { it.from == from }
            val quiet = mutableSetOf<Square>()
            val captures = mutableSetOf<Square>()
            for (m in legal) {
                val targetPiece = board.getPiece(m.to)
                val isEp = movingPiece.pieceType == PieceType.PAWN &&
                    m.from.file != m.to.file &&
                    targetPiece == Piece.NONE
                if (targetPiece != Piece.NONE || isEp) captures.add(m.to) else quiet.add(m.to)
            }
            quiet to captures
        }
    }

    // Стрелки на доске:
    // 1) в режиме «Исправить» — только явная подсказка 2-го уровня (без спойлеров);
    // 2) при показе линии Stockfish — синяя стрелка хода движка;
    // 3) на ходе с ошибкой — коралловая стрелка сыгранного хода + зелёная стрелка лучшего хода.
    val arrowsList = remember(
        currentMoveIndex,
        currentAnalyzedMove,
        currentMistake,
        retryState,
        engineLine,
        isInVariation,
        fenBeforeCurrent,
        concealAnswer
    ) {
        val activeRetry = retryState
        if (activeRetry != null) {
            val hintUci = activeRetry.hintArrowUci
            val arrow = if (!hintUci.isNullOrBlank()) {
                parseMoveToArrow(hintUci, activeRetry.fenBefore, Color(0xCC43A047))
            } else {
                null
            }
            return@remember listOfNotNull(arrow)
        }

        val liveLine = engineLine
        if (liveLine != null && !liveLine.bestMoveUci.isNullOrBlank()) {
            val arrow = parseMoveToArrow(
                moveNotation = liveLine.bestMoveUci,
                fenBefore = liveLine.analyzedFen,
                color = Color(0xCC1E88E5)
            )
            if (arrow != null) {
                return@remember listOf(arrow)
            }
        }

        if (isInVariation || concealAnswer) return@remember emptyList()

        val bestRaw = currentAnalyzedMove?.bestMove?.takeIf {
            currentAnalyzedMove.isMistake() && it.isNotBlank() && it != currentAnalyzedMove.san
        } ?: currentMistake?.bestMove?.takeIf {
            it.isNotBlank() && it != currentMistake.userMove
        }

        if (bestRaw != null) {
            val bestArrow = parseMoveToArrow(bestRaw, fenBeforeCurrent, Color(0xCC43A047))
            val playedSquares = boardViewModel.getPlayedMoveSquares(currentMoveIndex)
            val playedArrow = playedSquares?.takeIf { bestArrow == null || it != (bestArrow.from to bestArrow.to) }
                ?.let { (from, to) -> BoardArrow(from, to, Color(0x88E53935)) }
            listOfNotNull(playedArrow, bestArrow)
        } else {
            emptyList()
        }
    }

    fun playFeedbackSoundForCurrentPosition(isCaptureHint: Boolean = false) {
        val currentBoard = boardViewModel.board.value
        val idx = boardViewModel.currentMoveIndex.intValue
        val san = if (boardViewModel.isInVariation()) {
            boardViewModel.variationSteps.lastOrNull()?.san.orEmpty()
        } else {
            displayedMoves.getOrNull(idx).orEmpty()
        }
        coroutineScope.launch {
            when {
                currentBoard.isMated -> soundManager.playCheckmate()
                currentBoard.isKingAttacked -> soundManager.playCheck()
                isCaptureHint || 'x' in san -> soundManager.playCapture()
                idx >= 0 || boardViewModel.isInVariation() -> soundManager.playMove()
            }
        }
    }

    fun handleUserBoardMove(from: Square, to: Square, promotion: PieceType?) {
        val move = ChessMoveUtils.createMove(from, to, board.sideToMove, promotion)
        val wasCapture = board.getPiece(to) != Piece.NONE ||
            (board.getPiece(from).pieceType == PieceType.PAWN && from.file != to.file)

        if (retryState != null) {
            val verdict = boardViewModel.submitRetryMove(
                move = move,
                engine = chessEngine,
                isEngineBusy = isBackgroundAnalyzing
            )
            coroutineScope.launch {
                when (verdict) {
                    GameReviewHelper.RetryVerdict.EXACT_BEST -> soundManager.playSuccess()
                    GameReviewHelper.RetryVerdict.SAME_AS_GAME_MISTAKE,
                    GameReviewHelper.RetryVerdict.ALTERNATIVE_LEGAL -> {
                        if (boardViewModel.retryState.value?.isCheckingEngine != true) {
                            soundManager.playError()
                            repeat(2) {
                                shakeOffset.animateTo(8f, tween(40))
                                shakeOffset.animateTo(-8f, tween(40))
                            }
                            shakeOffset.animateTo(0f, tween(40))
                        }
                    }
                    GameReviewHelper.RetryVerdict.ILLEGAL -> {}
                }
            }
        } else {
            val moved = boardViewModel.playExploratoryMove(
                move = move,
                engine = chessEngine,
                isEngineBusy = isBackgroundAnalyzing
            )
            if (moved) {
                playFeedbackSoundForCurrentPosition(isCaptureHint = wasCapture)
            }
        }
    }

    // Данные игроков и часов сверху и снизу доски (с учётом переворота)
    val topColor = if (isFlipped) ChessColor.WHITE else ChessColor.BLACK
    val bottomColor = topColor.opposite()
    val topLabel = (if (topColor == ChessColor.WHITE) cardInfo.whiteDisplay else cardInfo.blackDisplay)
        ?: if (topColor == ChessColor.WHITE) "Белые" else "Чёрные"
    val bottomLabel = (if (bottomColor == ChessColor.WHITE) cardInfo.whiteDisplay else cardInfo.blackDisplay)
        ?: if (bottomColor == ChessColor.WHITE) "Белые" else "Чёрные"
    val topClock = boardViewModel.getClockForSide(topColor)
    val bottomClock = boardViewModel.getClockForSide(bottomColor)
    val matDiffWhite = remember(board.fen) { GameReviewHelper.materialDiffWhitePawns(board.fen) }
    val topMaterialAdv = if (topColor == ChessColor.WHITE) matDiffWhite else -matDiffWhite
    val bottomMaterialAdv = -topMaterialAdv

    val hasPrevMoment = remember(currentMoveIndex, analyzedMoves.size, mistakes.size) {
        boardViewModel.hasPreviousKeyMoment()
    }
    val hasNextMoment = remember(currentMoveIndex, analyzedMoves.size, mistakes.size) {
        boardViewModel.hasNextKeyMoment()
    }

    val openingAtMove = remember(currentMoveIndex, totalMoves, cardInfo.openingLabel) {
        boardViewModel.getOpeningLabelAtCurrentMove() ?: if (currentMoveIndex < 0) cardInfo.openingLabel else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Верхняя полоска игрока + часы + кнопки (переворот, Stockfish, Сводка)
        ReviewPlayerToolbar(
            color = topColor,
            nameLabel = topLabel,
            materialAdvantage = topMaterialAdv,
            clockSeconds = topClock,
            isLiveEngineEnabled = isLiveEngine,
            showEngineToggle = chessEngine != null,
            onFlipBoard = { boardViewModel.toggleFlipBoard() },
            onToggleEngine = {
                chessEngine?.let {
                    boardViewModel.toggleLiveEngine(it, isEngineBusy = isBackgroundAnalyzing)
                }
            },
            onOpenSummary = onOpenSummary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // 2. Шкала оценки
        EvaluationBar(
            evaluation = currentEvaluation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // 3. Интерактивная шахматная доска (с бейджем качества хода и двойными стрелками)
        GameBoardWithBadgeAndArrow(
            board = board,
            currentMistake = currentMistake,
            currentAnalyzedMove = currentAnalyzedMove,
            showQualityBadge = retryState == null && !isInVariation,
            highlightedSquares = highlightedSquares,
            selectedSquare = selectedSquare,
            legalMoveSquares = legalMoveSquares,
            legalCaptureSquares = legalCaptureSquares,
            lastMove = lastMove,
            arrows = arrowsList,
            isFlipped = isFlipped,
            theme = selectedTheme,
            onSquareClick = if (canMoveOnBoard) { clicked ->
                val from = selectedSquare
                val piece = board.getPiece(clicked)
                when {
                    from == null -> {
                        if (piece != Piece.NONE && piece.pieceSide == board.sideToMove) {
                            selectedSquare = clicked
                        }
                    }
                    clicked == from -> selectedSquare = null
                    clicked in legalMoveSquares || clicked in legalCaptureSquares -> {
                        if (ChessMoveUtils.isPawnPromotion(board, from, clicked)) {
                            pendingPromotion = from to clicked
                        } else {
                            selectedSquare = null
                            handleUserBoardMove(from, clicked, promotion = null)
                        }
                    }
                    piece != Piece.NONE && piece.pieceSide == board.sideToMove -> {
                        selectedSquare = clicked
                    }
                    else -> selectedSquare = null
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .aspectRatio(1f)
                .graphicsLayer { translationX = shakeOffset.value }
        )

        // 4. Нижняя полоска игрока + часы + индикатор режима (Ветка / Исправление)
        ReviewBottomPlayerStrip(
            color = bottomColor,
            nameLabel = bottomLabel,
            materialAdvantage = bottomMaterialAdv,
            clockSeconds = bottomClock,
            isRetryMode = retryState != null,
            variationDepth = variationSteps.size,
            hasRealEvaluations = hasRealEvaluations,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
        )

        // 5. Адаптивная тренерская панель (прокручиваемая, никогда не обрезает комментарии)
        val panelScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(panelScroll)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val activeRetry = retryState
            val gameId = game.id
            when {
                activeRetry != null -> {
                    RetryMistakePanel(
                        state = activeRetry,
                        onHint = { boardViewModel.requestRetryHint() },
                        onShowLine = if (chessEngine != null && activeRetry.isSolved) {
                            {
                                val idx = activeRetry.targetMoveIndex
                                boardViewModel.exitRetryMode(advanceToNext = false)
                                boardViewModel.goToMove(idx)
                                boardViewModel.analyzeBestAlternativeForCurrentMove(
                                    chessEngine,
                                    isEngineBusy = isBackgroundAnalyzing
                                )
                            }
                        } else {
                            null
                        },
                        onPracticeInTrainer = if (activeRetry.isPlayerMistake && gameId != null && onPracticeMistake != null) {
                            { onPracticeMistake(gameId, activeRetry.moveNumber) }
                        } else {
                            null
                        },
                        onContinueGame = {
                            boardViewModel.exitRetryMode(advanceToNext = true)
                            playFeedbackSoundForCurrentPosition()
                        },
                        onCancel = {
                            boardViewModel.exitRetryMode(advanceToNext = false)
                        }
                    )
                }

                isInVariation -> {
                    VariationSandboxPanel(
                        steps = variationSteps,
                        onStepBack = {
                            boardViewModel.stepBackVariation()
                            playFeedbackSoundForCurrentPosition()
                        },
                        onExitVariation = {
                            boardViewModel.exitVariation()
                        },
                        onAnalyzeWithEngine = if (chessEngine != null && engineLine == null) {
                            { boardViewModel.toggleLiveEngine(chessEngine, isBackgroundAnalyzing) }
                        } else {
                            null
                        }
                    )
                }

                currentMistake != null -> {
                    val isPlayerMistake = currentMistake.color == game.playerColor
                    CompactMistakeCard(
                        mistake = currentMistake,
                        moveQuality = currentAnalyzedMove?.quality,
                        fenBefore = fenBeforeCurrent,
                        clockSeconds = currentMoveClock,
                        onRevealAnswer = revealAnswer,
                        onRetryMove = if (canRetry) {
                            { boardViewModel.startRetryCurrentMove() }
                        } else {
                            null
                        },
                        onShowEngineLine = if (chessEngine != null) {
                            {
                                boardViewModel.analyzeBestAlternativeForCurrentMove(
                                    chessEngine,
                                    isEngineBusy = isBackgroundAnalyzing
                                )
                            }
                        } else {
                            null
                        },
                        onPracticeInTrainer = if (isPlayerMistake && gameId != null && onPracticeMistake != null) {
                            { onPracticeMistake(gameId, currentMistake.moveNumber) }
                        } else {
                            null
                        }
                    )
                }

                currentAnalyzedMove != null -> {
                    val isPlayerMove = currentAnalyzedMove.color == game.playerColor
                    AnalyzedMoveInfoCard(
                        analyzedMove = currentAnalyzedMove,
                        isPlayerMove = isPlayerMove,
                        fenBefore = fenBeforeCurrent,
                        clockSeconds = currentMoveClock,
                        onRevealAnswer = revealAnswer,
                        onRetryMove = if (canRetry) {
                            { boardViewModel.startRetryCurrentMove() }
                        } else {
                            null
                        },
                        onShowEngineLine = if (chessEngine != null && currentAnalyzedMove.isMistake()) {
                            {
                                boardViewModel.analyzeBestAlternativeForCurrentMove(
                                    chessEngine,
                                    isEngineBusy = isBackgroundAnalyzing
                                )
                            }
                        } else {
                            null
                        },
                        onPracticeInTrainer = if (
                            isPlayerMove &&
                            currentAnalyzedMove.isMistake() &&
                            gameId != null &&
                            onPracticeMistake != null
                        ) {
                            { onPracticeMistake(gameId, currentAnalyzedMove.moveNumber) }
                        } else {
                            null
                        }
                    )
                }

                else -> {
                    CompactPositionCard(
                        evaluation = currentEvaluation,
                        playerColor = game.playerColor,
                        openingLabel = openingAtMove,
                        subtitle = if (!game.isAnalyzed()) {
                            "Сделайте ход на доске для анализа варианта или запустите разбор Stockfish"
                        } else {
                            "Нажмите на фигуру, чтобы разыграть свой вариант"
                        },
                        onAnalyzePosition = when {
                            !game.isAnalyzed() && gameId != null -> {
                                { gameViewModel.analyzeGameWithProgress(gameId, forceReanalyze = true) }
                            }
                            chessEngine != null && engineLine == null -> {
                                { boardViewModel.toggleLiveEngine(chessEngine, isBackgroundAnalyzing) }
                            }
                            else -> null
                        }
                    )
                }
            }

            // Полоска живой линии Stockfish с кнопкой пошагового разыгрывания на доске
            val activeEngineLine = engineLine
            if (activeEngineLine != null && activeRetry == null) {
                EngineLineCard(
                    state = activeEngineLine,
                    onPlayBestMove = if (chessEngine != null && activeEngineLine.pvSteps.isNotEmpty()) {
                        {
                            if (boardViewModel.playEngineBestStep(chessEngine, isBackgroundAnalyzing)) {
                                playFeedbackSoundForCurrentPosition()
                            }
                        }
                    } else {
                        null
                    },
                    onClose = { boardViewModel.dismissEngineLine() }
                )
            }
        }

        // 6. Горизонтальная лента ходов с цветовой индикацией качества
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            HorizontalMoveList(
                moves = displayedMoves,
                currentMoveIndex = currentMoveIndex,
                mistakes = mistakes.toList(),
                analyzedMoves = analyzedMoves.toList(),
                startMoveNumber = boardViewModel.getStartMoveNumber(),
                firstMoveIsWhite = boardViewModel.isFirstMoveWhite(),
                onMoveClick = { index ->
                    boardViewModel.goToMove(index)
                    playFeedbackSoundForCurrentPosition()
                },
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .height(34.dp)
            )
        }

        // 7. Навигация: Начало · Пред. ключевой момент · Назад · Счётчик · Вперёд · След. момент · Конец
        ReviewNavigationBar(
            currentMoveIndex = currentMoveIndex,
            totalMoves = totalMoves,
            isInOverlay = isInVariation || retryState != null,
            hasPreviousKeyMoment = hasPrevMoment,
            hasNextKeyMoment = hasNextMoment,
            onGoToStart = { boardViewModel.goToStart() },
            onPreviousKeyMoment = {
                if (boardViewModel.goToPreviousKeyMoment()) {
                    playFeedbackSoundForCurrentPosition()
                }
            },
            onGoToPrevious = {
                boardViewModel.goToPreviousMove()
                playFeedbackSoundForCurrentPosition()
            },
            onGoToNext = {
                boardViewModel.goToNextMove()
                playFeedbackSoundForCurrentPosition()
            },
            onNextKeyMoment = {
                if (boardViewModel.goToNextKeyMoment()) {
                    playFeedbackSoundForCurrentPosition()
                }
            },
            onGoToEnd = {
                boardViewModel.goToEnd()
                playFeedbackSoundForCurrentPosition()
            }
        )
    }

    // Диалог выбора фигуры при превращении пешки (в режиме «Исправить» и в аналитической ветке)
    pendingPromotion?.let { (from, to) ->
        ReviewPromotionDialog(
            onSelect = { pieceType ->
                pendingPromotion = null
                selectedSquare = null
                handleUserBoardMove(from, to, promotion = pieceType)
            },
            onDismiss = { pendingPromotion = null }
        )
    }

    AnalysisProgressDialog(
        progress = analysisProgress,
        onDismiss = { gameViewModel.clearAnalysisProgress() },
        onCancel = { gameViewModel.cancelAnalysis() }
    )
}

@Composable
private fun ReviewPlayerToolbar(
    color: ChessColor,
    nameLabel: String,
    materialAdvantage: Int,
    clockSeconds: Int?,
    isLiveEngineEnabled: Boolean,
    showEngineToggle: Boolean,
    onFlipBoard: () -> Unit,
    onToggleEngine: () -> Unit,
    onOpenSummary: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PlayerInfoBadge(
            color = color,
            nameLabel = nameLabel,
            materialAdvantage = materialAdvantage,
            clockSeconds = clockSeconds,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onFlipBoard,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = "Перевернуть доску",
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showEngineToggle) {
                IconButton(
                    onClick = onToggleEngine,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Анализ Stockfish",
                        modifier = Modifier.size(19.dp),
                        tint = if (isLiveEngineEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            if (onOpenSummary != null) {
                IconButton(
                    onClick = onOpenSummary,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = "К сводке анализа",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewBottomPlayerStrip(
    color: ChessColor,
    nameLabel: String,
    materialAdvantage: Int,
    clockSeconds: Int?,
    isRetryMode: Boolean,
    variationDepth: Int,
    hasRealEvaluations: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PlayerInfoBadge(
            color = color,
            nameLabel = nameLabel,
            materialAdvantage = materialAdvantage,
            clockSeconds = clockSeconds,
            modifier = Modifier.weight(1f)
        )

        val statusText = when {
            isRetryMode -> "Исправление"
            variationDepth > 0 -> "Ветка (+$variationDepth)"
            !hasRealEvaluations -> "Без анализа"
            else -> null
        }
        if (statusText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayerInfoBadge(
    color: ChessColor,
    nameLabel: String,
    materialAdvantage: Int,
    clockSeconds: Int?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (color == ChessColor.WHITE) Color.White else Color(0xFF1E1E1E),
                    RoundedCornerShape(3.dp)
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
        )
        Text(
            text = nameLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (materialAdvantage > 0) {
            Text(
                text = "+$materialAdvantage",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (clockSeconds != null) {
            val trouble = GameReviewHelper.isTimeTrouble(clockSeconds)
            val low = GameReviewHelper.isLowTime(clockSeconds)
            val bg = when {
                trouble -> LocalChessPalette.current.negative.copy(alpha = 0.16f)
                low -> LocalChessPalette.current.caution.copy(alpha = 0.16f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val fg = when {
                trouble -> LocalChessPalette.current.negative
                low -> LocalChessPalette.current.caution
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = bg
            ) {
                Text(
                    text = GameReviewHelper.formatClock(clockSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun GameBoardWithBadgeAndArrow(
    board: Board,
    currentMistake: Mistake?,
    currentAnalyzedMove: AnalyzedMove?,
    showQualityBadge: Boolean,
    highlightedSquares: Set<Square>,
    selectedSquare: Square?,
    legalMoveSquares: Set<Square>,
    legalCaptureSquares: Set<Square>,
    lastMove: Pair<Square, Square>?,
    arrows: List<BoardArrow>,
    isFlipped: Boolean,
    theme: BoardTheme,
    onSquareClick: ((Square) -> Unit)?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val boardSize = this.maxWidth

        ChessBoard(
            board = board,
            highlightedSquares = highlightedSquares,
            selectedSquare = selectedSquare,
            legalMoveSquares = legalMoveSquares,
            legalCaptureSquares = legalCaptureSquares,
            arrows = arrows,
            lastMove = lastMove,
            modifier = Modifier.fillMaxSize(),
            flipped = isFlipped,
            theme = theme,
            animateMove = true,
            onSquareClick = onSquareClick
        )

        if (showQualityBadge && lastMove != null) {
            val quality = currentAnalyzedMove?.quality
            val showForQuality = quality != null && quality in listOf(
                MoveQuality.BRILLIANT,
                MoveQuality.GREAT_MOVE,
                MoveQuality.BEST_MOVE,
                MoveQuality.BOOK,
                MoveQuality.BLUNDER,
                MoveQuality.MISTAKE,
                MoveQuality.INACCURACY,
                MoveQuality.MISSED_WIN
            )
            if (showForQuality || currentMistake != null) {
                val badgeOffset = calculateBadgeOffset(lastMove.second, isFlipped, boardSize)
                MoveQualityBadge(
                    mistakeType = currentMistake?.mistakeType,
                    moveQuality = quality,
                    size = 26.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = badgeOffset.first, y = badgeOffset.second)
                )
            }
        }
    }
}

private fun calculateBadgeOffset(square: Square, isFlipped: Boolean, boardSize: Dp): Pair<Dp, Dp> {
    val file = square.file.ordinal
    val rank = square.rank.ordinal
    val (dFile, dRank) = if (isFlipped) Pair(7 - file, rank) else Pair(file, 7 - rank)
    val sqSize = boardSize / 8
    val badgeSize = 26.dp
    return Pair(sqSize * dFile + sqSize - badgeSize - 2.dp, sqSize * dRank + 2.dp)
}

@Composable
internal fun RetryMistakePanel(
    state: RetryMistakeUiState,
    onHint: () -> Unit,
    onShowLine: (() -> Unit)?,
    onPracticeInTrainer: (() -> Unit)?,
    onContinueGame: () -> Unit,
    onCancel: () -> Unit
) {
    val containerColor = if (state.isSolved) {
        LocalChessPalette.current.positive.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    }
    val borderColor = if (state.isSolved) {
        LocalChessPalette.current.positive.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.isSolved) {
                        "Ошибка на ${state.moveNumber}-м ходу исправлена!"
                    } else {
                        "Исправьте ход ${state.moveNumber}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isSolved) LocalChessPalette.current.positive else MaterialTheme.colorScheme.primary
                )
                if (state.isCheckingEngine) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = state.feedback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            var more by remember(state.targetMoveIndex, state.isSolved) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.isSolved -> Button(onClick = onContinueGame, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Дальше")
                    }
                    state.hintLevel < 2 -> FilledTonalButton(onClick = onHint, enabled = !state.isCheckingEngine,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(if (state.hintLevel == 0) "Подсказка" else "Показать ход")
                    }
                    else -> TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("К партии")
                    }
                }
                val canReturn = !state.isSolved && state.hintLevel < 2
                if (canReturn || onPracticeInTrainer != null || (state.isSolved && onShowLine != null)) {
                    Box {
                        IconButton(onClick = { more = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Действия разбора")
                        }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (canReturn) DropdownMenuItem(text = { Text("К партии") },
                                onClick = { more = false; onCancel() })
                            if (state.isSolved && onShowLine != null) DropdownMenuItem(text = { Text("Посмотреть вариант") },
                                onClick = { more = false; onShowLine() })
                            if (onPracticeInTrainer != null) DropdownMenuItem(text = { Text("В тренажёр") },
                                onClick = { more = false; onPracticeInTrainer() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VariationSandboxPanel(
    steps: List<GameReviewHelper.VariationStep>,
    onStepBack: () -> Unit,
    onExitVariation: () -> Unit,
    onAnalyzeWithEngine: (() -> Unit)?
) {
    val lineText = remember(steps) { GameReviewHelper.formatVariationLine(steps) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "Своя ветка: $lineText",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onStepBack,
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Назад", fontSize = 11.sp)
                }
                if (onAnalyzeWithEngine != null) {
                    OutlinedButton(
                        onClick = onAnalyzeWithEngine,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Оценить", fontSize = 11.sp)
                    }
                }
                FilledTonalButton(
                    onClick = onExitVariation,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Вернуться к партии", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EngineLineCard(
    state: EngineLineUiState,
    onPlayBestMove: (() -> Unit)?,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "Stockfish считает лучшую линию...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.evalWhiteCp?.let { cp ->
                            Text(
                                text = ChessEngine.formatEvaluation(cp),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (state.isAlternativeBeforeMove) {
                                "Сильнейшее продолжение"
                            } else {
                                "Stockfish"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val body = state.errorMessage
                        ?: state.pvFormatted.ifBlank { "Нет доступных ходов" }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onPlayBestMove != null) {
                    FilledTonalButton(
                        onClick = onPlayBestMove,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("▶ Ход", fontSize = 11.sp)
                    }
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Скрыть линию",
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
internal fun AnalyzedMoveInfoCard(
    analyzedMove: AnalyzedMove,
    isPlayerMove: Boolean,
    fenBefore: String?,
    clockSeconds: Int?,
    onRevealAnswer: (() -> Unit)?,
    onRetryMove: (() -> Unit)?,
    onShowEngineLine: (() -> Unit)?,
    onPracticeInTrainer: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val quality = analyzedMove.quality
    val moverDelta = analyzedMove.playerEvalChange
    val userDelta = if (isPlayerMove) moverDelta else -moverDelta
    val isMateDelta = ChessEngine.isMateScore(userDelta) || abs(userDelta) > 50_000
    val moveLabel = if (analyzedMove.color == ChessColor.WHITE) {
        "${analyzedMove.moveNumber}. ${analyzedMove.san}"
    } else {
        "${analyzedMove.moveNumber}... ${analyzedMove.san}"
    }
    val titleText = if (isPlayerMove) {
        quality.getDisplayName()
    } else {
        "${quality.getDisplayName()} соперника"
    }
    val bestSan = remember(analyzedMove.bestMove, fenBefore) {
        val raw = analyzedMove.bestMove?.trim().orEmpty()
        if (raw.isEmpty()) {
            null
        } else if (!fenBefore.isNullOrBlank() && ChessMoveUtils.isUciMove(raw)) {
            ChessMoveUtils.uciToSan(raw, fenBefore)
        } else {
            raw
        }
    }
    val inTimeTrouble = GameReviewHelper.isTimeTrouble(clockSeconds)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LocalChessPalette.current.accent(quality).copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(quality.getIcon(), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = LocalChessPalette.current.accent(quality)
                        )
                        if (inTimeTrouble && clockSeconds != null) {
                            Text(
                                text = "· Осталось: ${GameReviewHelper.formatClock(clockSeconds)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalChessPalette.current.negative
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = moveLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (onRevealAnswer == null && !bestSan.isNullOrBlank() && quality.isBad() && bestSan != analyzedMove.san) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Лучше: $bestSan",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalChessPalette.current.positive
                            )
                        }
                    }
                }
                if (userDelta != 0 && quality != MoveQuality.BOOK) {
                    Spacer(modifier = Modifier.width(6.dp))
                    val text = when {
                        isMateDelta -> if (userDelta > 0) "+Мат" else "-Мат"
                        userDelta > 0 -> String.format(Locale.US, "+%.1f", userDelta / 100.0)
                        else -> String.format(Locale.US, "%.1f", userDelta / 100.0)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (userDelta > 0) LocalChessPalette.current.positive else LocalChessPalette.current.negative
                    )
                }
            }

            analyzedMove.comment?.takeIf { onRevealAnswer == null && it.isNotBlank() }?.let { comment ->
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    lineHeight = 16.sp
                )
            }

            MistakeReviewActions(onRetryMove, onRevealAnswer, onShowEngineLine, onPracticeInTrainer)
        }
    }
}

@Composable
internal fun ReviewNavigationBar(
    currentMoveIndex: Int,
    totalMoves: Int,
    isInOverlay: Boolean,
    hasPreviousKeyMoment: Boolean,
    hasNextKeyMoment: Boolean,
    onGoToStart: () -> Unit,
    onPreviousKeyMoment: () -> Unit,
    onGoToPrevious: () -> Unit,
    onGoToNext: () -> Unit,
    onNextKeyMoment: () -> Unit,
    onGoToEnd: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canGoBack = currentMoveIndex > -1 || isInOverlay
    val canGoForward = currentMoveIndex < totalMoves - 1 || isInOverlay
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onGoToPrevious, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущий ход")
            }
            Text("${(currentMoveIndex + 1).coerceAtLeast(0)}/$totalMoves",
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            IconButton(onClick = onGoToNext, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующий ход")
            }
            Button(onClick = onNextKeyMoment, enabled = hasNextKeyMoment,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("К моменту", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Другие переходы")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Предыдущий ключевой момент") }, enabled = hasPreviousKeyMoment,
                        onClick = { menuOpen = false; onPreviousKeyMoment() })
                    DropdownMenuItem(text = { Text("В начало партии") }, enabled = canGoBack,
                        onClick = { menuOpen = false; onGoToStart() })
                    DropdownMenuItem(text = { Text("В конец партии") }, enabled = canGoForward,
                        onClick = { menuOpen = false; onGoToEnd() })
                }
            }
        }
    }
}

@Composable
private fun ReviewPromotionDialog(
    onSelect: (PieceType) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        PieceType.QUEEN to "♕ Ферзь",
        PieceType.ROOK to "♖ Ладья",
        PieceType.BISHOP to "♗ Слон",
        PieceType.KNIGHT to "♘ Конь"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Превращение пешки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (type, label) ->
                    FilledTonalButton(
                        onClick = { onSelect(type) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
