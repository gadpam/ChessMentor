// presentation/ui/components/MoveQualityBadge.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality

/**
 * Значок качества хода — отображается на доске в углу поля последнего хода
 *
 * Как в Chess.com Mobile — маленький кружок с символом
 */
@Composable
fun MoveQualityBadge(
    mistakeType: MistakeType? = null,
    moveQuality: MoveQuality? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val palette = LocalChessPalette.current
    // Preserve precedence when both classifiers provide a grade for this move.
    val quality = when {
        mistakeType == MistakeType.BLUNDER || moveQuality == MoveQuality.BLUNDER -> MoveQuality.BLUNDER
        moveQuality == MoveQuality.MISSED_WIN -> MoveQuality.MISSED_WIN
        mistakeType == MistakeType.MISTAKE || moveQuality == MoveQuality.MISTAKE -> MoveQuality.MISTAKE
        mistakeType == MistakeType.INACCURACY -> MoveQuality.INACCURACY
        else -> moveQuality ?: return
    }
    val symbol = when (quality) {
        MoveQuality.BLUNDER -> "??"
        MoveQuality.MISTAKE -> "?"
        MoveQuality.INACCURACY, MoveQuality.MISSED_WIN -> "?!"
        MoveQuality.BRILLIANT -> "!!"
        MoveQuality.GREAT_MOVE -> "!"
        MoveQuality.BEST_MOVE -> "✓"
        MoveQuality.GOOD -> "○"
        MoveQuality.BOOK -> null
        MoveQuality.EXCELLENT -> return // No board marker for this grade.
    }

    Box(
        modifier = modifier
            .size(size)
            .background(palette.accent(quality), CircleShape)
            .border(2.dp, palette.onAccent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (symbol == null) {
            Icon(MoveQuality.BOOK.getIcon(), contentDescription = MoveQuality.BOOK.getDisplayName(),
                tint = palette.onAccent, modifier = Modifier.size(size * 0.6f))
        } else Text(
            text = symbol,
            fontSize = (size.value * 0.45).sp,
            fontWeight = FontWeight.Bold,
            color = palette.onAccent
        )
    }
}

/**
 * Получить значок качества для отображения на доске
 *
 * Возвращает Composable или null если значок не нужен
 */
@Composable
fun getMoveQualityBadge(
    mistakeType: MistakeType?,
    moveQuality: MoveQuality?,
    size: Dp = 24.dp
): @Composable (() -> Unit)? {
    if (mistakeType == null && moveQuality == null) return null

    return {
        MoveQualityBadge(
            mistakeType = mistakeType,
            moveQuality = moveQuality,
            size = size
        )
    }
}