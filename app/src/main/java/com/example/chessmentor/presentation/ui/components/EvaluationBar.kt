// presentation/ui/components/EvaluationBar.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.data.engine.ChessEngine
import kotlin.math.exp

/**
 * Горизонтальная шкала оценки позиции (над доской, как в Chess.com Mobile)
 *
 * Белые слева, чёрные справа. Оценка отображается на баре и всегда
 * смещается так, чтобы быть читаемой (не на границе цветов).
 *
 * @param evaluation Оценка в сантипешках (с точки зрения белых)
 * @param modifier Модификатор
 */
@Composable
fun EvaluationBar(
    evaluation: Int,
    modifier: Modifier = Modifier
) {
    val isMate = ChessEngine.isMateScore(evaluation)
    val whiteAdvantage = calculateWhiteAdvantage(evaluation, isMate)

    val animatedAdvantage by animateFloatAsState(
        targetValue = whiteAdvantage,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "advantageAnimation"
    )

    val evalText = ChessEngine.formatEvaluation(evaluation)

    val whiteColor = Color.White
    val blackColor = Color(0xFF1A1A1A)
    val borderColor = Color.Gray.copy(alpha = 0.3f)

    // Определяем где показывать текст и какой цвет использовать
    val textAlignment = getEvaluationAlignment(animatedAdvantage)
    val textColor = getEvaluationTextColor(animatedAdvantage)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
    ) {
        // Бар (белая и чёрная части)
        Row(modifier = Modifier.matchParentSize()) {
            // Белая часть (слева)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(animatedAdvantage.coerceAtLeast(0.02f))
                    .background(whiteColor)
            )
            // Чёрная часть (справа)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight((1f - animatedAdvantage).coerceAtLeast(0.02f))
                    .background(blackColor)
            )
        }

        // Оценка поверх бара
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(horizontal = 12.dp, vertical = 3.dp),
            contentAlignment = textAlignment
        ) {
            Text(
                text = evalText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

/**
 * Выравнивание текста оценки
 *
 * ПРАВИЛО: текст всегда должен быть ПОЛНОСТЬЮ на одной из частей бара,
 * никогда не на границе.
 *
 * - Если белая часть >= 50% → текст СЛЕВА (на белой части)
 * - Если чёрная часть > 50% → текст СПРАВА (на чёрной части)
 */
private fun getEvaluationAlignment(advantage: Float): Alignment {
    return if (advantage >= 0.5f) {
        Alignment.CenterStart  // Текст на белой части (слева)
    } else {
        Alignment.CenterEnd    // Текст на чёрной части (справа)
    }
}

/**
 * Цвет текста оценки
 *
 * - На белой части → чёрный текст
 * - На чёрной части → белый текст
 */
private fun getEvaluationTextColor(advantage: Float): Color {
    return if (advantage >= 0.5f) {
        Color.Black   // На белой части
    } else {
        Color.White   // На чёрной части
    }
}

/**
 * Расчёт преимущества белых (0.0 - 1.0)
 */
private fun calculateWhiteAdvantage(evaluation: Int, isMate: Boolean): Float {
    if (isMate) {
        return if (evaluation > 0) 0.98f else 0.02f
    }

    val cappedEval = evaluation.coerceIn(-1000, 1000)
    val normalized = 1.0f / (1.0f + exp(-cappedEval / 300.0f).toFloat())

    return normalized.coerceIn(0.02f, 0.98f)
}