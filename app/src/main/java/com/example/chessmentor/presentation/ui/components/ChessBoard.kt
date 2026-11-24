package com.example.chessmentor.presentation.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.min

data class BoardTheme(
    val lightSquare: Color,
    val darkSquare: Color,
    val highlightColor: Color,
    val lastMoveColor: Color,
    val selectedColor: Color = Color(0x9920A858),
    val name: String
)

object BoardThemes {
    val Classic = BoardTheme(
        lightSquare = Color(0xFFF0D9B5),
        darkSquare = Color(0xFFB58863),
        highlightColor = Color(0xAA64DD17),
        lastMoveColor = Color(0x80CDD26A),
        selectedColor = Color(0x9920A858),
        name = "Классическая"
    )
    val Blue = BoardTheme(
        lightSquare = Color(0xFFDEE3E6),
        darkSquare = Color(0xFF8CA2AD),
        highlightColor = Color(0xAA64DD17),
        lastMoveColor = Color(0x80FFF176),
        selectedColor = Color(0x991E88E5),
        name = "Синяя"
    )
    val Green = BoardTheme(
        lightSquare = Color(0xFFEBECD0),
        darkSquare = Color(0xFF779556),
        highlightColor = Color(0xAAFFD54F),
        lastMoveColor = Color(0x88F6F669),
        selectedColor = Color(0x99BBCB2B),
        name = "Зеленая"
    )
    val Wood = BoardTheme(
        lightSquare = Color(0xFFFFCE9E),
        darkSquare = Color(0xFFD18B47),
        highlightColor = Color(0xAA64DD17),
        lastMoveColor = Color(0x80FFB300),
        selectedColor = Color(0x992E7D32),
        name = "Дерево"
    )

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

private val FILE_LABELS = charArrayOf('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h')
private val LEGAL_MOVE_INDICATOR_COLOR = Color(0x48000000)
private val LEGAL_CAPTURE_RING_COLOR = Color(0x58000000)
private val CHECK_CENTER_COLOR = Color(0xD9E53935)
private val CHECK_MID_COLOR = Color(0x88C62828)

@Composable
fun ChessBoard(
    board: Board,
    highlightedSquares: Set<Square> = emptySet(),
    selectedSquare: Square? = null,
    legalMoveSquares: Set<Square> = emptySet(),
    legalCaptureSquares: Set<Square> = emptySet(),
    arrows: List<BoardArrow> = emptyList(),
    lastMove: Pair<Square, Square>? = null,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    theme: BoardTheme = BoardThemes.Classic,
    animateMove: Boolean = true,
    showCoordinates: Boolean = true,
    showLastMoveArrow: Boolean = false,
    onSquareClick: ((Square) -> Unit)? = null
) {
    val context = LocalContext.current
    val currentOnSquareClick by rememberUpdatedState(onSquareClick)

    val coordinatePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    var animatingPieceState by remember { mutableStateOf<Pair<Piece, Pair<Square, Square>>?>(null) }
    val animProgress = remember { Animatable(1f) }
    val animationProgress = animProgress.value

    LaunchedEffect(lastMove, board.fen, animateMove) {
        if (lastMove != null && animateMove) {
            val piece = board.getPiece(lastMove.second)
            if (piece != Piece.NONE) {
                animatingPieceState = piece to lastMove
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                )
                animatingPieceState = null
                return@LaunchedEffect
            }
        }
        animatingPieceState = null
        animProgress.snapTo(1f)
    }

    val checkedKingPiece = remember(board.fen) {
        val inCheck = runCatching { board.isKingAttacked }.getOrDefault(false)
        if (!inCheck) {
            Piece.NONE
        } else if (board.sideToMove == Side.WHITE) {
            Piece.WHITE_KING
        } else {
            Piece.BLACK_KING
        }
    }

