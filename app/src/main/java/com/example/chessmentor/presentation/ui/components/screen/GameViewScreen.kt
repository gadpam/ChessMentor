package com.example.chessmentor.presentation.ui.components.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.*
import com.example.chessmentor.presentation.viewmodel.BoardViewModel
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.github.bhlangonijr.chesslib.Board
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
    boardViewModel: BoardViewModel = viewModel()
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
            user = user
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
    user: User?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mistakes = gameViewModel.selectedGameMistakes
    val analyzedMoves = gameViewModel.selectedGameAnalyzedMoves
    val gameEvaluations = gameViewModel.selectedGameEvaluations

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

    val currentEvaluation = boardViewModel.getCurrentEvaluation()
    val currentMistake = boardViewModel.getCurrentMistake()
    val currentAnalyzedMove = boardViewModel.getCurrentAnalyzedMove()
    val moveNotations = boardViewModel.moveNotations

    // Извлекаем чистый SAN из нотации ("1. e4" -> "e4", "1... e5" -> "e5") для горизонтальной ленты
    val displayedMoves = remember(moveNotations.size) {
        moveNotations.map { notation ->
            notation.substringAfter(' ', notation)
        }
    }
    val totalMoves = boardViewModel.getTotalMoves()
    val hasRealEvaluations = boardViewModel.hasRealEvaluations()

    // Стрелка лучшего хода (поддерживает и SAN, и UCI за счёт FEN позиции до хода)
    val arrowsList = remember(currentMoveIndex, currentAnalyzedMove) {
        val bestMove = currentAnalyzedMove?.bestMove
        if (currentAnalyzedMove?.isMistake() == true && !bestMove.isNullOrBlank()) {
            val fenBefore = boardViewModel.getFenBeforeMove(currentMoveIndex)
            val arrow = parseMoveToArrow(bestMove, fenBefore)
            if (arrow != null) listOf(arrow) else emptyList()
        } else {
            emptyList()
        }
    }

    fun playFeedbackSoundForCurrentPosition() {
        val currentBoard = boardViewModel.board.value
        val idx = boardViewModel.currentMoveIndex.intValue
        val san = displayedMoves.getOrNull(idx).orEmpty()
        coroutineScope.launch {
            when {
                currentBoard.isMated -> soundManager.playCheckmate()
                currentBoard.isKingAttacked -> soundManager.playCheck()
                'x' in san -> soundManager.playCapture()
                idx >= 0 -> soundManager.playMove()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background)
    ) {
        EvaluationBarWithIndicator(
            evaluation = currentEvaluation,
            hasRealData = hasRealEvaluations,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
        )

        GameBoardWithBadgeAndArrow(
            board = board,
            currentMistake = currentMistake,
            currentAnalyzedMove = currentAnalyzedMove,
            highlightedSquares = highlightedSquares,
            lastMove = lastMove,
            arrows = arrowsList,
            isFlipped = game.playerColor == ChessColor.BLACK,
            theme = selectedTheme,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(horizontal = 12.dp)
        ) {
            MoveAnalysisCard(
                currentMistake = currentMistake,
                currentAnalyzedMove = currentAnalyzedMove,
                currentEvaluation = currentEvaluation,
                playerColor = game.playerColor
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            HorizontalMoveList(
                moves = displayedMoves,
                currentMoveIndex = currentMoveIndex,
                mistakes = mistakes.toList(),
                onMoveClick = { index ->
                    boardViewModel.goToMove(index)
                    playFeedbackSoundForCurrentPosition()
                },
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .height(36.dp)
            )
        }

        NavigationBar(
            currentMoveIndex = currentMoveIndex,
            totalMoves = totalMoves,
            onGoToStart = { boardViewModel.goToStart() },
            onGoToPrevious = {
                boardViewModel.goToPreviousMove()
                playFeedbackSoundForCurrentPosition()
            },
            onGoToNext = {
                boardViewModel.goToNextMove()
                playFeedbackSoundForCurrentPosition()
            },
            onGoToEnd = {
                boardViewModel.goToEnd()
                playFeedbackSoundForCurrentPosition()
            }
        )
    }
}

@Composable
private fun EvaluationBarWithIndicator(
    evaluation: Int,
    hasRealData: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        EvaluationBar(evaluation = evaluation, modifier = Modifier.fillMaxWidth())
        if (!hasRealData) {
            Text(
                text = "⚠ Приблизительные данные",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun GameBoardWithBadgeAndArrow(
    board: Board,
    currentMistake: Mistake?,
    currentAnalyzedMove: AnalyzedMove?,
    highlightedSquares: Set<Square>,
    lastMove: Pair<Square, Square>?,
    arrows: List<BoardArrow>,
    isFlipped: Boolean,
    theme: BoardTheme,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val boardSize = this.maxWidth

        ChessBoard(
            board = board,
            highlightedSquares = highlightedSquares,
            arrows = arrows,
            lastMove = lastMove,
            modifier = Modifier.fillMaxSize(),
            flipped = isFlipped,
            theme = theme,
            animateMove = true
        )

        if (lastMove != null) {
            val quality = currentAnalyzedMove?.quality
            if (quality != null && quality in listOf(
                    MoveQuality.BRILLIANT,
                    MoveQuality.GREAT_MOVE,
                    MoveQuality.BEST_MOVE,
                    MoveQuality.BOOK,
                    MoveQuality.BLUNDER,
                    MoveQuality.MISTAKE,
                    MoveQuality.INACCURACY,
                    MoveQuality.MISSED_WIN
                )
            ) {
                val badgeOffset = calculateBadgeOffset(lastMove.second, isFlipped, boardSize)
                MoveQualityBadge(
                    mistakeType = currentMistake?.mistakeType,
                    moveQuality = quality,
                    size = 28.dp,
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
    val badgeSize = 28.dp
    return Pair(sqSize * dFile + sqSize - badgeSize - 2.dp, sqSize * dRank + 2.dp)
}

@Composable
private fun MoveAnalysisCard(
    currentMistake: Mistake?,
    currentAnalyzedMove: AnalyzedMove?,
    currentEvaluation: Int,
    playerColor: ChessColor,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        when {
            currentMistake != null -> CompactMistakeCard(mistake = currentMistake)
            currentAnalyzedMove != null -> AnalyzedMoveInfoCard(
                analyzedMove = currentAnalyzedMove,
                isPlayerMove = currentAnalyzedMove.color == playerColor
            )
            else -> CompactPositionCard(evaluation = currentEvaluation, playerColor = playerColor)
        }
    }
}

@Composable
private fun AnalyzedMoveInfoCard(
    analyzedMove: AnalyzedMove,
    isPlayerMove: Boolean,
    modifier: Modifier = Modifier
) {
    val quality = analyzedMove.quality
    val moverDelta = analyzedMove.playerEvalChange
    // Изменение оценки показываем с точки зрения самого пользователя (если зевнул соперник — плюс для игрока)
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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = quality.getColor().copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = quality.getEmoji(), style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = quality.getColor()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = moveLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!analyzedMove.bestMove.isNullOrBlank() && quality.isBad()) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Лучше: ${analyzedMove.bestMove}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                analyzedMove.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (userDelta != 0 && quality != MoveQuality.BOOK) {
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val text = when {
                        isMateDelta -> if (userDelta > 0) "+Мат" else "-Мат"
                        userDelta > 0 -> String.format(Locale.US, "+%.1f", userDelta / 100.0)
                        else -> String.format(Locale.US, "%.1f", userDelta / 100.0)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (userDelta > 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                    if (!isMateDelta) {
                        Text(
                            text = "пешек",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationBar(
    currentMoveIndex: Int,
    totalMoves: Int,
    onGoToStart: () -> Unit,
    onGoToPrevious: () -> Unit,
    onGoToNext: () -> Unit,
    onGoToEnd: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onGoToStart, enabled = currentMoveIndex > -1) {
                Icon(Icons.Default.FirstPage, "В начало")
            }
            IconButton(onClick = onGoToPrevious, enabled = currentMoveIndex > -1) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
            }
            Text(
                "${(currentMoveIndex + 1).coerceAtLeast(0)}/$totalMoves",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onGoToNext, enabled = currentMoveIndex < totalMoves - 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд")
            }
            IconButton(onClick = onGoToEnd, enabled = currentMoveIndex < totalMoves - 1) {
                Icon(Icons.Default.LastPage, "В конец")
            }
        }
    }
}
