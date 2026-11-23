// presentation/ui/components/EvaluationBar.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessmentor.data.engine.ChessEngine
import kotlin.math.exp

/**
 * Горизонтальная шкала оценки позиции (над доской).
 *
 * По умолчанию белые слева, чёрные справа; при [flipped] = true стороны зеркалятся,
 * повторяя перевёрнутую ориентацию доски.
 */
@Composable
fun EvaluationBar(
    evaluation: Int,
    modifier: Modifier = Modifier,
    flipped: Boolean = false
) {
    val isMate = ChessEngine.isMateScore(evaluation)
    val whiteAdvantage = calculateWhiteAdvantage(evaluation, isMate)
    val targetLeftAdvantage = if (flipped) 1f - whiteAdvantage else whiteAdvantage

    val animatedLeftAdvantage by animateFloatAsState(
        targetValue = targetLeftAdvantage,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "advantageAnimation"
    )

    val evalText = ChessEngine.formatEvaluation(evaluation)

    val whiteColor = Color.White
    val blackColor = Color(0xFF1A1A1A)
    val leftColor = if (flipped) blackColor else whiteColor
    val rightColor = if (flipped) whiteColor else blackColor
    val borderColor = Color.Gray.copy(alpha = 0.3f)

    val targetOnLeft = targetLeftAdvantage >= 0.5f
    val showOnLeft = if (targetOnLeft) {
        animatedLeftAdvantage >= 0.35f
    } else {
        animatedLeftAdvantage > 0.65f
    }
    val textAlignment = if (showOnLeft) Alignment.CenterStart else Alignment.CenterEnd
    val textColor = when {
        showOnLeft && flipped -> Color.White
        showOnLeft && !flipped -> Color.Black
        !showOnLeft && flipped -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Оценка позиции: $evalText" }
    ) {
        Row(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(animatedLeftAdvantage.coerceAtLeast(0.02f))
                    .background(leftColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight((1f - animatedLeftAdvantage).coerceAtLeast(0.02f))
                    .background(rightColor)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(horizontal = 12.dp, vertical = 3.dp),
            contentAlignment = textAlignment
        ) {
            Text(
                text = evalText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                color = textColor
            )
        }
    }
}

private fun calculateWhiteAdvantage(evaluation: Int, isMate: Boolean): Float {
    if (isMate) {
        return if (evaluation > 0) 0.98f else 0.02f
    }

    val cappedEval = evaluation.coerceIn(-1000, 1000)
    val normalized = 1.0f / (1.0f + exp(-cappedEval / 300.0f).toFloat())

    return normalized.coerceIn(0.02f, 0.98f)
}
