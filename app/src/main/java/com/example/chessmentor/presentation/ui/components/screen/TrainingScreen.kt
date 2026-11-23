package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.painter.rememberVectorPainter
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.vectorResource
import com.example.chessmentor.R
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.SessionSummary
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.example.chessmentor.presentation.ui.components.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.ListSkeleton
import com.example.chessmentor.presentation.ui.components.SoundManager
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.HintLevel
import com.example.chessmentor.presentation.viewmodel.SessionStats
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
                if (initialModeKey != null) {
                    currentOnModeConsumed()
                }
            }
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

    Scaffold(
        modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onLeaveTraining) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Выйти из тренировки")
                    }
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            !uiState.hasActiveExercise -> {
                val isFromGame = uiState.mode is TrainingMode.FromGame && onReturnToGame != null
                val action = when {
                    isFromGame -> { onReturnToGame?.invoke() to "Вернуться к разбору партии" }
                    uiState.hasNoUserMistakes -> { onNavigateToUpload to "Загрузить партию (PGN)" }
                    else -> { { viewModel.selectMode(TrainingMode.All) } to "Открыть общий тактический банк" }
                }
                BrandEmptyState(
                    painter = rememberVectorPainter(vectorResource(R.drawable.ic_empty_training)),
                    title = uiState.feedbackMessage ?: "Нет доступных задач в выбранном режиме.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    onAction = action.first,
                    actionLabel = action.second
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Прогресс структурированной сессии (10 задач)
                    if (uiState.mode is TrainingMode.SmartSession && uiState.sessionStepNumber > 0) {
                        SmartSessionProgressBar(uiState = uiState)
                    }

                    // Компактная информационная строка текущей задачи
                    ExerciseMetaStrip(uiState = uiState)

                    // Шахматная доска
                    val solveBorderColor by animateColorAsState(
                        if (uiState.isSolved) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = tween(300)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer { translationX = shakeOffset.value }
                            .border(
                                width = (3f * solvePulse.value).dp,
                                color = solveBorderColor.copy(alpha = 0.3f + 0.7f * solvePulse.value),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
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
                            onSquareClick = if (canInteract) { square ->
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
                            } else {
                                null
                            },
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }

                    // Панель обратной связи / подсказки / объяснения ошибки
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        UnifiedFeedbackPanel(uiState = uiState)
                    }
                }
            }
        }
    }

    pendingPromotion?.let { (from, to) ->
        val promoSide = if (uiState.isFreeAnalysisMode) uiState.board.sideToMove else uiState.playerSide
        val options = remember(promoSide) {
            if (promoSide == Side.WHITE) {
                listOf(
                    Triple(PieceType.QUEEN, "♕", "Ферзь"),
                    Triple(PieceType.ROOK, "♖", "Ладья"),
                    Triple(PieceType.BISHOP, "♗", "Слон"),
                    Triple(PieceType.KNIGHT, "♘", "Конь")
                )
            } else {
                listOf(
                    Triple(PieceType.QUEEN, "♛", "Ферзь"),
                    Triple(PieceType.ROOK, "♜", "Ладья"),
                    Triple(PieceType.BISHOP, "♝", "Слон"),
                    Triple(PieceType.KNIGHT, "♞", "Конь")
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { pendingPromotion = null }
            ) {
                options.forEach { (pieceType, symbol, title) ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 48.dp),
                        text = { Text("$symbol $title", fontWeight = FontWeight.Medium) },
                        onClick = {
                            pendingPromotion = null
                            val promotionMove = ChessMoveUtils.createMove(
                                from = from,
                                to = to,
                                side = promoSide,
                                promotion = pieceType
                            )
                            viewModel.onMoveMade(promotionMove)
                        }
                    )
                }
            }
        }
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
        TextButton(onClick = { choosingTheme = false; expanded = true }, enabled = enabled) {
            Column {
                Text("Режим тренировки", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selectedMode.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Сменить режим")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (choosingTheme) {
                DropdownMenuItem(text = { Text("Назад к режимам") }, onClick = { choosingTheme = false },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) })
                HorizontalDivider()
            }
            val modes = if (choosingTheme) {
                TrainingMode.selectableThemes.map { TrainingMode.ByTheme(it) }
            } else {
                listOfNotNull((selectedMode as? TrainingMode.FromGame)?.copy(focusMoveNumber = null),
                    TrainingMode.Daily, TrainingMode.SmartSession, TrainingMode.All, TrainingMode.MyMistakes,
                    TrainingMode.WeakPatterns, TrainingMode.PatternVision, TrainingMode.DeepCalculation, TrainingMode.Defense)
            }
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(if (mode == TrainingMode.WeakPatterns && dueReviewCount > 0)
                        "${mode.displayName} · повторить: $dueReviewCount" else mode.displayName) },
                    trailingIcon = if (mode == selectedMode || (mode is TrainingMode.FromGame && selectedMode is TrainingMode.FromGame)) {
                        { Icon(Icons.Default.Check, contentDescription = "Выбрано") }
                    } else null,
                    onClick = { choose(mode) }
                )
            }
            if (!choosingTheme) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("По тактической теме") }, onClick = { choosingTheme = true },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
        }
    }
}

