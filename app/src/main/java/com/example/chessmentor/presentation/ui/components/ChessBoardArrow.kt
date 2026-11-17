package com.example.chessmentor.presentation.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Модель данных для стрелки.
 * Используется и в Тренировке, и в Анализе.
 */
data class BoardArrow(
    val from: Square,
    val to: Square,
    val color: Color
)

/**
 * Парсинг UCI (например, e2e4 или e7e8q) в стрелку.
 */
fun parseBestMoveToArrow(uciMove: String?, color: Color = Color(0xAA4CAF50)): BoardArrow? {
    if (uciMove == null || uciMove.length < 4) return null
    val trimmed = uciMove.trim()
    if (!ChessMoveUtils.isUciMove(trimmed)) return null
    return try {
        val from = Square.fromValue(trimmed.substring(0, 2).uppercase())
        val to = Square.fromValue(trimmed.substring(2, 4).uppercase())
        if (from == Square.NONE || to == Square.NONE || from == to) null
        else BoardArrow(from, to, color)
    } catch (e: Exception) {
        null
    }
}

/**
 * Парсинг хода в формате SAN (Nf3, Qxd5, O-O, e8=Q+) ИЛИ UCI (g1f3) в стрелку по FEN до хода.
 */
fun parseMoveToArrow(
    moveNotation: String?,
    fenBefore: String?,
    color: Color = Color(0xAA4CAF50)
): BoardArrow? {
    if (moveNotation.isNullOrBlank()) return null
    parseBestMoveToArrow(moveNotation, color)?.let { return it }

    if (fenBefore.isNullOrBlank()) return null
    val uci = ChessMoveUtils.sanOrUciToUci(moveNotation, fenBefore) ?: return null
    return parseBestMoveToArrow(uci, color)
}

/**
 * Универсальная функция рисования стрелки на Canvas.
 */
fun DrawScope.drawBoardArrow(
    arrow: BoardArrow,
    squareSize: Float,
    flipped: Boolean,
    alpha: Float = 0.8f
) {
    val startFile = if (flipped) 7 - arrow.from.file.ordinal else arrow.from.file.ordinal
    val startRank = if (flipped) arrow.from.rank.ordinal else 7 - arrow.from.rank.ordinal
    val endFile = if (flipped) 7 - arrow.to.file.ordinal else arrow.to.file.ordinal
    val endRank = if (flipped) arrow.to.rank.ordinal else 7 - arrow.to.rank.ordinal

    val startX = startFile * squareSize + squareSize / 2
    val startY = startRank * squareSize + squareSize / 2
    val endX = endFile * squareSize + squareSize / 2
    val endY = endRank * squareSize + squareSize / 2

    val color = arrow.color
    val strokeWidth = squareSize * 0.15f
    val arrowHeadSize = squareSize * 0.45f

    val angle = atan2(endY - startY, endX - startX)

    val arrowX1 = endX - arrowHeadSize * cos(angle - Math.PI / 6)
    val arrowY1 = endY - arrowHeadSize * sin(angle - Math.PI / 6)
    val arrowX2 = endX - arrowHeadSize * cos(angle + Math.PI / 6)
    val arrowY2 = endY - arrowHeadSize * sin(angle + Math.PI / 6)

    val lineEndX = endX - (arrowHeadSize * 0.6f) * cos(angle)
    val lineEndY = endY - (arrowHeadSize * 0.6f) * sin(angle)

    drawLine(
        color = color,
        start = Offset(startX, startY),
        end = Offset(lineEndX.toFloat(), lineEndY.toFloat()),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
        alpha = alpha
    )

    val path = Path().apply {
        moveTo(endX, endY)
        lineTo(arrowX1.toFloat(), arrowY1.toFloat())
        lineTo(arrowX2.toFloat(), arrowY2.toFloat())
        close()
    }
    drawPath(path, color, alpha = alpha)
}
