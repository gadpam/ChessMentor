package com.example.chessmentor.presentation.ui.components.screen

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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.SessionSummary
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
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
    onUserUpdated: (User) -> Unit = {}
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

    val uiState by viewModel.uiState
    var selectedSquare by remember { mutableStateOf<Square?>(null) }
    var pendingPromotion by remember { mutableStateOf<Pair<Square, Square>?>(null) }
    val shakeOffset = remember { Animatable(0f) }

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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
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
                EmptyTrainingState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    message = uiState.feedbackMessage ?: "Нет доступных задач в выбранном режиме.",
                    showUploadButton = uiState.hasNoUserMistakes,
                    showReturnToGameButton = uiState.mode is TrainingMode.FromGame && onReturnToGame != null,
                    onReturnToGame = { onReturnToGame?.invoke() },
                    onNavigateToUpload = onNavigateToUpload,
                    onSwitchToAllMode = { viewModel.selectMode(TrainingMode.All) }
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer { translationX = shakeOffset.value },
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
        PromotionPickerDialog(
            side = promoSide,
            onPieceSelected = { pieceType ->
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
    val eloSign = if (summary.netRatingDelta >= 0) "+${summary.netRatingDelta}" else "${summary.netRatingDelta}"

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
                    contentDescription = null,
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
                        label = "Рейтинг",
                        value = "$eloSign Эло"
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
            Icon(Icons.Default.PlayArrow, contentDescription = null)
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
        Text(
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
            uiState.reviewBox?.let { box ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Ур. $box/5",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

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
private fun EmptyTrainingState(
    modifier: Modifier,
    message: String,
    showUploadButton: Boolean,
    showReturnToGameButton: Boolean = false,
    onReturnToGame: () -> Unit = {},
    onNavigateToUpload: () -> Unit,
    onSwitchToAllMode: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (showReturnToGameButton) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(20.dp))
        if (showReturnToGameButton) {
            Button(
                onClick = onReturnToGame,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("← Вернуться к разбору партии")
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (showUploadButton) {
            Button(
                onClick = onNavigateToUpload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Загрузить партию (PGN)")
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = onSwitchToAllMode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Открыть общий тактический банк")
        }
    }
}

@Composable
private fun PromotionPickerDialog(
    side: Side,
    onPieceSelected: (PieceType) -> Unit,
    onDismiss: () -> Unit
) {
    val options = remember(side) {
        if (side == Side.WHITE) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Превращение пешки",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Выберите фигуру:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    options.forEach { (pieceType, symbol, title) ->
                        Surface(
                            onClick = { onPieceSelected(pieceType) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = symbol, fontSize = 28.sp)
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun UnifiedFeedbackPanel(uiState: TrainingUiState) {
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
    val queued = uiState.similarExercisesQueued.takeIf { it > 0 && uiState.mode is TrainingMode.All }
    val details = listOfNotNull(
        mainText,
        motifNow?.let { "Мотив: $it" },
        uiState.postSolveExplanation.takeIf { isFinished && !uiState.isFreeAnalysisMode },
        uiState.solutionSanLine.takeIf { uiState.isSolved && !uiState.isFreeAnalysisMode },
        queued?.let { "Закрепление темы: ещё $it шт. в очереди" }
    ).filter { it.isNotBlank() }
    if (details.isEmpty()) return

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
            Text(details.first(), modifier = Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.Info, contentDescription = "Открыть пояснение")
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Пояснение к задаче") },
            text = { Text(details.joinToString("\n\n"), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("К доске") } }
        )
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
            if (stats.netRatingDelta != 0) {
                val sign = if (stats.netRatingDelta > 0) "+" else ""
                val deltaColor = if (stats.netRatingDelta > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Text(
                    text = "$sign${stats.netRatingDelta}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = deltaColor
                )
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
                modifier = Modifier.size(36.dp)
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
            if (isFinished) {
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
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.FirstPage, contentDescription = "В начало", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = onReviewPrev,
                                    enabled = canBack,
                                    modifier = Modifier.size(34.dp)
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
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ход вперёд", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = onReviewLast,
                                    enabled = canForward,
                                    modifier = Modifier.size(34.dp)
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
                            Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
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
                        contentDescription = null
                    )
                }
            } else {
                val showBetterButton = uiState.showBetterMoveHint && !uiState.betterMoveShown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Основная кнопка: подсказка фигуры / стрелка хода
                    Button(
                        onClick = if (showBetterButton) onShowBetterMove else onHint,
                        enabled = controlsEnabled,
                        modifier = Modifier
                            .weight(1.35f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (uiState.hintLevel) {
                                HintLevel.NONE -> MaterialTheme.colorScheme.primary
                                HintLevel.PIECE_HIGHLIGHT -> MaterialTheme.colorScheme.tertiary
                                HintLevel.FULL_SOLUTION -> MaterialTheme.colorScheme.secondary
                            }
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (showBetterButton) {
                                Icons.Default.Visibility
                            } else {
                                Icons.AutoMirrored.Filled.HelpOutline
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                showBetterButton -> "Лучший ход"
                                uiState.hintLevel == HintLevel.NONE -> "Подсказка"
                                else -> "Куда ходить?"
                            },
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Показать полное решение (с демонстрацией комбинации)
                    OutlinedButton(
                        onClick = onRevealSolution,
                        enabled = controlsEnabled,
                        modifier = Modifier
                            .weight(0.95f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Решение",
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }

                    // Пропустить задачу
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = !uiState.isAnimatingIntro && !uiState.isAnimatingPunishment,
                        modifier = Modifier
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Пропустить задачу",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

        }
    }
}
