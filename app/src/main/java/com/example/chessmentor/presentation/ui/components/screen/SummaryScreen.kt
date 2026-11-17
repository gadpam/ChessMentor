// presentation/ui/screen/SummaryScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
import com.example.chessmentor.presentation.ui.components.EvaluationGraph
import com.example.chessmentor.presentation.viewmodel.BoardViewModel
import com.example.chessmentor.presentation.viewmodel.GameViewModel

/**
 * Экран сводки анализа партии
 * ✅ ОБНОВЛЕНО: Использует evaluations из GameViewModel (сохранённые в БД)
 */
@Composable
fun SummaryScreen(
    paddingValues: PaddingValues,
    game: Game,
    keyMoments: List<KeyMoment>,
    boardViewModel: BoardViewModel,
    gameViewModel: GameViewModel,  // ✅ ДОБАВЛЕНО
    onReviewClick: () -> Unit
) {
    val currentMoveIndex by boardViewModel.currentMoveIndex

    // Реактивно пересчитываем оценки при завершении загрузки/анализа
    val evaluations = remember(
        game.id,
        boardViewModel.evaluations.size,
        gameViewModel.selectedGameEvaluations.size,
        game.evaluationsJson
    ) {
        when {
            boardViewModel.evaluations.size > 1 -> boardViewModel.evaluations.toList()
            gameViewModel.selectedGameEvaluations.isNotEmpty() -> gameViewModel.selectedGameEvaluations.toList()
            game.hasEvaluations() -> game.getEvaluations()
            else -> emptyList()
        }
    }

    val hasRealEvaluations = evaluations.size > 1 && (game.hasEvaluations() || gameViewModel.hasEvaluations())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        item {
            SummaryHeader(game = game)
        }

        // Основные метрики
        if (game.isAnalyzed()) {
            item {
                GameMetricsCard(game = game)
            }
        }

        // ✅ ОБНОВЛЕНО: График оценки с проверкой данных
        item {
            EvaluationGraphCard(
                evaluations = evaluations,
                keyMoments = keyMoments,
                currentMoveIndex = currentMoveIndex,
                playerColor = game.playerColor,
                hasRealData = hasRealEvaluations,
                onMoveClick = { index ->
                    boardViewModel.goToMove(index)
                }
            )
        }

        // Ключевые моменты
        item {
            KeyMomentsCard(
                keyMoments = keyMoments,
                onMomentClick = { moment ->
                    boardViewModel.goToMove(moment.moveIndex)
                    onReviewClick()
                }
            )
        }

        // Кнопка разбора
        item {
            Button(
                onClick = onReviewClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Перейти к разбору партии", fontSize = 16.sp)
            }
        }

        // Предложение запустить или повторить анализ, если данных нет
        item {
            if (!hasRealEvaluations && game.id != null) {
                ReanalyzeHint(
                    isAnalyzed = game.isAnalyzed(),
                    onReanalyze = {
                        gameViewModel.analyzeGameWithProgress(game.id, forceReanalyze = true)
                    }
                )
            }
        }
    }

    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    AnalysisProgressDialog(
        progress = analysisProgress,
        onDismiss = { gameViewModel.clearAnalysisProgress() },
        onCancel = { gameViewModel.cancelAnalysis() }
    )
}

/**
 * ✅ НОВОЕ: Карточка с графиком оценки
 */
@Composable
private fun EvaluationGraphCard(
    evaluations: List<Int>,
    keyMoments: List<KeyMoment>,
    currentMoveIndex: Int,
    playerColor: ChessColor,
    hasRealData: Boolean,
    onMoveClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📈 График оценки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // ✅ НОВОЕ: Индикатор источника данных
                if (hasRealData) {
                    Surface(
                        color = Color(0xFF4CAF50).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Stockfish",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (evaluations.isNotEmpty()) {
                EvaluationGraph(
                    evaluations = evaluations,
                    keyMoments = keyMoments,
                    currentMoveIndex = currentMoveIndex,
                    playerColor = playerColor,
                    onMoveClick = onMoveClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    showMoveNumbers = true,
                    animateChanges = true
                )

                // ✅ НОВОЕ: Легенда
                Spacer(modifier = Modifier.height(8.dp))
                EvaluationGraphLegend()

            } else {
                // Нет данных
                NoEvaluationsPlaceholder()
            }
        }
    }
}