@Composable
private fun SmartSessionProgressBar(uiState: TrainingUiState) {
    val total = uiState.sessionTotalSteps.coerceAtLeast(1)
    val step = uiState.sessionStepNumber.coerceIn(1, total)
    val completed = if (uiState.isSolved || uiState.isSolutionRevealed) step else (step - 1)
    val progress = (completed.toFloat() / total.toFloat()).coerceIn(0.05f, 1f)
    val phaseText = uiState.sessionPhase?.let { phase ->
        if (uiState.isSolved || uiState.isSolutionRevealed) phase.displayName else phase.preSolveLabel
    } ?: "Тренировка"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Задача $step из $total • $phaseText",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Чисто: ${uiState.sessionStats.cleanFirstTrySolved}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun SessionSummaryCard(
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
                Text(
                    text = "Сессия из ${summary.totalExercises} задач завершена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStatPill(
                        label = "С 1-й попытки",
                        value = "${summary.cleanFirstTryCount}/${summary.totalExercises} (${summary.firstTryAccuracyPercent}%)"
                    )
                    SummaryStatPill(
                        label = "Ср. время",
                        value = "${summary.averageTimeSeconds} с"
                    )
                }

                if (summary.impulsiveMistakeCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Поспешных первых ходов: ${summary.impulsiveMistakeCount}",
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
                .height(48.dp),
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
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Закрепить слабые темы")
            }
        }

        TextButton(
            onClick = onSwitchToFreeTraining,
            modifier = Modifier.fillMaxWidth()
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
    Surface(
        onClick = { showDetails = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(headline, modifier = Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.Info, contentDescription = "Открыть пояснение")
        }
    }
    if (showDetails) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = showDetails,
                enter = tween(250),
                exit = tween(250)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Пояснение к задаче",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = details.joinToString("\n\n"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { showDetails = false },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("К доске")
                        }
                    }
                }
            }
        }
    }
}

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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = "Решено", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(stats.solved.toString(), fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, contentDescription = "Ошибки", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(stats.failed.toString(), fontWeight = FontWeight.Bold)
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
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 14.sp
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
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
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

@Composable
private fun BottomControlPanel(
    uiState: TrainingUiState,
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
    onPlayEngineBest: () -> Unit
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.checkUnavailable) {
                Button(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) { Text("Повторить проверку") }
                TextButton(onClick = onReturnFromCheck, modifier = Modifier.fillMaxWidth()) { Text("Вернуться к позиции") }
            } else if (isFinished) {
                // Пошаговый навигатор решения или управление свободным анализом со Stockfish
                if (uiState.isFreeAnalysisMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onUndoAnalysis,
                            enabled = uiState.canUndoAnalysisMove,
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить ход", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Назад", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onPlayEngineBest,
                            enabled = uiState.analysisBestMoveUci != null && !uiState.isAnalyzingPosition,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Показать лучший ход движка", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = uiState.analysisBestMoveSan?.let { "Ход $it" } ?: "Ход движка",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onToggleFreeAnalysis,
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Вернуться к решению", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("К линии", fontSize = 12.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.reviewSteps.size > 1) {
                            val canBack = uiState.reviewStepIndex > 0
                            val canForward = uiState.reviewStepIndex < uiState.reviewSteps.lastIndex
                            val stepLabel = uiState.reviewSteps
                                .getOrNull(uiState.reviewStepIndex)
                                ?.moveLabel
                                .orEmpty()

                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Analytics, contentDescription = "Анализ позиции", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Анализ", fontSize = 12.sp)
                        }
                    }
                }

                val isFinalSessionPuzzle = uiState.mode is TrainingMode.SmartSession &&
                    uiState.sessionStepNumber >= uiState.sessionTotalSteps

                Button(
                    onClick = onNextExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isFinalSessionPuzzle) "Подвести итоги сессии" else "Следующая задача",
                        fontSize = 16.sp,
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
                TrainingHelpControls(uiState, controlsEnabled, onHint, onShowBetterMove, onRevealSolution, onSkip)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Помощь")
        }
        OutlinedButton(onClick = onSkip, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(if (state.mode is TrainingMode.Daily) "К другим задачам" else "Пропустить")
        }
    }
    if (!open || !enabled) return

    val better = state.showBetterMoveHint && !state.betterMoveShown
    val offerPiece = !better && state.hintLevel == HintLevel.NONE
    val moveShown = state.betterMoveShown || state.hintLevel == HintLevel.FULL_SOLUTION
    val replay = state.isDailyAlreadySolved
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(
            visible = open,
            enter = tween(250),
            exit = tween(250)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Помощь в задаче",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!moveShown) HelpOption(
                            title = if (offerPiece) "Подсказать фигуру" else "Показать ход",
                            consequence = when {
                                replay -> "Повтор без изменения рейтинга"
                                !offerPiece || state.moveRevealedInExercise -> "Без прибавки к рейтингу"
                                else -> "Прибавка за решение будет меньше"
                            },
                            onClick = { open = false; if (better) onBetterMove() else onHint() }
                        )
                        HelpOption(if (state.currentStepNumber > 1) "Показать оставшуюся линию" else "Показать всю линию",
                            if (replay) "Просмотр без изменения рейтинга" else "Без прибавки. Не считается решённой самостоятельно.",
                            onClick = { open = false; onReveal() })
                    }
                    Button(
                        onClick = { open = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("К доске")
                    }
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
