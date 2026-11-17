package com.example.chessmentor.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.min

// Темы оставляем здесь (или можно вынести в BoardTheme.kt)
data class BoardTheme(
    val lightSquare: Color,
    val darkSquare: Color,
    val highlightColor: Color,
    val lastMoveColor: Color,
    val name: String
)

object BoardThemes {
    val Classic = BoardTheme(
        lightSquare = Color(0xFFF0D9B5),
        darkSquare = Color(0xFFB58863),
        highlightColor = Color(0xCC64DD17),
        lastMoveColor = Color(0x99FFD600),
        name = "Классическая"
    )
    val Blue = BoardTheme(Color(0xFFDEE3E6), Color(0xFF8CA2AD), Color(0xCC64DD17), Color(0x99FFEB3B), "Синяя")
    val Green = BoardTheme(Color(0xFFEBECD0), Color(0xFF779556), Color(0x99FFD54F), Color(0x99FFA726), "Зеленая")
    val Wood = BoardTheme(Color(0xFFFFCE9E), Color(0xFFD18B47), Color(0xCC64DD17), Color(0x99FF6F00), "Дерево")

    fun getAll() = listOf(Classic, Blue, Green, Wood)

    fun getByName(name: String?): BoardTheme {
        return when (name) {
            null, "Classic", Classic.name -> Classic
            "Blue", Blue.name -> Blue
            "Green", Green.name -> Green
            "Wood", Wood.name -> Wood
            else -> getAll().find { it.name == name } ?: Classic
        }
    }
}

data class PieceAnimation(
    val piece: Piece,
    val fromSquare: Square,
    val toSquare: Square,
    val progress: Float
)

@Composable
fun ChessBoard(
    board: Board,
    highlightedSquares: Set<Square> = emptySet(),
    arrows: List<BoardArrow> = emptyList(), // Используем класс из ChessBoardArrow.kt
    lastMove: Pair<Square, Square>? = null,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    theme: BoardTheme = BoardThemes.Classic,
    animateMove: Boolean = true,
    onSquareClick: ((Square) -> Unit)? = null
) {
    val context = LocalContext.current
    val currentOnSquareClick by rememberUpdatedState(onSquareClick)

    // Состояние анимации (используем Pair, чтобы не путаться с классами)
    var animatingPieceState by remember { mutableStateOf<Pair<Piece, Pair<Square, Square>>?>(null) }

    val animationProgress by animateFloatAsState(
        targetValue = if (animatingPieceState != null) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        finishedListener = { animatingPieceState = null },
        label = "piece_move"
    )

    LaunchedEffect(lastMove) {
        if (lastMove != null && animateMove) {
            val piece = board.getPiece(lastMove.second)
            if (piece != Piece.NONE) {
                animatingPieceState = piece to lastMove
            }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(flipped) {
                    detectTapGestures { offset ->
                        val boardSize = min(size.width, size.height)
                        val squareSize = boardSize / 8
                        val xOffset = (size.width - boardSize) / 2
                        val yOffset = (size.height - boardSize) / 2
                        val localX = offset.x - xOffset
                        val localY = offset.y - yOffset
                        val file = (localX / squareSize).toInt()
                        val rank = (localY / squareSize).toInt()
                        if (file in 0..7 && rank in 0..7) {
                            val actualRank = if (flipped) rank else 7 - rank
                            val actualFile = if (flipped) 7 - file else file
                            currentOnSquareClick?.invoke(Square.squareAt(actualRank * 8 + actualFile))
                        }
                    }
                }
        ) {
            val boardSize = min(size.width, size.height)
            val squareSize = boardSize / 8
            val xOffset = (size.width - boardSize) / 2
            val yOffset = (size.height - boardSize) / 2

            translate(left = xOffset, top = yOffset) {
                // 1. Клетки
                for (rank in 0..7) {
                    for (file in 0..7) {
                        val actualRank = if (flipped) rank else 7 - rank
                        val actualFile = if (flipped) 7 - file else file
                        val square = Square.squareAt(actualRank * 8 + actualFile)
                        val isLight = (rank + file) % 2 == 0
                        var squareColor = if (isLight) theme.lightSquare else theme.darkSquare

                        if (square in highlightedSquares) squareColor = theme.highlightColor
                        if (lastMove != null && (square == lastMove.first || square == lastMove.second)) {
                            squareColor = theme.lastMoveColor
                        }

                        drawRect(
                            color = squareColor,
                            topLeft = Offset(file * squareSize, rank * squareSize),
                            size = Size(squareSize, squareSize)
                        )
                    }
                }

                // 2. СТРЕЛКИ (Используем общую функцию из ChessBoardArrow.kt)
                // а) Последний ход
                if (lastMove != null) {
                    val arrow = BoardArrow(lastMove.first, lastMove.second, Color(0x80FFD54F))
                    drawBoardArrow(arrow, squareSize, flipped)
                }
                // б) Остальные стрелки (подсказки, ошибки)
                arrows.forEach { arrow ->
                    drawBoardArrow(arrow, squareSize, flipped)
                }

                // 3. Фигуры
                for (rank in 0..7) {
                    for (file in 0..7) {
                        val actualRank = if (flipped) rank else 7 - rank
                        val actualFile = if (flipped) 7 - file else file
                        val square = Square.squareAt(actualRank * 8 + actualFile)
                        val piece = board.getPiece(square)

                        val isAnimating = animatingPieceState?.let { (_, move) ->
                            move.second == square && animationProgress < 1f
                        } ?: false

                        if (piece != Piece.NONE && !isAnimating) {
                            ChessPieces.draw(this, piece, file * squareSize, rank * squareSize, squareSize, context)
                        }
                    }
                }

                // 4. Анимируемая фигура
                animatingPieceState?.let { (piece, move) ->
                    val fromSquare = move.first
                    val toSquare = move.second

                    val fromFile = if (flipped) 7 - fromSquare.file.ordinal else fromSquare.file.ordinal
                    val fromRank = if (flipped) fromSquare.rank.ordinal else 7 - fromSquare.rank.ordinal
                    val toFile = if (flipped) 7 - toSquare.file.ordinal else toSquare.file.ordinal
                    val toRank = if (flipped) toSquare.rank.ordinal else 7 - toSquare.rank.ordinal

                    val currentX = (fromFile * squareSize) + ((toFile - fromFile) * squareSize * animationProgress)
                    val currentY = (fromRank * squareSize) + ((toRank - fromRank) * squareSize * animationProgress)

                    ChessPieces.draw(this, piece, currentX, currentY, squareSize, context)
                }
            }
        }
    }
}