/**
 * ✅ НОВОЕ: Легенда графика
 */
@Composable
private fun EvaluationGraphLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = Color.White, label = "Белые")
        Spacer(modifier = Modifier.width(16.dp))
        LegendItem(color = Color.Black, label = "Чёрные")
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "• Клик = переход к ходу",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * ✅ НОВОЕ: Заглушка когда нет данных
 */
@Composable
private fun NoEvaluationsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Timeline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "График недоступен",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = "Переанализируйте партию",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * ✅ НОВОЕ: Подсказка о переанализе
 */
@Composable
private fun ReanalyzeHint(
    isAnalyzed: Boolean,
    onReanalyze: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAnalyzed) "Данные графика отсутствуют" else "Партия ещё не проанализирована",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isAnalyzed) "Обновите анализ движком Stockfish" else "Запустите анализ для поиска ошибок",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            TextButton(onClick = onReanalyze) {
                Text(if (isAnalyzed) "Обновить" else "Анализ")
            }
        }
    }
}

// ==================== СУЩЕСТВУЮЩИЕ КОМПОНЕНТЫ (без изменений) ====================

@Composable
private fun SummaryHeader(game: Game) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "📊 Сводка анализа",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Партия #${game.id ?: "?"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }
    }
}

@Composable
private fun GameMetricsCard(game: Game) {
    val accuracy = game.accuracy ?: 0.0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 Статистика партии",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Точность — большой индикатор
            AccuracyIndicator(accuracy = accuracy)

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Средняя потеря
                MetricItem(
                    label = "Ср. потеря",
                    value = String.format(java.util.Locale.US, "%.1f", (game.averageEvaluationLoss ?: 0) / 100.0),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Всего ошибок
                MetricItem(
                    label = "Ошибок",
                    value = "${game.totalMistakes}",
                    color = when {
                        game.totalMistakes > 5 -> Color(0xFFE53935)
                        game.totalMistakes > 2 -> Color(0xFFFF9800)
                        else -> Color(0xFF4CAF50)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            // Детализация ошибок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ErrorTypeItem(
                    emoji = "??",
                    label = "Зевки",
                    count = game.blundersCount,
                    color = Color(0xFFD32F2F)
                )
                ErrorTypeItem(
                    emoji = "?",
                    label = "Ошибки",
                    count = game.mistakesCount,
                    color = Color(0xFFF57C00)
                )
                ErrorTypeItem(
                    emoji = "?!",
                    label = "Неточности",
                    count = game.inaccuraciesCount,
                    color = Color(0xFFFBC02D)
                )
            }
        }
    }
}

