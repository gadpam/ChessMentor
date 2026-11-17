package com.example.chessmentor.presentation.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.chessmentor.R
import com.github.bhlangonijr.chesslib.Piece

object ChessPieces {

    private data class CacheKey(val piece: Piece, val size: Int)

    // Кэшируем несколько размеров доски одновременно (например, мини-виджет + основная доска)
    private val bitmapCache = object : LruCache<CacheKey, Bitmap>(48) {}

    // Переиспользуемый Paint вместо аллокации на каждую фигуру в каждом кадре
    private val sharedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(
        drawScope: DrawScope,
        piece: Piece,
        x: Float,
        y: Float,
        size: Float,
        context: Context,
        alpha: Float = 1f
    ) {
        val targetSize = size.toInt()
        if (targetSize <= 0) return

        val bitmap = getBitmap(piece, context, targetSize) ?: return
        sharedPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()

        drawScope.drawContext.canvas.nativeCanvas.drawBitmap(
            bitmap,
            x,
            y,
            sharedPaint
        )
    }

    private fun getBitmap(piece: Piece, context: Context, size: Int): Bitmap? {
        val key = CacheKey(piece, size)
        bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val resId = when (piece) {
            Piece.WHITE_PAWN -> R.drawable.ic_pawn_white
            Piece.BLACK_PAWN -> R.drawable.ic_pawn_black
            Piece.WHITE_KNIGHT -> R.drawable.ic_knight_white
            Piece.BLACK_KNIGHT -> R.drawable.ic_knight_black
            Piece.WHITE_BISHOP -> R.drawable.ic_bishop_white
            Piece.BLACK_BISHOP -> R.drawable.ic_bishop_black
            Piece.WHITE_ROOK -> R.drawable.ic_rook_white
            Piece.BLACK_ROOK -> R.drawable.ic_rook_black
            Piece.WHITE_QUEEN -> R.drawable.ic_queen_white
            Piece.BLACK_QUEEN -> R.drawable.ic_queen_black
            Piece.WHITE_KING -> R.drawable.ic_king_white
            Piece.BLACK_KING -> R.drawable.ic_king_black
            else -> return null
        }

        val drawable = ContextCompat.getDrawable(context, resId) ?: return null
        val bitmap = drawable.toBitmap(width = size, height = size)
        bitmapCache.put(key, bitmap)
        return bitmap
    }
}