    Box(modifier = modifier.background(Color.Transparent)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .let { base ->
                    if (onSquareClick != null) {
                        base.pointerInput(flipped) {
                            detectTapGestures { offset ->
                                val boardSize = min(size.width, size.height)
                                if (boardSize <= 0f) return@detectTapGestures
                                val squareSize = boardSize / 8f
                                val xOffset = (size.width - boardSize) / 2f
                                val yOffset = (size.height - boardSize) / 2f
                                val localX = offset.x - xOffset
                                val localY = offset.y - yOffset
                                if (localX < 0f || localY < 0f) return@detectTapGestures
                                val file = (localX / squareSize).toInt()
                                val rank = (localY / squareSize).toInt()
                                if (file in 0..7 && rank in 0..7) {
                                    val actualRank = if (flipped) rank else 7 - rank
                                    val actualFile = if (flipped) 7 - file else file
                                    currentOnSquareClick?.invoke(Square.squareAt(actualRank * 8 + actualFile))
                                }
                            }
                        }
                    } else {
                        base
                    }
                }
        ) {
            val boardSize = min(size.width, size.height)
            if (boardSize <= 0f) return@Canvas
            val squareSize = boardSize / 8f
            val xOffset = (size.width - boardSize) / 2f
            val yOffset = (size.height - boardSize) / 2f

            val lightCoordArgb = theme.darkSquare.toArgb()
            val darkCoordArgb = theme.lightSquare.toArgb()
            val drawCoords = showCoordinates && squareSize >= 18.dp.toPx()
            if (drawCoords) {
                coordinatePaint.textSize = (squareSize * 0.22f).coerceIn(8.sp.toPx(), 13.sp.toPx())
            }

            translate(left = xOffset, top = yOffset) {
                // 1. Клетки, подсветка последнего хода, подсказок, выбора и шаха королю
                for (rank in 0..7) {
                    for (file in 0..7) {
                        val actualRank = if (flipped) rank else 7 - rank
                        val actualFile = if (flipped) 7 - file else file
                        val square = Square.squareAt(actualRank * 8 + actualFile)
                        val isLight = (rank + file) % 2 == 0
                        val topLeft = Offset(file * squareSize, rank * squareSize)
                        val cellSize = Size(squareSize, squareSize)

                        // Базовый цвет поля
                        drawRect(
                            color = if (isLight) theme.lightSquare else theme.darkSquare,
                            topLeft = topLeft,
                            size = cellSize
                        )

                        // Последний ход на доске
                        if (lastMove != null && (square == lastMove.first || square == lastMove.second)) {
                            drawRect(
                                color = theme.lastMoveColor,
                                topLeft = topLeft,
                                size = cellSize
                            )
                        }

                        // Подсветка подсказки (фигура для хода)
                        if (square in highlightedSquares) {
                            drawRect(
                                color = theme.highlightColor,
                                topLeft = topLeft,
                                size = cellSize
                            )
                        }

                        // Выбранная игроком фигура (поверх lastMove)
                        if (selectedSquare != null && square == selectedSquare) {
                            drawRect(
                                color = theme.selectedColor,
                                topLeft = topLeft,
                                size = cellSize
                            )
                        }

                        // Радиальная красная подсветка короля под шахом
                        if (checkedKingPiece != Piece.NONE && board.getPiece(square) == checkedKingPiece) {
                            val center = Offset(
                                topLeft.x + squareSize / 2f,
                                topLeft.y + squareSize / 2f
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(CHECK_CENTER_COLOR, CHECK_MID_COLOR, Color.Transparent),
                                    center = center,
                                    radius = squareSize * 0.65f
                                ),
                                topLeft = topLeft,
                                size = cellSize
                            )
                        }

                        // Координаты доски (1–8 слева, a–h снизу с учётом переворота)
                        if (drawCoords) {
                            coordinatePaint.color = if (isLight) lightCoordArgb else darkCoordArgb
                            val pad = squareSize * 0.06f
                            if (file == 0) {
                                coordinatePaint.textAlign = Paint.Align.LEFT
                                val rankText = (actualRank + 1).toString()
                                drawContext.canvas.nativeCanvas.drawText(
                                    rankText,
                                    topLeft.x + pad,
                                    topLeft.y + coordinatePaint.textSize + pad * 0.5f,
                                    coordinatePaint
                                )
                            }
                            if (rank == 7) {
                                coordinatePaint.textAlign = Paint.Align.RIGHT
                                val fileText = FILE_LABELS[actualFile].toString()
                                drawContext.canvas.nativeCanvas.drawText(
                                    fileText,
                                    topLeft.x + squareSize - pad,
                                    topLeft.y + squareSize - pad,
                                    coordinatePaint
                                )
                            }
                        }
                    }
                }

                // 2. Индикаторы легальных ходов выбранной фигуры (точки и кольца взятий)
                if (legalMoveSquares.isNotEmpty() || legalCaptureSquares.isNotEmpty()) {
                    val dotRadius = squareSize * 0.155f
                    val ringStroke = Stroke(width = squareSize * 0.085f)
                    val ringRadius = squareSize * 0.42f

                    for (sq in legalMoveSquares) {
                        if (sq == Square.NONE) continue
                        val drawFile = if (flipped) 7 - sq.file.ordinal else sq.file.ordinal
                        val drawRank = if (flipped) sq.rank.ordinal else 7 - sq.rank.ordinal
                        val center = Offset(
                            (drawFile + 0.5f) * squareSize,
                            (drawRank + 0.5f) * squareSize
                        )
                        drawCircle(
                            color = LEGAL_MOVE_INDICATOR_COLOR,
                            radius = dotRadius,
                            center = center
                        )
                    }

                    for (sq in legalCaptureSquares) {
                        if (sq == Square.NONE) continue
                        val drawFile = if (flipped) 7 - sq.file.ordinal else sq.file.ordinal
                        val drawRank = if (flipped) sq.rank.ordinal else 7 - sq.rank.ordinal
                        val center = Offset(
                            (drawFile + 0.5f) * squareSize,
                            (drawRank + 0.5f) * squareSize
                        )
                        drawCircle(
                            color = LEGAL_CAPTURE_RING_COLOR,
                            radius = ringRadius,
                            center = center,
                            style = ringStroke
                        )
                    }
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
                            ChessPieces.draw(
                                this,
                                piece,
                                file * squareSize,
                                rank * squareSize,
                                squareSize,
                                context
                            )
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

                // 5. Стрелки (поверх фигур, чтобы подсказки и опровержения всегда хорошо читались)
                if (showLastMoveArrow && lastMove != null && arrows.isEmpty()) {
                    val arrow = BoardArrow(lastMove.first, lastMove.second, Color(0x70FFD54F))
                    drawBoardArrow(arrow, squareSize, flipped)
                }
                arrows.forEach { arrow ->
                    drawBoardArrow(arrow, squareSize, flipped)
                }
            }
        }
    }
}
