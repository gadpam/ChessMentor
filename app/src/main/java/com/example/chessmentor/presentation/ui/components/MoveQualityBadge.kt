// presentation/ui/components/MoveQualityBadge.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette

/**
 * Значок качества хода — отображается на доске в углу поля последнего хода.
 */
@Composable
fun MoveQualityBadge(
    mistakeType: MistakeType? = null,
    moveQuality: MoveQuality? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val palette = LocalChessPalette.current
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
        MoveQuality.EXCELLENT -> return
    }

    val scaledSymbolSize = with(LocalDensity.current) {
        (size * 0.46f).toSp()
    }

    Box(
        modifier = modifier
            .size(size)
            .background(palette.accent(quality), CircleShape)
            .border(1.5.dp, palette.onAccent, CircleShape)
            .semantics { contentDescription = quality.getDisplayName() },
        contentAlignment = Alignment.Center
    ) {
        if (symbol == null) {
            Icon(
                imageVector = MoveQuality.BOOK.getIcon(),
                contentDescription = MoveQuality.BOOK.getDisplayName(),
                tint = palette.onAccent,
                modifier = Modifier.size(size * 0.6f)
            )
        } else {
            Text(
                text = symbol,
                fontSize = scaledSymbolSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                color = palette.onAccent
            )
        }
    }
}
