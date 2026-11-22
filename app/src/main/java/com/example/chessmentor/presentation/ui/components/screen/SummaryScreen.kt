// presentation/ui/screen/SummaryScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.presentation.ui.components.util.MISSING_ACCURACY
import com.example.chessmentor.presentation.ui.components.util.formatGameAccuracy
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.pgn.PgnParser
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
    gameViewModel: GameViewModel,
    onReviewClick: () -> Unit,
    onPracticeGameMistakes: ((gameId: Long, focusMoveNumber: Int?) -> Unit)? = null
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

    val opponentSummary = remember(
        game.id,
        game.analyzedAt,
        boardViewModel.analyzedMoves.size,
        boardViewModel.evaluations.size,
        game.playerColor
    ) {
        boardViewModel.getSideSummary(game.playerColor.opposite())
    }

    val phaseAccuracy = remember(
        game.id,
        game.analyzedAt,
        boardViewModel.analyzedMoves.size,
        boardViewModel.moveNotations.size,
        game.playerColor
    ) {
        boardViewModel.getPhaseAccuracy(game.playerColor)
    }

    val cardInfo = remember(game.id, game.pgnData, game.playerColor, game.timeControl) {
        PgnParser.extractGameCardInfo(game)
    }

    val openingLabel = remember(game.id, boardViewModel.moveNotations.size, cardInfo.openingLabel) {
        boardViewModel.getDetectedOpeningLabel() ?: cardInfo.openingLabel
    }

    val coachReport = remember(
        game.id,
        game.analyzedAt,
        boardViewModel.analyzedMoves.size,
        boardViewModel.mistakes.size,
        phaseAccuracy,
        cardInfo.outcome
    ) {
        if (game.isAnalyzed()) {
            boardViewModel.getCoachReport(
                outcome = cardInfo.outcome,
                phaseAccuracy = phaseAccuracy
            )
        } else {
            null
        }
    }

    val trainableCount = remember(
        game.id,
        game.analyzedAt,
        boardViewModel.mistakes.size,
        boardViewModel.analyzedMoves.size,
        coachReport
    ) {
        coachReport?.trainableMistakesCount ?: boardViewModel.getTrainableMistakesCount()
    }

    // На графике отмечаем только действительно значимые моменты, не загромождая линию рутинными ходами
    val graphMoments = remember(keyMoments) {
        GameReviewHelper.filterKeyMoments(keyMoments, GameReviewHelper.MomentFilter.ALL)
    }

    val currentMoveLabel = boardViewModel.getCurrentMoveNotation()
    val currentMoveEval = evaluations.getOrNull(currentMoveIndex + 1)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок + метаданные PGN + распознанный дебют
        item {
            SummaryHeader(
                game = game,
                cardInfo = cardInfo,
                openingLabel = openingLabel
            )
        }

        // Быстрые действия: интерактивный разбор на доске + мост «Ошибки партии → Тренировка»
        item {
            SummaryActionRow(
                canPracticeMistakes = game.isAnalyzed() && trainableCount > 0 &&
                    game.id != null && onPracticeGameMistakes != null,
                trainableCount = trainableCount,
                onReviewClick = onReviewClick,
                onPracticeClick = {
                    val gid = game.id
                    if (gid != null) {
                        onPracticeGameMistakes?.invoke(gid, null)
                    }
                }
            )
        }

        // Тренерский вывод: переломный момент, фазы, цейтнот и упущенные шансы
        if (coachReport != null) {
            item {
                CoachSummaryCard(
                    report = coachReport,
                    onJumpToTurningPoint = { moveIndex ->
                        boardViewModel.goToMove(moveIndex)
                        onReviewClick()
                    }
                )
            }
        }

        // Основные метрики (точность игрока vs соперника + точность по фазам)
        if (game.isAnalyzed()) {
            item {
                GameMetricsCard(
                    game = game,
                    opponentSummary = opponentSummary,
                    phaseAccuracy = phaseAccuracy
                )
            }
        }

        // Интерактивный график оценки (тап + скраббинг + мгновенный переход на доску)
        item(key = "evaluation-graph") {
            EvaluationGraphCard(
                moveLabels = boardViewModel.moveNotations,
                evaluations = evaluations,
                keyMoments = graphMoments,
                currentMoveIndex = currentMoveIndex,
                currentMoveLabel = currentMoveLabel,
                currentMoveEval = currentMoveEval,
                playerColor = game.playerColor,
                hasRealData = hasRealEvaluations,
                startMoveNumber = boardViewModel.getStartMoveNumber(),
                firstMoveIsWhite = boardViewModel.isFirstMoveWhite(),
                onMoveClick = { index ->
                    boardViewModel.goToMove(index)
                },
                onOpenReviewAtMove = { index ->
                    boardViewModel.goToMove(index)
                    onReviewClick()
                }
            )
        }

        // Ключевые моменты с фильтрами, полным раскрытием и переходом в тренажёр по любому ходу
        item(key = "key-moments") {
            KeyMomentsCard(
                keyMoments = keyMoments,
                onMomentClick = { moment ->
                    boardViewModel.goToMove(moment.moveIndex)
                    onReviewClick()
                },
                onPracticeMoment = if (game.id != null && onPracticeGameMistakes != null) { moment ->
                    onPracticeGameMistakes(game.id, moment.moveNumber)
                } else {
                    null
                }
            )
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

@Composable
private fun SummaryActionRow(
    canPracticeMistakes: Boolean,
    trainableCount: Int,
    onReviewClick: () -> Unit,
    onPracticeClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onReviewClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Интерактивный разбор на доске",
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Интерактивный разбор на доске", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        if (canPracticeMistakes) {
            FilledTonalButton(
                onClick = onPracticeClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = "Отработать ошибки партии",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Отработать ошибки партии ($trainableCount)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CoachSummaryCard(
    report: GameReviewHelper.CoachReport,
    onJumpToTurningPoint: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = "Тренерский вывод",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Тренерский вывод",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = report.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            report.bullets.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            report.turningPoint?.let { tp ->
                OutlinedButton(
                    onClick = { onJumpToTurningPoint(tp.moveIndex) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Разобрать переломный ход ${tp.formattedMove}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Разобрать переломный ход",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Карточка интерактивного графика оценки (клик, скраббинг и переход на доску).
 */
@Composable
private fun EvaluationGraphCard(
    moveLabels: List<String>,
    evaluations: List<Int>,
    keyMoments: List<KeyMoment>,
    currentMoveIndex: Int,
    currentMoveLabel: String,
    currentMoveEval: Int?,
    playerColor: ChessColor,
    hasRealData: Boolean,
    startMoveNumber: Int,
    firstMoveIsWhite: Boolean,
    onMoveClick: (Int) -> Unit,
    onOpenReviewAtMove: (Int) -> Unit
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
                    text = "График оценки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (currentMoveIndex >= 0 && currentMoveEval != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onOpenReviewAtMove(currentMoveIndex) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "$currentMoveLabel (${ChessEngine.formatEvaluation(currentMoveEval)})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Открыть ход на доске",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else if (hasRealData) {
                    Surface(
                        color = LocalChessPalette.current.positive.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Stockfish",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChessPalette.current.positive,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (hasRealData) {
                EvaluationGraph(
                    evaluations = evaluations,
                    keyMoments = keyMoments,
                    currentMoveIndex = currentMoveIndex,
                    playerColor = playerColor,
                    onMoveClick = onMoveClick,
                    onOpenReview = onOpenReviewAtMove,
                    modifier = Modifier.fillMaxWidth(),
                    chartHeight = 154.dp,
                    showHeader = false,
                    showMoveNumbers = true,
                    startMoveNumber = startMoveNumber,
                    firstMoveIsWhite = firstMoveIsWhite
                )

                Spacer(modifier = Modifier.height(8.dp))
                EvaluationGraphLegend()
                GraphMovePicker(moveLabels, evaluations, currentMoveIndex, onOpenReviewAtMove)
            } else {
                NoEvaluationsPlaceholder()
            }
        }
    }
}

/** Text alternative to aiming at the graph. Index 0 in evaluations is the starting position. */
@Composable
internal fun GraphMovePicker(
    moveLabels: List<String>,
    evaluations: List<Int>,
    currentMoveIndex: Int,
    onOpenReviewAtMove: (Int) -> Unit
) {
    val count = minOf(moveLabels.size, evaluations.size - 1)
    if (count <= 0) return
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Выбрать ход из списка")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Открыть ход на доске") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = currentMoveIndex.coerceIn(0, count - 1))
                ) {
                    items(count) { index ->
                        TextButton(
                            onClick = { open = false; onOpenReviewAtMove(index) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(moveLabels[index], modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            Text(ChessEngine.formatEvaluation(evaluations[index + 1]))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } }
        )
    }
}

/**
 * Легенда графика оценки.
 */
@Composable
private fun EvaluationGraphLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = Color(0xFFF3F5F8), label = "Белые")
        Spacer(modifier = Modifier.width(14.dp))
        LegendItem(color = Color(0xFF232A34), label = "Чёрные")
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = "• Нажмите или проведите по графику",
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
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
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
                contentDescription = "График недоступен",
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
                contentDescription = "Информация о графике",
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
private fun SummaryHeader(
    game: Game,
    cardInfo: PgnParser.GameCardInfo,
    openingLabel: String? = null
) {
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
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = cardInfo.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Результат, контроль времени, число ходов, дата, платформа
                val metaLine = buildList {
                    if (cardInfo.outcome != PgnParser.PlayerOutcome.UNKNOWN) {
                        val term = cardInfo.terminationRu?.let { " ($it)" }.orEmpty()
                        add("${cardInfo.outcome.displayName} ${cardInfo.result.displaySymbol()}$term")
                    }
                    add(cardInfo.timeControlLabel)
                    if (cardInfo.fullMoveCount > 0) add("${cardInfo.fullMoveCount} х.")
                    if (cardInfo.dateLabel.isNotBlank()) add(cardInfo.dateLabel)
                    if (cardInfo.platform != PgnParser.GamePlatform.OTHER) {
                        add(cardInfo.platform.displayName)
                    }
                }.joinToString(" · ")

                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )

                if (!openingLabel.isNullOrBlank()) {
                    Text(
                        text = "Дебют: $openingLabel",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (cardInfo.playerTimeTroubleMoves > 0) {
                    Text(
                        text = "Ходов в цейтноте (<20 с): ${cardInfo.playerTimeTroubleMoves}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }
    }
}

@Composable
internal fun GameMetricsCard(
    game: Game,
    opponentSummary: GameMoveClassifier.SideAnalysisSummary? = null,
    phaseAccuracy: GameMoveClassifier.PhaseAccuracyBreakdown? = null
) {
    val accuracy = game.accuracy

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Статистика партии",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Точность игрока — главный круговой индикатор
            if (accuracy != null) {
                AccuracyIndicator(accuracy = accuracy)
            } else {
                Text(MISSING_ACCURACY, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Сравнение точности и средней потери: Вы vs Соперник
            if (opponentSummary != null) {
                Spacer(modifier = Modifier.height(14.dp))
                SideComparisonStrip(
                    playerAccuracy = accuracy,
                    playerAcpl = game.averageEvaluationLoss,
                    opponentAccuracy = opponentSummary.accuracy,
                    opponentAcpl = opponentSummary.averageCpLoss
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Средняя потеря (ACPL)
                MetricItem(
                    label = "Ср. потеря (сП)",
                    value = game.averageEvaluationLoss?.toString() ?: "—",
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Всего ошибок игрока
                MetricItem(
                    label = "Ваших ошибок",
                    value = "${game.totalMistakes}",
                    color = when {
                        game.totalMistakes > 5 -> LocalChessPalette.current.negative
                        game.totalMistakes > 2 -> LocalChessPalette.current.caution
                        else -> LocalChessPalette.current.positive
                    }
                )
            }

            // Точность по фазам партии: Дебют / Миттельшпиль / Эндшпиль
            if (phaseAccuracy != null && phaseAccuracy.hasAnyPhase) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Точность по фазам",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PhaseAccuracyItem(
                        label = "Дебют",
                        accuracy = phaseAccuracy.opening,
                        movesCount = phaseAccuracy.openingMoves
                    )
                    PhaseAccuracyItem(
                        label = "Миттельшпиль",
                        accuracy = phaseAccuracy.middlegame,
                        movesCount = phaseAccuracy.middlegameMoves
                    )
                    PhaseAccuracyItem(
                        label = "Эндшпиль",
                        accuracy = phaseAccuracy.endgame,
                        movesCount = phaseAccuracy.endgameMoves
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            // Детализация ошибок игрока
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ErrorTypeItem(
                    emoji = "??",
                    label = "Зевки",
                    count = game.blundersCount,
                    color = LocalChessPalette.current.negative
                )
                ErrorTypeItem(
                    emoji = "?",
                    label = "Ошибки",
                    count = game.mistakesCount,
                    color = LocalChessPalette.current.caution
                )
                ErrorTypeItem(
                    emoji = "?!",
                    label = "Неточности",
                    count = game.inaccuraciesCount,
                    color = LocalChessPalette.current.caution
                )
            }
        }
    }
}

@Composable
private fun SideComparisonStrip(
    playerAccuracy: Double?,
    playerAcpl: Int?,
    opponentAccuracy: Double,
    opponentAcpl: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = "Вы",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = playerAccuracy?.let(::formatGameAccuracy) ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = playerAcpl?.let { "$it сП" } ?: "— сП",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "VS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Соперник",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatGameAccuracy(opponentAccuracy),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$opponentAcpl сП",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PhaseAccuracyItem(
    label: String,
    accuracy: Double?,
    movesCount: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (accuracy != null) {
                String.format(java.util.Locale.US, "%.0f%%", accuracy)
            } else {
                "—"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (accuracy != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (movesCount >= 2) {
            Text(
                text = "$movesCount х.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
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
                    text = formatGameAccuracy(accuracy),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
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
                color = MaterialTheme.colorScheme.onSurface
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
            color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun getAccuracyColor(accuracy: Double): Color = with(LocalChessPalette.current) {
    when {
        accuracy >= 80 -> positive
        accuracy >= 60 -> caution
        else -> negative
    }
}

@Composable
fun KeyMomentsCard(
    keyMoments: List<KeyMoment>,
    onMomentClick: (KeyMoment) -> Unit,
    onPracticeMoment: ((KeyMoment) -> Unit)? = null
) {
    var selectedFilter by rememberSaveable { mutableStateOf(GameReviewHelper.MomentFilter.ALL) }
    var showAllMoments by rememberSaveable { mutableStateOf(false) }

    // Статистика качества считается строго по ходам игрока
    val playerMoments = keyMoments.filter { it.isPlayerMove }
    val opponentMoments = keyMoments.filter { !it.isPlayerMove }

    val brilliantCount = playerMoments.count { it.quality == MoveQuality.BRILLIANT }
    val greatCount = playerMoments.count { it.quality == MoveQuality.GREAT_MOVE }
    val bestCount = playerMoments.count { it.quality == MoveQuality.BEST_MOVE }
    val bookCount = playerMoments.count { it.quality == MoveQuality.BOOK }
    val solidCount = playerMoments.count {
        it.quality == MoveQuality.GOOD || it.quality == MoveQuality.EXCELLENT
    }
    val inaccuracyCount = playerMoments.count { it.quality == MoveQuality.INACCURACY }
    val mistakeCount = playerMoments.count { it.quality == MoveQuality.MISTAKE }
    val missedWinCount = playerMoments.count { it.quality == MoveQuality.MISSED_WIN }
    val blunderCount = playerMoments.count { it.quality == MoveQuality.BLUNDER }

    val oppBlunders = opponentMoments.count { it.quality == MoveQuality.BLUNDER }
    val oppMistakes = opponentMoments.count {
        it.quality == MoveQuality.MISTAKE || it.quality == MoveQuality.MISSED_WIN
    }
    val oppInaccuracies = opponentMoments.count { it.quality == MoveQuality.INACCURACY }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Качество ваших ходов",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            // Сильные и теоретические ходы
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
                if (bookCount > 0) {
                    KeyMomentItemCompact(
                        quality = MoveQuality.BOOK,
                        count = bookCount
                    )
                } else {
                    KeyMomentItemCompact(
                        quality = MoveQuality.GOOD,
                        count = solidCount
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            // Ошибки и упущенные возможности
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
                if (missedWinCount > 0) {
                    KeyMomentItemCompact(
                        quality = MoveQuality.MISSED_WIN,
                        count = missedWinCount
                    )
                }
                KeyMomentItemCompact(
                    quality = MoveQuality.BLUNDER,
                    count = blunderCount
                )
            }

            // Краткая строка ошибок соперника (если выполнен двусторонний анализ)
            if (opponentMoments.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ошибки соперника: ?? $oppBlunders · ? $oppMistakes · ?! $oppInaccuracies",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Фильтруемый и раскрываемый список ключевых моментов (ваши ошибки, сильные ходы и зевки соперника)
            val allSignificant = remember(keyMoments) {
                GameReviewHelper.filterKeyMoments(keyMoments, GameReviewHelper.MomentFilter.ALL)
            }
            val playerMistakeList = remember(keyMoments) {
                GameReviewHelper.filterKeyMoments(keyMoments, GameReviewHelper.MomentFilter.PLAYER_MISTAKES)
            }
            val highlightList = remember(keyMoments) {
                GameReviewHelper.filterKeyMoments(keyMoments, GameReviewHelper.MomentFilter.HIGHLIGHTS)
            }
            val oppMistakeList = remember(keyMoments) {
                GameReviewHelper.filterKeyMoments(keyMoments, GameReviewHelper.MomentFilter.OPPONENT_MISTAKES)
            }

            if (allSignificant.isNotEmpty() || highlightList.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Ключевые моменты партии:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filterEntries = buildList {
                        add(GameReviewHelper.MomentFilter.ALL to allSignificant.size)
                        if (playerMistakeList.isNotEmpty()) {
                            add(GameReviewHelper.MomentFilter.PLAYER_MISTAKES to playerMistakeList.size)
                        }
                        if (highlightList.isNotEmpty()) {
                            add(GameReviewHelper.MomentFilter.HIGHLIGHTS to highlightList.size)
                        }
                        if (oppMistakeList.isNotEmpty()) {
                            add(GameReviewHelper.MomentFilter.OPPONENT_MISTAKES to oppMistakeList.size)
                        }
                    }
                    filterEntries.forEach { (filter, count) ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = {
                                selectedFilter = filter
                                showAllMoments = false
                            },
                            label = { Text("${filter.label} ($count)", fontSize = 12.sp) }
                        )
                    }
                }

                val filteredMoments = when (selectedFilter) {
                    GameReviewHelper.MomentFilter.ALL -> allSignificant
                    GameReviewHelper.MomentFilter.PLAYER_MISTAKES -> playerMistakeList
                    GameReviewHelper.MomentFilter.HIGHLIGHTS -> highlightList
                    GameReviewHelper.MomentFilter.OPPONENT_MISTAKES -> oppMistakeList
                }
                val visibleMoments = if (showAllMoments) {
                    filteredMoments
                } else {
                    filteredMoments.take(6)
                }

                Spacer(Modifier.height(8.dp))

                visibleMoments.forEach { moment ->
                    val canTrain = onPracticeMoment != null &&
                        moment.isPlayerMove &&
                        moment.quality.isBad() &&
                        !moment.bestMove.isNullOrBlank() &&
                        moment.bestMove != moment.san
                    KeyMomentRow(
                        moment = moment,
                        onClick = onMomentClick,
                        onPracticeClick = if (canTrain) {
                            { onPracticeMoment?.invoke(moment) }
                        } else {
                            null
                        }
                    )
                }

                if (filteredMoments.size > 6) {
                    TextButton(
                        onClick = { showAllMoments = !showAllMoments },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (showAllMoments) {
                                "Свернуть список"
                            } else {
                                "Показать все моменты (${filteredMoments.size})"
                            }
                        )
                    }
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
        Icon(quality.getIcon(), contentDescription = quality.getDisplayName(), modifier = Modifier.size(24.dp))

        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) LocalChessPalette.current.accent(quality) else MaterialTheme.colorScheme.onSurfaceVariant
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
    onClick: (KeyMoment) -> Unit,
    onPracticeClick: (() -> Unit)? = null
) {
    val accent = LocalChessPalette.current.accent(moment.quality)
    val userDelta = moment.userPerspectiveDelta()
    val roleLabel = if (moment.isPlayerMove) {
        moment.quality.getDisplayName()
    } else {
        "${moment.quality.getDisplayName()} соперника"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick(moment) },
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(moment.quality.getIcon(), contentDescription = moment.quality.getDisplayName(), modifier = Modifier.size(22.dp))

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = moment.formattedMove(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!moment.bestMove.isNullOrBlank() && moment.quality.isBad() && moment.bestMove != moment.san) {
                        Text(
                            text = "→ ${moment.bestMove}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalChessPalette.current.positive
                        )
                    }
                }
                moment.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (userDelta != 0 && moment.quality != MoveQuality.BOOK) {
                Text(
                    text = formatEvaluationChange(userDelta),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (userDelta > 0) LocalChessPalette.current.positive else LocalChessPalette.current.negative
                )
            }

            if (onPracticeClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onPracticeClick,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = "Отработать ход в тренажёре",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Перейти к ходу на доске",
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