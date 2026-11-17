// presentation/ui/components/MoveQualityBadge.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    // Определяем что показывать: ошибку или качество хода
    val badgeInfo = when {
        mistakeType == MistakeType.BLUNDER || moveQuality == MoveQuality.BLUNDER -> BadgeInfo(
            backgroundColor = Color(0xFFE53935),
            symbol = "??",
            textColor = Color.White
        )
        moveQuality == MoveQuality.MISSED_WIN -> BadgeInfo(
            backgroundColor = Color(0xFFE91E63),
            symbol = "?!",
            textColor = Color.White
        )
        mistakeType == MistakeType.MISTAKE || moveQuality == MoveQuality.MISTAKE -> BadgeInfo(
            backgroundColor = Color(0xFFFF9800),
            symbol = "?",
            textColor = Color.White
        )
        mistakeType == MistakeType.INACCURACY || moveQuality == MoveQuality.INACCURACY -> BadgeInfo(
            backgroundColor = Color(0xFFFFC107),
            symbol = "?!",
            textColor = Color.Black
        )
        moveQuality == MoveQuality.BRILLIANT -> BadgeInfo(
            backgroundColor = Color(0xFF26C6DA),
            symbol = "!!",
            textColor = Color.White
        )
        moveQuality == MoveQuality.GREAT_MOVE -> BadgeInfo(
            backgroundColor = Color(0xFF66BB6A),
            symbol = "!",
            textColor = Color.White
        )
        moveQuality == MoveQuality.BEST_MOVE -> BadgeInfo(
            backgroundColor = Color(0xFF9CCC65),
            symbol = "✓",
            textColor = Color.White
        )
        moveQuality == MoveQuality.GOOD -> BadgeInfo(
            backgroundColor = Color(0xFFAED581),
            symbol = "○",
            textColor = Color.White
        )
        moveQuality == MoveQuality.BOOK -> BadgeInfo(
            backgroundColor = Color(0xFFCE93D8),
            symbol = "📖",
            textColor = Color.White
        )
        else -> null
    }

    // Если нет информации — не показываем значок
    badgeInfo ?: return

    Box(
        modifier = modifier
            .size(size)
            .background(badgeInfo.backgroundColor, CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badgeInfo.symbol,
            fontSize = (size.value * 0.45).sp,
            fontWeight = FontWeight.Bold,
            color = badgeInfo.textColor
        )
    }
}

private data class BadgeInfo(
    val backgroundColor: Color,
    val symbol: String,
    val textColor: Color
)

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