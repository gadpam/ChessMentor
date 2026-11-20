// presentation/ui/components/EvaluationGraph.kt
package com.example.chessmentor.presentation.ui.components

import com.example.chessmentor.presentation.ui.components.theme.ChessPalette
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.KeyMoment
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Интерактивный график оценки партии в шкале вероятности победы (`Win%` Lichess):
 * - клик и горизонтальное перетаскивание мгновенно переходят к соответствующему ходу (`onMoveClick`),
 *   включая маркеры ключевых моментов;
 * - при попадании на ключевой момент под графиком (не перекрывая кривую) показывается компактная
 *   карточка хода с кнопкой перехода к разбору на доске (`onOpenReview`);
 * - контрастная палитра одинаково хорошо читается в светлой и тёмной теме.
 */
@Composable
fun EvaluationGraph(
    evaluations: List<Int>,
    keyMoments: List<KeyMoment>,
    currentMoveIndex: Int,
    @Suppress("UNUSED_PARAMETER") playerColor: ChessColor,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 152.dp,
    showHeader: Boolean = true,
    showMoveNumbers: Boolean = true,
    @Suppress("UNUSED_PARAMETER") animateChanges: Boolean = true,
    startMoveNumber: Int = 1,
    firstMoveIsWhite: Boolean = true,
    onOpenReview: ((Int) -> Unit)? = null
) {
    if (evaluations.isEmpty()) {
        EmptyGraphPlaceholder(modifier = modifier, height = chartHeight)
        return
    }

    val currentOnMoveClick by rememberUpdatedState(onMoveClick)
    val momentsByIndex = remember(keyMoments) { keyMoments.associateBy { it.moveIndex } }

    // Синхронизируем выбранный ключевой момент с текущим ходом
    val activeMoment = momentsByIndex[currentMoveIndex]

    // evaluations[0] = стартовая позиция, evaluations[i + 1] = после полухода i
    val maxEvalIndex = (evaluations.size - 1).coerceAtLeast(0)
    val evaluationIndex = (currentMoveIndex + 1).coerceIn(0, maxEvalIndex)

    val animatedEvalIndex by animateFloatAsState(
        targetValue = evaluationIndex.toFloat(),
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "evalGraphCursor"
    )

    val textMeasurer = rememberTextMeasurer()

    val palette = LocalChessPalette.current
    val whiteAreaColor = Color(0xFFF3F5F8)
    val blackAreaColor = Color(0xFF232A34)
    val backdropColor = Color(0xFF7D8590).copy(alpha = 0.22f)
    val lineColor = MaterialTheme.colorScheme.primary
    val cursorColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    fun resolveMoveIndexFromX(x: Float, width: Float, snapToMarkers: Boolean): Int {
        if (evaluations.size <= 1) return -1
        val leftPad = 34f
        val rightPad = 14f
        val graphW = (width - leftPad - rightPad).coerceAtLeast(1f)
        val clampedX = (x - leftPad).coerceIn(0f, graphW)
        val rawEvalIdx = ((clampedX / graphW) * maxEvalIndex).roundToInt().coerceIn(0, maxEvalIndex)

        if (snapToMarkers && keyMoments.isNotEmpty()) {
            // При одиночном тапе притягиваем палец к ближайшему маркеру в радиусе ~16 px
            val snapRadiusPx = 22f
            val nearestMoment = keyMoments
                .filter { it.moveIndex + 1 in 0..maxEvalIndex }
                .minByOrNull { m ->
                    val mx = leftPad + ((m.moveIndex + 1).toFloat() / maxEvalIndex) * graphW
                    abs(mx - x)
                }
            if (nearestMoment != null) {
                val mx = leftPad + ((nearestMoment.moveIndex + 1).toFloat() / maxEvalIndex) * graphW
                if (abs(mx - x) <= snapRadiusPx) {
                    return nearestMoment.moveIndex
                }
            }
        }
        return rawEvalIdx - 1
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            GraphHeader()
            Spacer(modifier = Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(backdropColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(evaluations, keyMoments) {
                        detectTapGestures { offset ->
                            val targetMove = resolveMoveIndexFromX(
                                x = offset.x,
                                width = size.width.toFloat(),
                                snapToMarkers = true
                            )
                            currentOnMoveClick(targetMove)
                        }
                    }
                    .pointerInput(evaluations) {
                        var lastEmitted = Int.MIN_VALUE
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                val target = resolveMoveIndexFromX(
                                    x = start.x,
                                    width = size.width.toFloat(),
                                    snapToMarkers = false
                                )
                                lastEmitted = target
                                currentOnMoveClick(target)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val target = resolveMoveIndexFromX(
                                    x = change.position.x,
                                    width = size.width.toFloat(),
                                    snapToMarkers = false
                                )
                                if (target != lastEmitted) {
                                    lastEmitted = target
                                    currentOnMoveClick(target)
                                }
                            }
                        )
                    }
            ) {
                val leftPad = 34f
                val rightPad = 14f
                val topPad = 12f
                val bottomPad = if (showMoveNumbers) 24f else 12f
                val graphWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val graphHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val centerY = topPad + graphHeight / 2f

                drawEvaluationAreas(
                    evaluations = evaluations,
                    leftPad = leftPad,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    whiteColor = whiteAreaColor,
                    blackColor = blackAreaColor
                )

                drawGraphGrid(
                    leftPad = leftPad,
                    topPad = topPad,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    gridColor = gridColor,
                    labelColor = labelColor,
                    textMeasurer = textMeasurer,
                    showMoveNumbers = showMoveNumbers,
                    totalEvaluations = evaluations.size,
                    startMoveNumber = startMoveNumber,
                    firstMoveIsWhite = firstMoveIsWhite
                )

                // Линия равенства (0.0 / 50% Win%)
                drawLine(
                    color = labelColor.copy(alpha = 0.55f),
                    start = Offset(leftPad, centerY),
                    end = Offset(leftPad + graphWidth, centerY),
                    strokeWidth = 1.8f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                )

                drawEvaluationCurve(
                    evaluations = evaluations,
                    leftPad = leftPad,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    lineColor = lineColor
                )

                drawMomentDots(
                    palette = palette,
                    keyMoments = keyMoments,
                    evaluations = evaluations,
                    leftPad = leftPad,
                    graphWidth = graphWidth,
                    graphHeight = graphHeight,
                    centerY = centerY,
                    activeMoveIndex = currentMoveIndex
                )

                // Курсор текущей позиции
                if (evaluations.size > 1) {
                    val clamped = animatedEvalIndex.coerceIn(0f, maxEvalIndex.toFloat())
                    val cursorX = leftPad + (clamped / maxEvalIndex) * graphWidth
                    val evalVal = evaluations[clamped.roundToInt().coerceIn(0, maxEvalIndex)]
                    val cursorY = centerY - normalizeEvaluationByWinProb(evalVal) * (graphHeight / 2f)

                    drawLine(
                        color = cursorColor.copy(alpha = 0.85f),
                        start = Offset(cursorX, topPad),
                        end = Offset(cursorX, topPad + graphHeight),
                        strokeWidth = 2.5f
                    )
                    drawCircle(
                        color = cursorColor,
                        radius = 7.5f,
                        center = Offset(cursorX, cursorY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.5f,
                        center = Offset(cursorX, cursorY)
                    )
                }
            }
        }

        // Компактная информационная плашка выбранного ключевого момента (под графиком — не закрывает его!)
        if (activeMoment != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SelectedMomentStrip(
                moment = activeMoment,
                onOpenReview = onOpenReview?.let { cb -> { cb(activeMoment.moveIndex) } }
            )
        }
    }
}

