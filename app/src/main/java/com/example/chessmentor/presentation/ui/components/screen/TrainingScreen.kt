package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.HintLevel
import com.example.chessmentor.presentation.viewmodel.SessionStats
import com.example.chessmentor.presentation.viewmodel.TrainingUiState
import com.example.chessmentor.presentation.viewmodel.TrainingViewModel
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

@Composable
fun TrainingScreen(
    paddingValues: PaddingValues,
    container: AppContainer,
    userId: Long,
    preferredTheme: String? = null,
    onUserUpdated: (User) -> Unit = {}
) {
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val viewModel: TrainingViewModel = viewModel(factory = factory)
    val currentOnUserUpdated by rememberUpdatedState(onUserUpdated)

    LaunchedEffect(userId) {
        if (userId > 0L) {
            val user = container.userRepository.findById(userId)
            if (user != null) {
                viewModel.init(user) { updated -> currentOnUserUpdated(updated) }
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

    LaunchedEffect(uiState.isErrorShake) {
        if (uiState.isErrorShake) {
            repeat(3) {
                shakeOffset.animateTo(10f, animationSpec = tween(50))
                shakeOffset.animateTo(-10f, animationSpec = tween(50))
            }
            shakeOffset.animateTo(0f, animationSpec = tween(50))
        }
    }

    LaunchedEffect(uiState.board.fen) {
        selectedSquare = null
        pendingPromotion = null
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                uiState.feedbackMessage?.let { loadingText ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    Scaffold(
        modifier = Modifier.padding(paddingValues),
        topBar = {
            SessionHeader(stats = uiState.sessionStats, sideToMove = uiState.playerSide)
        },
        bottomBar = {
            BottomControlPanel(
                uiState = uiState,
                onNextExercise = {
                    selectedSquare = null
                    pendingPromotion = null
                    viewModel.loadNextExercise()
                },
                onHint = { viewModel.onHintClicked() },
                onShowBetterMove = { viewModel.onShowBetterMoveClicked() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer { translationX = shakeOffset.value },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .fillMaxSize()
                ) {
                    val combinedHighlights = remember(selectedSquare, uiState.highlights) {
                        val set = uiState.highlights.mapNotNull {
                            try {
                                Square.fromValue(it.uppercase()).takeIf { sq -> sq != Square.NONE }
                            } catch (e: Exception) {
                                null
                            }
                        }.toMutableSet()
                        selectedSquare?.let { set.add(it) }
                        set
                    }

                    ChessBoard(
                        board = uiState.board,
                        arrows = uiState.arrows,
                        highlightedSquares = combinedHighlights,
                        theme = boardTheme,
                        onSquareClick = { square ->
                            if (uiState.isSolved || uiState.isInputLocked || uiState.isAnimatingPunishment) {
                                return@ChessBoard
                            }
                            val from = selectedSquare
                            val clickedPiece = uiState.board.getPiece(square)

                            when {
                                from == null -> {
                                    if (clickedPiece.pieceSide == uiState.playerSide) {
                                        selectedSquare = square
                                    }
                                }
                                square == from -> {
                                    selectedSquare = null
                                }
                                // Быстрое переключение на другую свою фигуру
                                clickedPiece.pieceSide == uiState.playerSide -> {
                                    selectedSquare = square
                                }
                                // Ход пешкой на последнюю горизонталь -> диалог выбора фигуры превращения
                                ChessMoveUtils.isPawnPromotion(uiState.board, from, square) -> {
                                    pendingPromotion = from to square
                                    selectedSquare = null
                                }
                                else -> {
                                    val move = ChessMoveUtils.createMove(from, square, uiState.playerSide)
                                    selectedSquare = null
                                    viewModel.onMoveMade(move)
                                }
                            }
                        },
                        flipped = uiState.playerSide == Side.BLACK,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (uiState.isInputLocked || uiState.isAnimatingPunishment) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Transparent)
                                .clickable(enabled = false) {}
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                UnifiedFeedbackPanel(uiState = uiState)
            }
        }
    }

    // Диалог выбора фигуры при превращении пешки
    pendingPromotion?.let { (from, to) ->
        PromotionPickerDialog(
            side = uiState.playerSide,
            onPieceSelected = { pieceType ->
                pendingPromotion = null
                val promotionMove = ChessMoveUtils.createMove(
                    from = from,
                    to = to,
                    side = uiState.playerSide,
                    promotion = pieceType
                )
                viewModel.onMoveMade(promotionMove)
            },
            onDismiss = { pendingPromotion = null }
        )
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
    val message = uiState.feedbackMessage

    AnimatedContent(
        targetState = Triple(message, analysis, uiState.isSolved),
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "FeedbackTransition"
    ) { (msg, errAnalysis, isSolved) ->
        if (msg != null || errAnalysis != null) {
            val isError = !isSolved && errAnalysis != null

            val (containerColor, contentColor, icon) = when {
                isSolved -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), Icons.Default.EmojiEvents)
                isError -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Default.Warning)
                else -> Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Default.Info
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = containerColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = msg ?: "Ошибка",
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (isError && errAnalysis?.tacticalMotif != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = contentColor.copy(alpha = 0.2f)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                null,
                                Modifier.size(16.dp),
                                tint = contentColor.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Мотив: ${errAnalysis.tacticalMotif.russianName}",
                                color = contentColor.copy(alpha = 0.9f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(stats: SessionStats, sideToMove: Side) {
    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✔ ${stats.solved}",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50),
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "✖ ${stats.failed}",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE57373),
                    fontSize = 16.sp
                )
            }
            Surface(
                color = if (sideToMove == Side.WHITE) Color.White else Color.Black,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ) {
                Text(
                    text = if (sideToMove == Side.WHITE) " Ход белых " else " Ход чёрных ",
                    color = if (sideToMove == Side.WHITE) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
    onShowBetterMove: () -> Unit
) {
    Surface(
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (uiState.showBetterMoveHint &&
                !uiState.betterMoveShown &&
                !uiState.isSolved &&
                !uiState.isAnimatingPunishment
            ) {
                OutlinedButton(
                    onClick = onShowBetterMove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Visibility, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Показать правильный ход")
                }
                Spacer(Modifier.height(12.dp))
            }

            if (uiState.isSolved) {
                Button(
                    onClick = onNextExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Далее", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            } else if (uiState.currentMistakeAnalysis == null) {
                Button(
                    onClick = onHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !uiState.isAnimatingPunishment && !uiState.isInputLocked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (uiState.hintLevel) {
                            HintLevel.NONE -> MaterialTheme.colorScheme.primary
                            HintLevel.PIECE_HIGHLIGHT -> MaterialTheme.colorScheme.tertiary
                            HintLevel.FULL_SOLUTION -> MaterialTheme.colorScheme.secondary
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (uiState.hintLevel) {
                            HintLevel.NONE -> "Подсказка"
                            HintLevel.PIECE_HIGHLIGHT -> "Куда ходить?"
                            HintLevel.FULL_SOLUTION -> "Решение"
                        },
                        fontSize = 16.sp
                    )
                }
            }

            if (uiState.similarExercisesQueued > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Серия задач: ${uiState.similarExercisesQueued} шт.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
