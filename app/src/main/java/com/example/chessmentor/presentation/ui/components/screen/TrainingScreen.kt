package com.example.chessmentor.presentation.ui.components.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.R
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.SessionSummary
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.example.chessmentor.presentation.ui.components.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.ListSkeleton
import com.example.chessmentor.presentation.ui.components.SoundManager
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.HintLevel
import com.example.chessmentor.presentation.viewmodel.SessionStats
import com.example.chessmentor.presentation.viewmodel.SmartSessionStepStatus
import com.example.chessmentor.presentation.viewmodel.TrainingSoundEvent
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import com.example.chessmentor.presentation.viewmodel.TrainingViewModel
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

@Composable
fun TrainingScreen(
    paddingValues: PaddingValues,
    container: AppContainer,
    userId: Long,
    onLeaveTraining: () -> Unit,
    preferredTheme: String? = null,
    initialSoundEnabled: Boolean = true,
    initialModeKey: String? = null,
    onModeConsumed: () -> Unit = {},
    onNavigateToUpload: () -> Unit = {},
    onReturnToGame: (() -> Unit)? = null,
    onUserUpdated: (User) -> Unit = {},
    onDueReviewCountChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val viewModel: TrainingViewModel = viewModel(factory = factory)
    val currentOnUserUpdated by rememberUpdatedState(onUserUpdated)
    val currentOnModeConsumed by rememberUpdatedState(onModeConsumed)

    // Звуковой менеджер с корректным освобождением ресурсов
    val soundManager = remember(context) { SoundManager(context.applicationContext) }
    var soundEnabled by rememberSaveable(userId, initialSoundEnabled) { mutableStateOf(initialSoundEnabled) }
    DisposableEffect(soundManager) {
        onDispose { soundManager.release() }
    }
    LaunchedEffect(soundEnabled) {
        soundManager.setEnabled(soundEnabled)
    }

    val uiState by viewModel.uiState
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onPauseTraining()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Сообщаем навигации о количестве задач на повторение — для бейджа на вкладке «Задачи»
    LaunchedEffect(uiState.dueReviewCount) {
        onDueReviewCountChange(uiState.dueReviewCount)
    }

    LaunchedEffect(userId, initialModeKey) {
        if (userId > 0L) {
            val user = container.userRepository.findById(userId)
            if (user != null) {
                val targetMode = initialModeKey?.let { TrainingMode.fromRouteKey(it) }
                viewModel.init(user, initialMode = targetMode) { updated ->
                    currentOnUserUpdated(updated)
                }
            } else {
                viewModel.showInitializationError("Не удалось загрузить профиль пользователя.")
            }
        } else {
            viewModel.showInitializationError("Войдите в профиль, чтобы начать тренировку.")
        }
        if (initialModeKey != null) {
            currentOnModeConsumed()
        }
    }

    var selectedSquare by remember { mutableStateOf<Square?>(null) }
    var pendingPromotion by remember { mutableStateOf<Pair<Square, Square>?>(null) }
    val shakeOffset = remember { Animatable(0f) }
    val solvePulse = remember { Animatable(0f) }

    // Микро-анимация при правильном ходе: пульсирующая зелёная граница доски
    LaunchedEffect(uiState.isSolved) {
        if (uiState.isSolved) {
            solvePulse.animateTo(1f, animationSpec = tween(150))
            solvePulse.animateTo(0f, animationSpec = tween(600))
        }
    }

    val boardTheme = remember(preferredTheme) {
        BoardThemes.getByName(preferredTheme)
    }

    // Воспроизведение звуковых событий
    LaunchedEffect(uiState.soundEventId) {
        if (uiState.soundEventId <= 0L) return@LaunchedEffect
        when (uiState.soundEvent) {
            TrainingSoundEvent.MOVE -> soundManager.playMove()
            TrainingSoundEvent.CAPTURE -> soundManager.playCapture()
            TrainingSoundEvent.CHECK -> soundManager.playCheck()
            TrainingSoundEvent.SOLVED -> soundManager.playSuccess()
            TrainingSoundEvent.ERROR -> soundManager.playError()
            null -> {}
        }
    }

    LaunchedEffect(uiState.isErrorShake) {
        if (uiState.isErrorShake) {
            repeat(3) {
                shakeOffset.animateTo(10f, animationSpec = tween(45))
                shakeOffset.animateTo(-10f, animationSpec = tween(45))
            }
            shakeOffset.animateTo(0f, animationSpec = tween(45))
        }
    }

    LaunchedEffect(uiState.board.fen, uiState.isInputLocked) {
        selectedSquare = null
        pendingPromotion = null
    }

    BackHandler(enabled = uiState.isFreeAnalysisMode) {
        selectedSquare = null
        pendingPromotion = null
        viewModel.onToggleFreeAnalysis()
    }

    // Вычисляем легальные ходы выбранной фигуры (отдельно тихие ходы и взятия, включая взятие на проходе)
    val (legalMoveSquares, legalCaptureSquares) = remember(uiState.board.fen, selectedSquare) {
        val from = selectedSquare
        if (from == null) {
            emptySet<Square>() to emptySet<Square>()
        } else {
            val board = uiState.board
            val movingPiece = board.getPiece(from)
            val allLegal = runCatching { board.legalMoves() }.getOrDefault(emptyList())
                .filter { it.from == from }
            val quiet = mutableSetOf<Square>()
            val captures = mutableSetOf<Square>()
            for (m in allLegal) {
                val targetPiece = board.getPiece(m.to)
                val isEnPassant = movingPiece.pieceType == PieceType.PAWN &&
                    m.from.file != m.to.file &&
                    targetPiece == Piece.NONE
                if (targetPiece != Piece.NONE || isEnPassant) {
                    captures.add(m.to)
                } else {
                    quiet.add(m.to)
                }
            }
            quiet to captures
        }
    }

    val hintHighlights = remember(uiState.highlights) {
        uiState.highlights.mapNotNull { raw ->
            runCatching {
                Square.fromValue(raw.uppercase()).takeIf { sq -> sq != Square.NONE }
            }.getOrNull()
        }.toSet()
    }

    val layoutDirection = LocalLayoutDirection.current
    val topInset = paddingValues.calculateTopPadding()
    val bottomInset = paddingValues.calculateBottomPadding()
    val startInset = paddingValues.calculateStartPadding(layoutDirection)
    val endInset = paddingValues.calculateEndPadding(layoutDirection)

    Scaffold(
        modifier = Modifier.consumeWindowInsets(paddingValues),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = topInset, start = startInset, end = endInset)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onLeaveTraining, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Выйти из тренировки")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ModePicker(
                            selectedMode = uiState.mode,
                            dueReviewCount = uiState.dueReviewCount,
                            enabled = !uiState.isLoading && !uiState.isAnimatingIntro && !uiState.isAnimatingPunishment,
                            onSelectMode = { mode ->
                                selectedSquare = null
                                pendingPromotion = null
                                viewModel.selectMode(mode)
                            }
                        )
                    }
                    val canPauseOrResume = !uiState.isLoading &&
                        uiState.hasActiveExercise &&
                        !uiState.isSolved &&
                        !uiState.isSolutionRevealed &&
                        !uiState.isAnimatingIntro &&
                        !uiState.isAnimatingPunishment &&
                        !uiState.checkUnavailable
                    if (canPauseOrResume) {
                        IconButton(
                            onClick = {
                                selectedSquare = null
                                pendingPromotion = null
                                if (uiState.isPaused) {
                                    viewModel.onResumeTraining()
                                } else {
                                    viewModel.onPauseTraining()
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (uiState.isPaused) "Продолжить тренировку" else "Пауза"
                            )
                        }
                    }
                }
                SessionHeader(
                    stats = uiState.sessionStats,
                    sideToMove = uiState.playerSide,
                    hasActiveExercise = uiState.hasActiveExercise,
                    soundEnabled = soundEnabled,
                    onToggleSound = { soundEnabled = !soundEnabled }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        },
        bottomBar = {
            if (!uiState.isLoading && uiState.hasActiveExercise) {
                BottomControlPanel(
                    uiState = uiState,
                    bottomInset = bottomInset,
                    startInset = startInset,
                    endInset = endInset,
                    onRetryCheck = viewModel::retryMoveCheck,
                    onReturnFromCheck = viewModel::returnFromMoveCheck,
                    onNextExercise = {
                        selectedSquare = null
                        pendingPromotion = null
                        viewModel.loadNextExercise()
                    },
                    onHint = { viewModel.onHintClicked() },
                    onShowBetterMove = { viewModel.onShowBetterMoveClicked() },
                    onRevealSolution = {
                        selectedSquare = null
                        pendingPromotion = null
                        viewModel.onRevealSolution()
                    },
                    onSkip = {
                        selectedSquare = null
                        pendingPromotion = null
                        viewModel.onSkipExercise()
                    },
                    onReviewFirst = {
                        selectedSquare = null
                        viewModel.onReviewFirst()
                    },
                    onReviewPrev = {
                        selectedSquare = null
                        viewModel.onReviewPrev()
                    },
                    onReviewNext = {
                        selectedSquare = null
                        viewModel.onReviewNext()
                    },
                    onReviewLast = {
                        selectedSquare = null
                        viewModel.onReviewLast()
                    },
                    onToggleFreeAnalysis = {
                        selectedSquare = null
                        pendingPromotion = null
                        viewModel.onToggleFreeAnalysis()
                    },
                    onUndoAnalysis = {
                        selectedSquare = null
                        viewModel.onUndoAnalysisMove()
                    },
                    onPlayEngineBest = {
                        selectedSquare = null
                        viewModel.onPlayEngineBestInAnalysis()
                    },
                    onResumeTraining = {
                        selectedSquare = null
                        pendingPromotion = null
                        viewModel.onResumeTraining()
                    }
                )
            }
        }
    ) { innerPadding ->
        val activeSummary = uiState.sessionSummary
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(start = startInset, end = endInset, bottom = bottomInset)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ListSkeleton(itemCount = 3)
                    uiState.feedbackMessage?.let { loadingText ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = loadingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            activeSummary != null -> {
                SessionSummaryCard(
                    summary = activeSummary,
                    onRestartSession = { viewModel.restartSmartSession() },
                    onSwitchToFreeTraining = { viewModel.selectMode(TrainingMode.All) },
                    onPracticeWeakPatterns = { viewModel.selectMode(TrainingMode.WeakPatterns) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(start = startInset, end = endInset, bottom = bottomInset)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            !uiState.hasActiveExercise -> {
                val isFromGame = (uiState.mode is TrainingMode.FromGame || uiState.mode is TrainingMode.SkillTransfer) &&
                    onReturnToGame != null
                val action = when {
                    uiState.isInitializationError -> Pair(onLeaveTraining, "Вернуться назад")
                    isFromGame -> Pair(onReturnToGame, "Вернуться к разбору партии")
                    uiState.hasNoUserMistakes -> Pair(onNavigateToUpload, "Загрузить партию (PGN)")
                    else -> Pair({ viewModel.selectMode(TrainingMode.All) }, "Открыть общий тактический банк")
                }
                BrandEmptyState(
                    painter = painterResource(R.drawable.ic_empty_training),
                    title = uiState.feedbackMessage ?: "Нет доступных задач в выбранном режиме.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(start = startInset, end = endInset, bottom = bottomInset)
                        .padding(24.dp),
                    onAction = action.first,
                    actionLabel = action.second
                )
            }

            else -> {
                val solveBorderColor by animateColorAsState(
                    if (uiState.isSolved) LocalChessPalette.current.positive else MaterialTheme.colorScheme.outlineVariant,
                    animationSpec = tween(300),
                    label = "solveBorder"
                )
                val isFreeAnalysis = uiState.isFreeAnalysisMode &&
                    (uiState.isSolved || uiState.isSolutionRevealed) &&
                    !uiState.isAnimatingIntro &&
                    !uiState.isAnimatingPunishment
                val canSolveMove = !uiState.isSolved &&
                    !uiState.isSolutionRevealed &&
                    !uiState.isInputLocked &&
                    !uiState.isAnimatingIntro &&
                    !uiState.isAnimatingPunishment
                val canInteract = canSolveMove || isFreeAnalysis
                val movableSide = if (isFreeAnalysis) uiState.board.sideToMove else uiState.playerSide
                val handleSquareClick: ((Square) -> Unit)? = if (canInteract) {
                    { square ->
                        val from = selectedSquare
                        val clickedPiece = uiState.board.getPiece(square)

                        when {
                            from == null -> {
                                if (clickedPiece != Piece.NONE && clickedPiece.pieceSide == movableSide) {
                                    selectedSquare = square
                                }
                            }
                            square == from -> {
                                selectedSquare = null
                            }
                            clickedPiece != Piece.NONE && clickedPiece.pieceSide == movableSide -> {
                                selectedSquare = square
                            }
                            square !in legalMoveSquares && square !in legalCaptureSquares -> {
                                selectedSquare = null
                            }
                            ChessMoveUtils.isPawnPromotion(uiState.board, from, square) -> {
                                pendingPromotion = from to square
                                selectedSquare = null
                            }
                            else -> {
                                val move = ChessMoveUtils.createMove(from, square, movableSide)
                                selectedSquare = null
                                viewModel.onMoveMade(move)
                            }
                        }
                    }
                } else {
                    null
                }

                val boardSlot: @Composable (Modifier) -> Unit = { boxModifier ->
                    Box(
                        modifier = boxModifier
                            .aspectRatio(1f)
                            .graphicsLayer { translationX = shakeOffset.value }
                            .border(
                                width = (3f * solvePulse.value).dp,
                                color = solveBorderColor.copy(alpha = 0.3f + 0.7f * solvePulse.value),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        ChessBoard(
                            board = uiState.board,
                            highlightedSquares = hintHighlights,
                            selectedSquare = selectedSquare,
                            legalMoveSquares = legalMoveSquares,
                            legalCaptureSquares = legalCaptureSquares,
                            arrows = uiState.arrows,
                            lastMove = uiState.lastMove,
                            theme = boardTheme,
                            flipped = uiState.playerSide == Side.BLACK,
                            showCoordinates = true,
                            onSquareClick = handleSquareClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(start = startInset, end = endInset)
                        .padding(horizontal = 10.dp)
                ) {
                    val isCompactLandscape = maxWidth > maxHeight && maxHeight < 460.dp
                    val isVeryShortPortrait = !isCompactLandscape && maxHeight < 280.dp

                    when {
                        isCompactLandscape -> {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    boardSlot(Modifier)
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (uiState.mode is TrainingMode.SmartSession && uiState.sessionStepNumber > 0) {
                                        SmartSessionProgressBar(uiState = uiState)
                                    }
                                    ExerciseMetaStrip(uiState = uiState)
                                    UnifiedFeedbackPanel(uiState = uiState)
                                }
                            }
                        }

                        isVeryShortPortrait -> {
                            val scrollBoardSide = (maxWidth - 20.dp).coerceIn(180.dp, 280.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (uiState.mode is TrainingMode.SmartSession && uiState.sessionStepNumber > 0) {
                                    SmartSessionProgressBar(uiState = uiState)
                                }
                                ExerciseMetaStrip(uiState = uiState)
                                boardSlot(Modifier.size(scrollBoardSide))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    UnifiedFeedbackPanel(uiState = uiState)
                                }
                            }
                        }

                        else -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (uiState.mode is TrainingMode.SmartSession && uiState.sessionStepNumber > 0) {
                                    SmartSessionProgressBar(uiState = uiState)
                                }
                                ExerciseMetaStrip(uiState = uiState)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    boardSlot(Modifier)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    UnifiedFeedbackPanel(uiState = uiState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPromotion?.let { (from, to) ->
        val promoSide = if (uiState.isFreeAnalysisMode) uiState.board.sideToMove else uiState.playerSide
        com.example.chessmentor.presentation.ui.components.PawnPromotionDialog(
            side = promoSide,
            onSelect = { pieceType ->
                pendingPromotion = null
                val promotionMove = ChessMoveUtils.createMove(
                    from = from,
                    to = to,
                    side = promoSide,
                    promotion = pieceType
                )
                viewModel.onMoveMade(promotionMove)
            },
            onDismiss = { pendingPromotion = null }
        )
    }
}

@Composable
internal fun ModePicker(
    selectedMode: TrainingMode,
    dueReviewCount: Int,
    enabled: Boolean,
    onSelectMode: (TrainingMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var choosingTheme by remember { mutableStateOf(false) }
    val choose: (TrainingMode) -> Unit = { mode ->
        expanded = false
        onSelectMode(mode)
    }
    Box {
        TextButton(
            onClick = { choosingTheme = false; expanded = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    "Режим тренировки",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    selectedMode.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Сменить режим")
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = {
                expanded = false
                choosingTheme = false
            }
        ) {
            if (choosingTheme) {
                DropdownMenuItem(text = { Text("Назад к режимам") }, onClick = { choosingTheme = false },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) })
                HorizontalDivider()
            }
            val modes = if (choosingTheme) {
                TrainingMode.selectableThemes.map { TrainingMode.ByTheme(it) }
            } else {
                listOfNotNull(
                    (selectedMode as? TrainingMode.FromGame)?.copy(focusMoveNumber = null),
                    selectedMode as? TrainingMode.SkillTransfer,
                    TrainingMode.Daily, TrainingMode.SmartSession, TrainingMode.All, TrainingMode.MyMistakes,
                    TrainingMode.WeakPatterns, TrainingMode.PatternVision, TrainingMode.DeepCalculation, TrainingMode.Defense
                )
            }
            modes.forEach { mode ->
                val isCurrentMode = mode == selectedMode ||
                    (mode is TrainingMode.FromGame && selectedMode is TrainingMode.FromGame) ||
                    (mode is TrainingMode.SkillTransfer && selectedMode is TrainingMode.SkillTransfer)
                DropdownMenuItem(
                    modifier = Modifier.semantics {
                        selected = isCurrentMode
                        role = Role.RadioButton
                    },
                    text = { Text(if (mode == TrainingMode.WeakPatterns && dueReviewCount > 0)
                        "${mode.displayName} · повторить: $dueReviewCount" else mode.displayName) },
                    trailingIcon = if (isCurrentMode) {
                        { Icon(Icons.Default.Check, contentDescription = "Выбрано") }
                    } else null,
                    onClick = { choose(mode) }
                )
            }
            if (!choosingTheme) {
                HorizontalDivider()
                DropdownMenuItem(
                    modifier = Modifier.semantics {
                        contentDescription = "По тактической теме — открыть список тем"
                    },
                    text = { Text("По тактической теме") },
                    onClick = { choosingTheme = true },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                )
            }
        }
    }
}

@Composable
internal fun SmartSessionProgressBar(uiState: TrainingUiState) {
    val total = uiState.sessionTotalSteps.coerceAtLeast(1)
    val step = uiState.sessionStepNumber.coerceIn(1, total)
    val phaseText = uiState.sessionPhase?.let { phase ->
        if (uiState.isSolved || uiState.isSolutionRevealed) phase.displayName else phase.preSolveLabel
    } ?: "Тренировка"
    val palette = LocalChessPalette.current

    val statuses = if (uiState.sessionStepStatuses.size >= total) {
        uiState.sessionStepStatuses.take(total)
    } else {
        List(total) { idx ->
            when {
                idx < step - 1 -> SmartSessionStepStatus.CLEAN
                idx == step - 1 -> when {
                    uiState.isSolved -> SmartSessionStepStatus.CLEAN
                    uiState.isSolutionRevealed -> SmartSessionStepStatus.FAILED
                    else -> SmartSessionStepStatus.CURRENT
                }
                else -> SmartSessionStepStatus.PENDING
            }
        }
    }

    val cleanCount = if (uiState.sessionStepStatuses.isNotEmpty()) {
        uiState.sessionCleanCount
    } else {
        statuses.count { it == SmartSessionStepStatus.CLEAN }
    }
    val failedCount = uiState.sessionFailedCount
    val skippedCount = uiState.sessionSkippedCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Задача $step из $total • $phaseText",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = buildString {
                    append("Чисто: $cleanCount")
                    if (failedCount > 0) append(" · Ошибок: $failedCount")
                    if (skippedCount > 0) append(" · Проп.: $skippedCount")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .semantics {
                    contentDescription = "Прогресс сессии: задача $step из $total, чисто $cleanCount"
                },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            statuses.forEach { status ->
                val color = when (status) {
                    SmartSessionStepStatus.CLEAN -> palette.ratingUp
                    SmartSessionStepStatus.SOLVED_WITH_ASSIST -> palette.warning
                    SmartSessionStepStatus.FAILED -> palette.ratingDown
                    SmartSessionStepStatus.SKIPPED -> MaterialTheme.colorScheme.outlineVariant
                    SmartSessionStepStatus.CURRENT -> MaterialTheme.colorScheme.primary
                    SmartSessionStepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
    }
}

@Composable
internal fun SessionSummaryCard(
    summary: SessionSummary,
    onRestartSession: () -> Unit,
    onSwitchToFreeTraining: () -> Unit,
    onPracticeWeakPatterns: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = summary.headline,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = summary.headline,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                val attemptedCount = (summary.totalExercises - summary.skippedCount).coerceAtLeast(0)
                val subtitleText = when {
                    attemptedCount == 0 -> "Все ${summary.totalExercises} задач пропущены без попыток"
                    summary.skippedCount > 0 ->
                        "Попыток: $attemptedCount из ${summary.totalExercises} · чисто: ${summary.cleanFirstTryCount}, с ошибками: ${summary.failedCount}, пропущено: ${summary.skippedCount}"
                    summary.failedCount > 0 ->
                        "Сессия из ${summary.totalExercises} задач: чисто ${summary.cleanFirstTryCount}, с ошибками ${summary.failedCount}"
                    else ->
                        "Сессия из ${summary.totalExercises} задач завершена: чисто ${summary.cleanFirstTryCount} из ${summary.totalExercises}"
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStatPill(
                        label = "С 1-й попытки",
                        value = if (attemptedCount > 0) {
                            "${summary.cleanFirstTryCount}/$attemptedCount (${summary.firstTryAccuracyPercent}%)"
                        } else {
                            "0/0"
                        }
                    )
                    if (summary.skippedCount > 0) {
                        SummaryStatPill(
                            label = "Пропущено",
                            value = "${summary.skippedCount}"
                        )
                    }
                    SummaryStatPill(
                        label = "Ср. время",
                        value = if (summary.averageTimeSeconds > 0) "${summary.averageTimeSeconds} с" else "—"
                    )
                }

                if (summary.impulsiveMistakeCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Поспешных ходов: ${summary.impulsiveMistakeCount}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (summary.missedPatternNames.isNotEmpty()) {
                    Text(
                        text = "Темы для повторения: ${summary.missedPatternNames.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = summary.coachTakeaway,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Button(
            onClick = onRestartSession,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Начать новую сессию")
            Spacer(Modifier.width(8.dp))
            Text("Новая сессия (10 задач)", fontWeight = FontWeight.Bold)
        }

        if (summary.missedPatternNames.isNotEmpty()) {
            OutlinedButton(
                onClick = onPracticeWeakPatterns,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Закрепить слабые темы")
            }
        }

        TextButton(
            onClick = onSwitchToFreeTraining,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("Перейти в свободную тренировку")
        }
    }
}

@Composable
private fun SummaryStatPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExerciseMetaStrip(uiState: TrainingUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.mode == TrainingMode.Daily && uiState.sourceLabel == uiState.mode.displayName) Spacer(Modifier.weight(1f))
        else Text(
            text = uiState.sourceLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Не спойлерим общее число ходов до решения: плашка появляется только при продолжении
            if (uiState.isFreeAnalysisMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = uiState.analysisEvalText ?: "Анализ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else if (uiState.currentStepNumber > 1 && !uiState.isSolved && !uiState.isSolutionRevealed) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Продолжение",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${uiState.difficultyLabel} • ${uiState.exerciseElo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}


@Composable
internal fun UnifiedFeedbackPanel(uiState: TrainingUiState) {
    val analysis = uiState.currentMistakeAnalysis
    val isFinished = uiState.isSolved || uiState.isSolutionRevealed
    val isErrorNow = !isFinished &&
        (analysis != null || (uiState.lastRatingDelta != null && uiState.lastRatingDelta < 0))
    val motifNow = when {
        isFinished -> uiState.patternDisplayName
        isErrorNow -> analysis?.tacticalMotif?.russianName
        else -> null
    }
    val mainText = if (uiState.isFreeAnalysisMode && isFinished) {
        val sideToMoveWord = if (uiState.board.sideToMove == Side.WHITE) "белых" else "чёрных"
        val evalPart = uiState.analysisEvalText?.let { "Оценка Stockfish: $it" } ?: "Свободный анализ"
        val bestPart = uiState.analysisBestMoveSan?.let { " • Лучший ответ: $it" }.orEmpty()
        "$evalPart$bestPart\nДвигайте фигуры за $sideToMoveWord, чтобы проверить любой вариант"
    } else {
        uiState.feedbackMessage ?: uiState.exerciseSubtitle ?: if (isErrorNow) "Неверный ход" else null
    }
    val details = listOfNotNull(
        mainText,
        motifNow?.let { "Мотив: $it" },
        uiState.postSolveExplanation.takeIf { isFinished && !uiState.isFreeAnalysisMode },
        uiState.solutionSanLine.takeIf { isFinished && !uiState.isFreeAnalysisMode },
        uiState.nextReviewNote.takeIf { isFinished && !uiState.isFreeAnalysisMode }
    ).filter { it.isNotBlank() }
    if (details.isEmpty()) return

    val headline = details.first().substringBefore('\n')
    val containerColor = when {
        isErrorNow -> MaterialTheme.colorScheme.errorContainer
        isFinished -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    var showDetails by remember { mutableStateOf(false) }
    val hasMoreDetails = details.size > 1 || (uiState.feedbackMessage?.contains('\n') == true)
    Surface(
        onClick = { showDetails = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(headline, modifier = Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (hasMoreDetails) {
                Text(
                    text = "Подробнее",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Icon(Icons.Default.Info, contentDescription = "Открыть пояснение")
        }
    }
    if (showDetails) {
        Dialog(onDismissRequest = { showDetails = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Пояснение к задаче",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = details.joinToString("\n\n"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(
                        onClick = { showDetails = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("К доске")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHeader(
    stats: SessionStats,
    sideToMove: Side,
    hasActiveExercise: Boolean,
    soundEnabled: Boolean,
    onToggleSound: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = "Решено", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(stats.solved.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, contentDescription = "Ошибки", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(stats.failed.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            if (stats.currentStreak > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = "Серия",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${stats.currentStreak}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasActiveExercise) {
                val isWhite = sideToMove == Side.WHITE
                Surface(
                    color = if (isWhite) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.inverseSurface
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = if (isWhite) "Ход белых" else "Ход чёрных",
                        color = if (isWhite) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.inverseOnSurface
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            IconButton(
                onClick = onToggleSound,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (soundEnabled) {
                        Icons.AutoMirrored.Filled.VolumeUp
                    } else {
                        Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = if (soundEnabled) "Выключить звук" else "Включить звук",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BottomControlPanel(
    uiState: TrainingUiState,
    bottomInset: Dp,
    startInset: Dp,
    endInset: Dp,
    onRetryCheck: () -> Unit,
    onReturnFromCheck: () -> Unit,
    onNextExercise: () -> Unit,
    onHint: () -> Unit,
    onShowBetterMove: () -> Unit,
    onRevealSolution: () -> Unit,
    onSkip: () -> Unit,
    onReviewFirst: () -> Unit,
    onReviewPrev: () -> Unit,
    onReviewNext: () -> Unit,
    onReviewLast: () -> Unit,
    onToggleFreeAnalysis: () -> Unit,
    onUndoAnalysis: () -> Unit,
    onPlayEngineBest: () -> Unit,
    onResumeTraining: () -> Unit = {}
) {
    val isFinished = uiState.isSolved || uiState.isSolutionRevealed
    val controlsEnabled = !uiState.isAnimatingIntro && !uiState.isAnimatingPunishment && !uiState.isInputLocked

    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomInset, start = startInset, end = endInset)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.checkUnavailable) {
                Button(
                    onClick = onRetryCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) { Text("Повторить проверку") }
                TextButton(
                    onClick = onReturnFromCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) { Text("Вернуться к позиции") }
            } else if (uiState.isPaused && !isFinished) {
                Button(
                    onClick = onResumeTraining,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Продолжить задачу",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isFinished) {
                if (uiState.isFreeAnalysisMode) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onUndoAnalysis,
                            enabled = uiState.canUndoAnalysisMove,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить ход", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Назад", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onPlayEngineBest,
                            enabled = uiState.analysisBestMoveUci != null && !uiState.isAnalyzingPosition,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Показать лучший ход движка", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = uiState.analysisBestMoveSan?.let { "Ход $it" } ?: "Ход движка",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onToggleFreeAnalysis,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Вернуться к решению", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("К линии", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                } else {
                    SolutionResultInlineCard(uiState = uiState)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (uiState.reviewSteps.size > 1) {
                            val canBack = uiState.reviewStepIndex > 0
                            val canForward = uiState.reviewStepIndex < uiState.reviewSteps.lastIndex
                            val stepLabel = uiState.reviewSteps
                                .getOrNull(uiState.reviewStepIndex)
                                ?.moveLabel
                                .orEmpty()

                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onReviewFirst,
                                    enabled = canBack,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.FirstPage, contentDescription = "В начало", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = onReviewPrev,
                                    enabled = canBack,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Ход назад", modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    text = stepLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                IconButton(
                                    onClick = onReviewNext,
                                    enabled = canForward,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ход вперёд", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = onReviewLast,
                                    enabled = canForward,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.LastPage, contentDescription = "В конец", modifier = Modifier.size(20.dp))
                                }
                            }
                        } else {
                            Spacer(Modifier.width(4.dp))
                        }

                        OutlinedButton(
                            onClick = onToggleFreeAnalysis,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Analytics, contentDescription = "Анализ позиции", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Анализ", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }

                val isFinalSessionPuzzle = uiState.mode is TrainingMode.SmartSession &&
                    uiState.sessionStepNumber >= uiState.sessionTotalSteps

                Button(
                    onClick = onNextExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isFinalSessionPuzzle) "Подвести итоги сессии" else "Следующая задача",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isFinalSessionPuzzle) {
                            Icons.Default.EmojiEvents
                        } else {
                            Icons.AutoMirrored.Filled.ArrowForward
                        },
                        contentDescription = if (isFinalSessionPuzzle) "Завершить сессию" else "Перейти к следующей задаче"
                    )
                }
            } else {
                uiState.saveWarningMessage?.takeIf { it.isNotBlank() }?.let { saveWarning ->
                    Text(
                        text = saveWarning,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TrainingHelpControls(uiState, controlsEnabled, onHint, onShowBetterMove, onRevealSolution, onSkip)
            }
        }
    }
}

@Composable
internal fun SolutionResultInlineCard(uiState: TrainingUiState) {
    val explanation = uiState.postSolveExplanation?.takeIf { it.isNotBlank() }
    val line = uiState.solutionSanLine?.takeIf { it.isNotBlank() }
    val schedule = uiState.nextReviewNote?.takeIf { it.isNotBlank() }
    val saveWarning = uiState.saveWarningMessage?.takeIf { it.isNotBlank() }
    val motif = uiState.patternDisplayName?.takeIf { it.isNotBlank() }
    if (explanation == null && line == null && schedule == null && saveWarning == null) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 132.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (saveWarning != null) {
                Text(
                    text = saveWarning,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (motif != null || schedule != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (motif != null) {
                        Text(
                            text = "Мотив: $motif",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (schedule != null) {
                        Text(
                            text = schedule,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (explanation != null) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (line != null) {
                Text(
                    text = "Решение: $line",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Opening or dismissing the chooser must never consume a hint. */
@Composable
internal fun TrainingHelpControls(
    state: TrainingUiState,
    enabled: Boolean,
    onHint: () -> Unit,
    onBetterMove: () -> Unit,
    onReveal: () -> Unit,
    onSkip: () -> Unit
) {
    var open by remember(enabled, state.mode, state.currentStepNumber) { mutableStateOf(false) }
    val skipLabel = if (state.mode is TrainingMode.Daily) "К другим задачам" else "Пропустить"
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackControls = maxWidth < (220 * LocalDensity.current.fontScale).dp
        if (stackControls) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { open = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Помощь")
                }
                OutlinedButton(
                    onClick = onSkip,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(skipLabel, textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { open = true },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Помощь", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onSkip,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(
                        text = skipLabel,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    if (!open || !enabled) return

    val better = state.showBetterMoveHint && !state.betterMoveShown
    val offerPiece = !better && state.hintLevel == HintLevel.NONE
    val moveShown = state.betterMoveShown || state.hintLevel == HintLevel.FULL_SOLUTION
    val replay = state.isDailyAlreadySolved
    Dialog(onDismissRequest = { open = false }) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Помощь в задаче",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!moveShown) HelpOption(
                        title = if (offerPiece) "Подсказать фигуру" else "Показать ход",
                        consequence = when {
                            replay -> "Повтор без изменения рейтинга"
                            !offerPiece || state.moveRevealedInExercise -> "Без прибавки к рейтингу"
                            else -> "Прибавка за решение будет меньше"
                        },
                        onClick = { open = false; if (better) onBetterMove() else onHint() }
                    )
                    HelpOption(
                        if (state.currentStepNumber > 1) "Показать оставшуюся линию" else "Показать всю линию",
                        if (replay) "Просмотр без изменения рейтинга" else "Без прибавки. Не считается решённой самостоятельно.",
                        onClick = { open = false; onReveal() }
                    )
                }
                Button(
                    onClick = { open = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("К доске")
                }
            }
        }
    }
}

@Composable
private fun HelpOption(title: String, consequence: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.heightIn(min = 48.dp).padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(consequence, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