@Composable
private fun GraphHeader() {
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
            HeaderLegendSwatch(color = Color(0xFFF3F5F8), label = "Белые")
            HeaderLegendSwatch(color = Color(0xFF232A34), label = "Чёрные")
        }
    }
}

@Composable
private fun HeaderLegendSwatch(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(2.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SelectedMomentStrip(
    moment: KeyMoment,
    onOpenReview: (() -> Unit)?
) {
    val accent = LocalChessPalette.current.accent(moment.quality)
    val userDelta = moment.userPerspectiveDelta()
    val deltaText = GameReviewHelper.formatSignedEvalDelta(userDelta)
    val whoSuffix = if (moment.isPlayerMove) "" else " (соперник)"

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = moment.formattedMove(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${moment.quality.getDisplayName()}$whoSuffix",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (userDelta != 0) {
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (userDelta > 0) LocalChessPalette.current.positive else LocalChessPalette.current.negative
                        )
                    }
                }
                val subtitle = when {
                    !moment.bestMove.isNullOrBlank() && moment.quality.isBad() && moment.bestMove != moment.san ->
                        "Сильнее было: ${moment.bestMove}"
                    !moment.comment.isNullOrBlank() -> moment.comment
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onOpenReview != null) {
                TextButton(
                    onClick = onOpenReview,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("На доске", style = MaterialTheme.typography.labelMedium)
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyGraphPlaceholder(
    modifier: Modifier,
    height: Dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Нет данных для отображения",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== ОТРИСОВКА ГРАФИКА ====================

private fun DrawScope.drawEvaluationAreas(
    evaluations: List<Int>,
    leftPad: Float,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    whiteColor: Color,
    blackColor: Color
) {
    if (evaluations.size < 2) return
    val maxIdx = (evaluations.size - 1).toFloat()

    val whitePath = Path().apply { moveTo(leftPad, centerY) }
    val blackPath = Path().apply { moveTo(leftPad, centerY) }

    var prevX = leftPad
    var prevNorm = normalizeEvaluationByWinProb(evaluations[0])

    evaluations.forEachIndexed { idx, eval ->
        val x = leftPad + (idx / maxIdx) * graphWidth
        val norm = normalizeEvaluationByWinProb(eval)
        val y = centerY - norm * (graphHeight / 2f)

        // Точная точка пересечения нуля, чтобы области белых и чёрных не «рвались»
        if (idx > 0 && (prevNorm > 0f && norm < 0f || prevNorm < 0f && norm > 0f)) {
            val t = abs(prevNorm) / (abs(prevNorm) + abs(norm))
            val crossX = prevX + (x - prevX) * t
            whitePath.lineTo(crossX, centerY)
            blackPath.lineTo(crossX, centerY)
        }

        if (norm >= 0f) {
            whitePath.lineTo(x, y)
            blackPath.lineTo(x, centerY)
        } else {
            whitePath.lineTo(x, centerY)
            blackPath.lineTo(x, y)
        }

        prevX = x
        prevNorm = norm
    }

    val endX = leftPad + graphWidth
    whitePath.lineTo(endX, centerY)
    whitePath.close()
    blackPath.lineTo(endX, centerY)
    blackPath.close()

    drawPath(path = whitePath, color = whiteColor.copy(alpha = 0.9f))
    drawPath(path = blackPath, color = blackColor.copy(alpha = 0.88f))
}

private fun DrawScope.drawEvaluationCurve(
    evaluations: List<Int>,
    leftPad: Float,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    lineColor: Color
) {
    if (evaluations.size < 2) return
    val maxIdx = (evaluations.size - 1).toFloat()
    val path = Path()

    evaluations.forEachIndexed { idx, eval ->
        val x = leftPad + (idx / maxIdx) * graphWidth
        val y = centerY - normalizeEvaluationByWinProb(eval) * (graphHeight / 2f)
        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawGraphGrid(
    leftPad: Float,
    topPad: Float,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    gridColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer,
    showMoveNumbers: Boolean,
    totalEvaluations: Int,
    startMoveNumber: Int,
    firstMoveIsWhite: Boolean
) {
    // Горизонтальные ориентиры +3 / 0 / -3 пешки (не перегружая мобильный экран)
    val evalMarksCp = listOf(300 to "+3", 0 to "0", -300 to "-3")
    for ((cp, label) in evalMarksCp) {
        val y = centerY - normalizeEvaluationByWinProb(cp) * (graphHeight / 2f)
        if (cp != 0) {
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + graphWidth, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
        }
        val measured = textMeasurer.measure(
            text = AnnotatedString(label),
            style = TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.8f))
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                (leftPad - measured.size.width - 4f).coerceAtLeast(2f),
                (y - measured.size.height / 2f).coerceIn(2f, topPad + graphHeight - measured.size.height)
            )
        )
    }

    // Вертикальные отметки номеров ходов
    if (showMoveNumbers && totalEvaluations > 8) {
        val totalPlies = totalEvaluations - 1
        val fullMoveStep = when {
            totalPlies <= 30 -> 5
            totalPlies <= 70 -> 10
            totalPlies <= 130 -> 15
            else -> 25
        }
        val plyStep = fullMoveStep * 2
        var ply = plyStep
        while (ply < totalPlies) {
            val evalIdx = ply + 1
            val x = leftPad + (evalIdx.toFloat() / (totalEvaluations - 1)) * graphWidth
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(x, topPad),
                end = Offset(x, topPad + graphHeight),
                strokeWidth = 1f
            )
            val fullMove = startMoveNumber + ((ply + if (firstMoveIsWhite) 0 else 1) / 2)
            val measured = textMeasurer.measure(
                text = AnnotatedString("$fullMove"),
                style = TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.85f))
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(x - measured.size.width / 2f, topPad + graphHeight + 3f)
            )
            ply += plyStep
        }
    }
}

