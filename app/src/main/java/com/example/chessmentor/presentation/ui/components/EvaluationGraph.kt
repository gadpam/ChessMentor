// presentation/ui/components/EvaluationGraph.kt

package com.example.chessmentor.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.KeyMoment
import kotlin.math.abs
import kotlin.math.exp

@Composable
fun EvaluationGraph(
    evaluations: List<Int>,
    keyMoments: List<KeyMoment>,
    currentMoveIndex: Int,
    playerColor: ChessColor,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showMoveNumbers: Boolean = true,
    animateChanges: Boolean = true
) {
    if (evaluations.isEmpty()) {
        EmptyGraphPlaceholder(modifier)
        return
    }

    var selectedMoment by remember { mutableStateOf<KeyMoment?>(null) }

    // +1 потому что evaluations[0] = начальная позиция, evaluations[1] = после первого хода
    val evaluationIndex = currentMoveIndex + 1

    val animatedCurrentIndex by animateFloatAsState(
        targetValue = evaluationIndex.toFloat(),
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "currentIndexAnimation"
    )

    val textMeasurer = rememberTextMeasurer()

    val whiteAdvantageColor = Color.White
    val blackAdvantageColor = Color(0xFF1A1A1A)
    val lineColor = MaterialTheme.colorScheme.primary
    val currentPositionColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val backgroundColor = MaterialTheme.colorScheme.surface

    Column(modifier = modifier) {
        GraphHeader(playerColor = playerColor)

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(color = backgroundColor, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(evaluations, keyMoments) {
                        detectTapGestures { offset ->
                            val clickedEvalIndex = findClosestEvalIndex(
                                offset = offset,
                                canvasWidth = size.width.toFloat(),
                                totalEvaluations = evaluations.size
                            )

                            // Конвертируем индекс оценки в индекс хода
                            val moveIndex = clickedEvalIndex - 1

                            val moment = keyMoments.find { it.moveIndex == moveIndex }
                            if (moment != null) {
                                selectedMoment = moment
                            } else {
                                selectedMoment = null
                                if (moveIndex >= 0) {
                                    onMoveClick(moveIndex)
                                }
                            }
                        }
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val padding = 40f
                val graphWidth = canvasWidth - padding * 2
                val graphHeight = canvasHeight - padding * 2
                val centerY = canvasHeight / 2

                drawEvaluationBackground(
                    evaluations = evaluations,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    padding = padding,
                    whiteColor = whiteAdvantageColor,
                    blackColor = blackAdvantageColor
                )

                drawGrid(
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    padding = padding,
                    gridColor = gridColor,
                    textMeasurer = textMeasurer,
                    showMoveNumbers = showMoveNumbers,
                    totalEvaluations = evaluations.size
                )

                drawLine(
                    color = gridColor.copy(alpha = 0.6f),
                    start = Offset(padding, centerY),
                    end = Offset(canvasWidth - padding, centerY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
                )

                drawEvaluationLine(
                    evaluations = evaluations,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    padding = padding,
                    lineColor = lineColor
                )

                drawKeyMomentMarkers(
                    keyMoments = keyMoments,
                    evaluations = evaluations,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    padding = padding,
                    selectedMoment = selectedMoment
                )

                // Текущая позиция
                if (animatedCurrentIndex >= 0 && evaluations.size > 1) {
                    val maxIndex = evaluations.size - 1
                    val clampedIndex = animatedCurrentIndex.coerceIn(0f, maxIndex.toFloat())
                    val currentX = padding + (clampedIndex / maxIndex) * graphWidth

                    drawLine(
                        color = currentPositionColor,
                        start = Offset(currentX, padding / 2),
                        end = Offset(currentX, canvasHeight - padding / 2),
                        strokeWidth = 3f
                    )

                    val evalIdx = clampedIndex.toInt().coerceIn(0, maxIndex)
                    val currentEval = evaluations[evalIdx]
                    val normalizedEval = normalizeEvaluation(currentEval)
                    val currentY = centerY - normalizedEval * (graphHeight / 2)

                    drawCircle(
                        color = currentPositionColor,
                        radius = 8f,
                        center = Offset(currentX, currentY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = Offset(currentX, currentY)
                    )
                }
            }

            selectedMoment?.let { moment ->
                KeyMomentTooltip(
                    moment = moment,
                    onDismiss = { selectedMoment = null },
                    onNavigate = {
                        onMoveClick(moment.moveIndex)
                        selectedMoment = null
                    }
                )
            }
        }
    }
}

@Composable
private fun GraphHeader(playerColor: ChessColor) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "График оценки",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = Color.White, label = "Белые")
            LegendItem(color = Color(0xFF1A1A1A), label = "Чёрные")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KeyMomentTooltip(
    moment: KeyMoment,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = moment.quality.getEmoji(), fontSize = 20.sp)
                    Column {
                        Text(
                            text = moment.quality.getDisplayName(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = moment.quality.getColor()
                        )
                        val moveNum = (moment.moveIndex / 2) + 1
                        val side = if (moment.moveIndex % 2 == 0) "" else "..."
                        Text(
                            text = "$moveNum$side ${moment.san}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("✕") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Изменение оценки",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatEvaluationChange(moment.evaluationChange),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = getEvaluationChangeColor(moment.evaluationChange)
                    )
                }
                Button(
                    onClick = onNavigate,
                    colors = ButtonDefaults.buttonColors(containerColor = moment.quality.getColor())
                ) {
                    Text("Перейти")
                }
            }

            // Показываем комментарий если есть
            moment.comment?.let { comment ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyGraphPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Нет данных для отображения",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============ Функции рисования ============

private fun DrawScope.drawEvaluationBackground(
    evaluations: List<Int>,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    padding: Float,
    whiteColor: Color,
    blackColor: Color
) {
    if (evaluations.size < 2) return

    val whitePath = Path()
    val blackPath = Path()

    whitePath.moveTo(padding, centerY)
    blackPath.moveTo(padding, centerY)

    evaluations.forEachIndexed { index, eval ->
        val x = padding + (index.toFloat() / (evaluations.size - 1)) * graphWidth
        val normalizedEval = normalizeEvaluation(eval)
        val y = centerY - normalizedEval * (graphHeight / 2)

        if (normalizedEval >= 0) {
            whitePath.lineTo(x, y)
            blackPath.lineTo(x, centerY)
        } else {
            whitePath.lineTo(x, centerY)
            blackPath.lineTo(x, y)
        }
    }

    whitePath.lineTo(padding + graphWidth, centerY)
    whitePath.close()
    blackPath.lineTo(padding + graphWidth, centerY)
    blackPath.close()

    drawPath(path = whitePath, color = whiteColor.copy(alpha = 0.8f))
    drawPath(path = blackPath, color = blackColor.copy(alpha = 0.8f))
}

private fun DrawScope.drawEvaluationLine(
    evaluations: List<Int>,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    padding: Float,
    lineColor: Color
) {
    if (evaluations.size < 2) return

    val path = Path()

    evaluations.forEachIndexed { index, eval ->
        val x = padding + (index.toFloat() / (evaluations.size - 1)) * graphWidth
        val normalizedEval = normalizeEvaluation(eval)
        val y = centerY - normalizedEval * (graphHeight / 2)

        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawGrid(
    canvasWidth: Float,
    canvasHeight: Float,
    padding: Float,
    gridColor: Color,
    textMeasurer: TextMeasurer,
    showMoveNumbers: Boolean,
    totalEvaluations: Int
) {
    val graphHeight = canvasHeight - padding * 2
    val graphWidth = canvasWidth - padding * 2
    val centerY = canvasHeight / 2

    val evalLevels = listOf(-10f, -5f, -3f, -1f, 1f, 3f, 5f, 10f)

    evalLevels.forEach { evalPawns ->
        val normalizedEval = normalizeEvaluation((evalPawns * 100).toInt())
        val y = centerY - normalizedEval * (graphHeight / 2)

        if (y > padding && y < canvasHeight - padding) {
            drawLine(
                color = gridColor,
                start = Offset(padding, y),
                end = Offset(canvasWidth - padding, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )

            val label = if (evalPawns > 0) "+${evalPawns.toInt()}" else "${evalPawns.toInt()}"
            val textResult = textMeasurer.measure(
                text = AnnotatedString(label),
                style = TextStyle(fontSize = 10.sp, color = gridColor)
            )
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(4f, y - textResult.size.height / 2)
            )
        }
    }

    // Номера ходов (не оценок)
    if (showMoveNumbers && totalEvaluations > 10) {
        val totalMoves = totalEvaluations - 1  // Первая оценка - начальная позиция
        val step = (totalMoves / 5).coerceAtLeast(10)
        var moveNum = step

        while (moveNum < totalMoves) {
            // +1 потому что evaluations[0] = начальная позиция
            val evalIndex = moveNum + 1
            val x = padding + (evalIndex.toFloat() / (totalEvaluations - 1)) * graphWidth

            drawLine(
                color = gridColor,
                start = Offset(x, padding),
                end = Offset(x, canvasHeight - padding),
                strokeWidth = 1f
            )

            // Показываем номер хода (полухода / 2 + 1)
            val displayMoveNum = (moveNum / 2) + 1
            val textResult = textMeasurer.measure(
                text = AnnotatedString("$displayMoveNum"),
                style = TextStyle(fontSize = 10.sp, color = gridColor)
            )
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(x - textResult.size.width / 2, canvasHeight - padding + 4f)
            )

            moveNum += step
        }
    }
}

private fun DrawScope.drawKeyMomentMarkers(
    keyMoments: List<KeyMoment>,
    evaluations: List<Int>,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    padding: Float,
    selectedMoment: KeyMoment?
) {
    if (evaluations.size < 2) return

    keyMoments.forEach { moment ->
        // Маркер должен быть на позиции ПОСЛЕ хода
        // moveIndex = 0 → evaluations[1] (после первого хода)
        val evalIndex = moment.moveIndex + 1

        if (evalIndex >= 0 && evalIndex < evaluations.size) {
            val x = padding + (evalIndex.toFloat() / (evaluations.size - 1)) * graphWidth
            val eval = evaluations[evalIndex]
            val normalizedEval = normalizeEvaluation(eval)
            val y = centerY - normalizedEval * (graphHeight / 2)

            val isSelected = selectedMoment?.moveIndex == moment.moveIndex
            val radius = if (isSelected) 12f else 8f
            val color = moment.quality.getColor()

            if (isSelected) {
                drawCircle(color = color.copy(alpha = 0.3f), radius = radius + 6f, center = Offset(x, y))
            }

            drawCircle(color = color, radius = radius, center = Offset(x, y))
            drawCircle(color = Color.White, radius = radius * 0.4f, center = Offset(x, y))
        }
    }
}

// ============ Вспомогательные функции ============

private fun normalizeEvaluation(centipawns: Int): Float {
    if (ChessEngine.isMateScore(centipawns)) {
        return if (centipawns > 0) 0.98f else -0.98f
    }

    val cappedEval = centipawns.coerceIn(-3000, 3000)
    val normalized = 1.0f / (1.0f + exp(-cappedEval / 400.0f).toFloat())

    return (normalized * 2 - 1).coerceIn(-0.98f, 0.98f)
}

/**
 * Находит ближайший индекс в массиве evaluations по клику
 */
private fun findClosestEvalIndex(offset: Offset, canvasWidth: Float, totalEvaluations: Int): Int {
    val padding = 40f
    val graphWidth = canvasWidth - padding * 2
    val relativeX = (offset.x - padding).coerceIn(0f, graphWidth)
    val maxIndex = totalEvaluations - 1
    return ((relativeX / graphWidth) * maxIndex).toInt().coerceIn(0, maxIndex)
}

private fun formatEvaluationChange(change: Int): String {
    if (ChessEngine.isMateScore(change) || abs(change) > 50_000) {
        return if (change > 0) "+Мат" else "-Мат"
    }
    val pawns = change / 100.0
    return when {
        change > 0 -> String.format(java.util.Locale.US, "+%.2f", pawns)
        change < 0 -> String.format(java.util.Locale.US, "%.2f", pawns)
        else -> "0.00"
    }
}

private fun getEvaluationChangeColor(change: Int): Color {
    return when {
        change > 100 -> Color(0xFF4CAF50)   // Зелёный для +1 пешка
        change > 0 -> Color(0xFF81C784)     // Светло-зелёный
        change < -100 -> Color(0xFFE53935)  // Красный для -1 пешка
        change < 0 -> Color(0xFFEF9A9A)     // Светло-красный
        else -> Color(0xFF9E9E9E)
    }
}