@Composable
private fun AccuracyIndicator(accuracy: Double) {
    val color = getAccuracyColor(accuracy)
    val rating = getAccuracyRating(accuracy)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Круговой индикатор
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(100.dp)
        ) {
            CircularProgressIndicator(
                progress = { (accuracy / 100f).toFloat() },
                modifier = Modifier.fillMaxSize(),
                color = color,
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${accuracy.toInt()}%",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        Column {
            Text(
                text = "Точность",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = rating,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

private fun getAccuracyRating(accuracy: Double): String {
    return when {
        accuracy >= 95 -> "Превосходно!"
        accuracy >= 90 -> "Отлично"
        accuracy >= 80 -> "Хорошо"
        accuracy >= 70 -> "Неплохо"
        accuracy >= 60 -> "Средне"
        accuracy >= 50 -> "Слабо"
        else -> "Плохо"
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorTypeItem(
    emoji: String,
    label: String,
    count: Int,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = emoji,
            fontSize = 20.sp
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else Color.Gray.copy(alpha = 0.5f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getAccuracyColor(accuracy: Double): Color {
    return when {
        accuracy >= 90 -> Color(0xFF4CAF50)
        accuracy >= 80 -> Color(0xFF8BC34A)
        accuracy >= 70 -> Color(0xFFCDDC39)
        accuracy >= 60 -> Color(0xFFFFEB3B)
        accuracy >= 50 -> Color(0xFFFF9800)
        else -> Color(0xFFE53935)
    }
}

@Composable
fun KeyMomentsCard(
    keyMoments: List<KeyMoment>,
    onMomentClick: (KeyMoment) -> Unit
) {
    // Считаем статистику из keyMoments
    val brilliantCount = keyMoments.count { it.quality == MoveQuality.BRILLIANT }
    val greatCount = keyMoments.count { it.quality == MoveQuality.GREAT_MOVE }
    val bestCount = keyMoments.count { it.quality == MoveQuality.BEST_MOVE }
    val excellentCount = keyMoments.count { it.quality == MoveQuality.EXCELLENT }
    val goodCount = keyMoments.count { it.quality == MoveQuality.GOOD }
    val inaccuracyCount = keyMoments.count { it.quality == MoveQuality.INACCURACY }
    val mistakeCount = keyMoments.count { it.quality == MoveQuality.MISTAKE }
    val blunderCount = keyMoments.count { it.quality == MoveQuality.BLUNDER }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⭐ Качество ходов",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            // Хорошие ходы
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                KeyMomentItemCompact(
                    quality = MoveQuality.BRILLIANT,
                    count = brilliantCount
                )
                KeyMomentItemCompact(
                    quality = MoveQuality.GREAT_MOVE,
                    count = greatCount
                )
                KeyMomentItemCompact(
                    quality = MoveQuality.BEST_MOVE,
                    count = bestCount
                )
                KeyMomentItemCompact(
                    quality = MoveQuality.GOOD,
                    count = goodCount + excellentCount
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            // Ошибки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                KeyMomentItemCompact(
                    quality = MoveQuality.INACCURACY,
                    count = inaccuracyCount
                )
                KeyMomentItemCompact(
                    quality = MoveQuality.MISTAKE,
                    count = mistakeCount
                )
                KeyMomentItemCompact(
                    quality = MoveQuality.BLUNDER,
                    count = blunderCount
                )
            }

            // Список ключевых моментов
            val significantMoments = keyMoments.filter {
                it.quality in listOf(
                    MoveQuality.BRILLIANT,
                    MoveQuality.GREAT_MOVE,
                    MoveQuality.BLUNDER,
                    MoveQuality.MISSED_WIN,
                    MoveQuality.MISTAKE,
                    MoveQuality.INACCURACY
                )
            }

            if (significantMoments.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Ключевые моменты:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                significantMoments
                    .take(5)
                    .forEach { moment ->
                        KeyMomentRow(moment = moment, onClick = onMomentClick)
                    }
            }
        }
    }
}

@Composable
fun KeyMomentItemCompact(
    quality: MoveQuality,
    count: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(70.dp)
    ) {
        Text(
            text = quality.getEmoji(),
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) quality.getColor() else Color.Gray.copy(alpha = 0.5f)
        )

        Text(
            text = getShortName(quality),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun getShortName(quality: MoveQuality): String {
    return when (quality) {
        MoveQuality.BRILLIANT -> "Блест."
        MoveQuality.GREAT_MOVE -> "Отлич."
        MoveQuality.BEST_MOVE -> "Лучший"
        MoveQuality.EXCELLENT -> "Превос."
        MoveQuality.GOOD -> "Хорош."
        MoveQuality.BOOK -> "Теория"
        MoveQuality.INACCURACY -> "Неточ."
        MoveQuality.MISTAKE -> "Ошибка"
        MoveQuality.BLUNDER -> "Зевок"
        MoveQuality.MISSED_WIN -> "Упущ."
    }
}

@Composable
fun KeyMomentRow(
    moment: KeyMoment,
    onClick: (KeyMoment) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick(moment) },
        colors = CardDefaults.cardColors(
            containerColor = moment.quality.getColor().copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = moment.quality.getEmoji(),
                fontSize = 24.sp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ход ${moment.moveIndex / 2 + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = moment.san,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = formatEvaluationChange(moment.evaluationChange),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (moment.evaluationChange >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Перейти",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatEvaluationChange(change: Int): String {
    if (kotlin.math.abs(change) > 50_000) {
        return if (change > 0) "+Мат" else "-Мат"
    }
    val pawns = change / 100.0
    return when {
        change > 0 -> String.format(java.util.Locale.US, "+%.1f", pawns)
        change < 0 -> String.format(java.util.Locale.US, "%.1f", pawns)
        else -> "0.0"
    }
}