private fun DrawScope.drawMomentDots(
    palette: ChessPalette,
    keyMoments: List<KeyMoment>,
    evaluations: List<Int>,
    leftPad: Float,
    graphWidth: Float,
    graphHeight: Float,
    centerY: Float,
    activeMoveIndex: Int
) {
    if (evaluations.size < 2) return
    val maxIdx = evaluations.size - 1

    keyMoments.forEach { moment ->
        val evalIndex = moment.moveIndex + 1
        if (evalIndex in 0..maxIdx) {
            val x = leftPad + (evalIndex.toFloat() / maxIdx) * graphWidth
            val y = centerY - normalizeEvaluationByWinProb(evaluations[evalIndex]) * (graphHeight / 2f)
            val isSelected = moment.moveIndex == activeMoveIndex
            val radius = if (isSelected) 9.5f else 6.5f
            val color = palette.accent(moment.quality)

            if (isSelected) {
                drawCircle(color = color.copy(alpha = 0.3f), radius = radius + 5f, center = Offset(x, y))
            }
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            drawCircle(color = Color.White, radius = radius * 0.38f, center = Offset(x, y))
        }
    }
}

/**
 * Единая шкала вероятности победы Lichess (`Win%` белых): `50% -> 0f`, `100% -> +0.96f`, `0% -> -0.96f`.
 */
private fun normalizeEvaluationByWinProb(evalWhiteCp: Int): Float {
    val winPct = GameMoveClassifier.winProbability(evalWhiteCp, ChessColor.WHITE)
    val unit = ((winPct - 50.0) / 50.0).toFloat()
    return (unit * 0.96f).coerceIn(-0.96f, 0.96f)
}
