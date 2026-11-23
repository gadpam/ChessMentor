package com.example.chessmentor.presentation.ui.components.screen

import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.example.chessmentor.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.presentation.ui.components.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.ListSkeleton
import com.example.chessmentor.presentation.ui.components.SkeletonBox
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.AccuracyTrendPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.ActionableProblemTheme
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.CoachingAction
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.ColorPerformance
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.DailyActivityPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.EcoFamilyStat
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.MoveQualityDistribution
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.OpeningLineStat
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.OpeningRepertoireSummary
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.PhaseOverview
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.PhasePerformance
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.RatingTrendPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.StatsPeriod
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.TimeControlPerformance
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.TimeTroubleReport
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.TrendDirection
import com.example.chessmentor.domain.training.PatternMastery
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.StatisticsSection
import com.example.chessmentor.presentation.viewmodel.StatisticsViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatisticsScreen(
    paddingValues: PaddingValues,
    user: User,
    container: AppContainer,
    onPracticeWeakPatterns: (() -> Unit)? = null,
    onStartTraining: ((TrainingMode) -> Unit)? = null,
    onOpenGamesLibrary: ((query: String, color: ChessColor?) -> Unit)? = null,
    onOpenGameById: ((Long) -> Unit)? = null,
    onNavigateToUpload: (() -> Unit)? = null
) {
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val statisticsViewModel: StatisticsViewModel = viewModel(factory = factory)
    val statistics by statisticsViewModel.statistics
    val selectedSection by statisticsViewModel.selectedSection
    val selectedPeriod by statisticsViewModel.selectedPeriod
    val repertoireColor by statisticsViewModel.selectedRepertoireColor
    val isLoading by statisticsViewModel.isLoading
    val error by statisticsViewModel.errorMessage

    val launchTraining: ((TrainingMode) -> Unit)? = when {
        onStartTraining != null -> onStartTraining
        onPracticeWeakPatterns != null -> { _ -> onPracticeWeakPatterns() }
        else -> null
    }

    LaunchedEffect(user.id, user.rating) {
        statisticsViewModel.loadStatistics(user, statisticsViewModel.selectedPeriod.value)
    }

    StatisticsPeriodLayout(
        paddingValues = paddingValues,
        selected = selectedPeriod,
        cached = statistics?.takeIf { it.userId == user.id },
        refreshing = isLoading,
        error = error,
        select = statisticsViewModel::selectPeriod,
        retry = statisticsViewModel::refresh
    ) { stats ->
        val report = stats.gameAnalytics
        val emptyPeriod = stats.totalGames == 0 && stats.totalGamesAllTime > 0 && stats.period != StatsPeriod.ALL
        // ==================== ВКЛАДКИ РАЗДЕЛОВ ====================
        item(key = "section_tabs") {
            SectionSegmentedBar(
                selected = selectedSection,
                openingsCount = report.repertoire.uniqueOpeningsCount,
                dueCount = stats.training.dueForReviewCount,
                onSelect = { statisticsViewModel.selectedSection.value = it }
            )
        }

        // Индикатор пустого временного окна, если в библиотеке есть более старые партии
        if (emptyPeriod && selectedSection != StatisticsSection.TACTICS) {
            item(key = "empty_period_banner") {
                EmptyPeriodNoticeCard(
                    period = stats.period,
                    totalAllTime = stats.totalGamesAllTime,
                    onShowAllTime = { statisticsViewModel.selectPeriod(StatsPeriod.ALL) }
                )
            }
            return@StatisticsPeriodLayout
        }

        when (selectedSection) {
            StatisticsSection.OVERVIEW -> {
                item(key = "hero_card") {
                    PlayerHeroSummaryCard(stats = stats)
                }

                if (stats.coachingActions.isNotEmpty()) {
                    item(key = "coaching_plan") {
                        CoachingPlanCard(
                            actions = stats.coachingActions,
                            onStartTraining = launchTraining,
                            onOpenGamesLibrary = onOpenGamesLibrary,
                            onOpenGameById = onOpenGameById
                        )
                    }
                }

                if (report.accuracySeries.isNotEmpty()) {
                    item(key = "accuracy_chart") {
                        AccuracyTrendCard(
                            points = report.accuracySeries,
                            direction = report.accuracyTrendDirection,
                            delta = report.accuracyTrendDelta,
                            onOpenGameById = onOpenGameById
                        )
                    }
                }

                if (report.overall.gamesCount > 0) {
                    item(key = "color_split") {
                        ColorSplitComparisonCard(
                            white = report.asWhite,
                            black = report.asBlack,
                            onOpenColorRepertoire = { color ->
                                statisticsViewModel.selectedRepertoireColor.value = color
                                statisticsViewModel.selectedSection.value = StatisticsSection.OPENINGS
                            }
                        )
                    }
                } else {
                    item(key = "no_games_cta") {
                        BrandEmptyState(
                            painter = painterResource(R.drawable.ic_empty_stats),
                            title = "Добавьте партии для полной аналитики",
                            subtitle = "Импортируйте PGN с Lichess или Chess.com — приложение построит график точности, сравнит игру белыми и чёрными, разберёт фазы партии и цейтнот.",
                            onAction = onNavigateToUpload,
                            actionLabel = "Загрузить PGN",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                if (report.phases.hasData) {
                    item(key = "phase_overview") {
                        GamePhaseCard(phases = report.phases)
                    }
                }

                if (report.moveQuality.totalPlayerMoves > 0 || stats.totalMistakes > 0) {
                    item(key = "move_quality") {
                        MoveQualityBreakdownCard(
                            quality = report.moveQuality,
                            fallbackBlunders = stats.blunders,
                            fallbackMistakes = stats.mistakes,
                            fallbackInaccuracies = stats.inaccuracies,
                            averageAcpl = stats.averageEvaluationLoss
                        )
                    }
                }

                if (report.timeControls.isNotEmpty() || report.timeTrouble.gamesWithClocks > 0) {
                    item(key = "time_controls") {
                        TimeControlsAndClockCard(
                            timeControls = report.timeControls,
                            timeTrouble = report.timeTrouble,
                            onTrainVision = launchTraining?.let { fn -> { fn(TrainingMode.PatternVision) } }
                        )
                    }
                }

                if (stats.dailyActivity.isNotEmpty()) {
                    item(key = "daily_activity") {
                        DailyActivityCard(
                            points = stats.dailyActivity,
                            activeDaysStreak = stats.activeDaysStreak
                        )
                    }
                }
            }

            StatisticsSection.OPENINGS -> {
                item(key = "repertoire_header") {
                    RepertoireOverviewBanner(
                        repertoire = report.repertoire,
                        selectedColor = repertoireColor,
                        onSelectColor = { statisticsViewModel.selectedRepertoireColor.value = it }
                    )
                }

                if (!report.repertoire.hasAnyOpenings) {
                    item(key = "empty_repertoire") {
                        BrandEmptyState(
                            painter = painterResource(R.drawable.ic_empty_repertoire),
                            title = "Дебютный репертуар пока пуст",
                            subtitle = "Загрузите партии с тегами [ECO] / [Opening] или проанализируйте их движком — здесь появится статистика побед, точности и оценки выхода из каждого дебюта за белых и чёрных.",
                            onAction = onNavigateToUpload,
                            actionLabel = "Импортировать партии",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    val bestForColor = if (repertoireColor == ChessColor.WHITE) {
                        report.repertoire.bestWhite
                    } else {
                        report.repertoire.bestBlack
                    }
                    val weakestForColor = if (repertoireColor == ChessColor.WHITE) {
                        report.repertoire.weakestWhite
                    } else {
                        report.repertoire.weakestBlack
                    }

                    if (bestForColor != null || weakestForColor != null) {
                        item(key = "repertoire_spotlights_${repertoireColor.name}") {
                            OpeningSpotlightsCard(
                                color = repertoireColor,
                                best = bestForColor,
                                weakest = weakestForColor,
                                onOpenLibrary = onOpenGamesLibrary
                            )
                        }
                    }

                    val activeLines = if (repertoireColor == ChessColor.WHITE) {
                        report.repertoire.whiteOpenings
                    } else {
                        report.repertoire.blackOpenings
                    }

                    item(key = "opening_lines_${repertoireColor.name}") {
                        OpeningLinesListCard(
                            color = repertoireColor,
                            lines = activeLines,
                            onOpenLibrary = onOpenGamesLibrary,
                            onOpenGameById = onOpenGameById
                        )
                    }

                    if (report.repertoire.ecoFamilies.isNotEmpty()) {
                        item(key = "eco_families") {
                            EcoVolumesCard(
                                families = report.repertoire.ecoFamilies,
                                onSearchEco = onOpenGamesLibrary?.let { fn -> { code -> fn(code, null) } }
                            )
                        }
                    }
                }
            }

            StatisticsSection.TACTICS -> {
                item(key = "tactics_summary") {
                    TacticalOverviewCard(
                        currentRating = stats.currentRating,
                        training = stats.training,
                        onStartTraining = launchTraining
                    )
                }

                if (stats.training.ratingHistory.size >= 2) {
                    item(key = "rating_trend") {
                        RatingTrendCard(
                            points = stats.training.ratingHistory,
                            isProvisional = stats.training.isRatingProvisional
                        )
                    }
                }

                if (stats.training.allPatternMastery.isNotEmpty()) {
                    item(key = "pattern_mastery") {
                        PatternMasteryCard(
                            weakPatterns = stats.training.weakPatterns,
                            strongPatterns = stats.training.strongPatterns,
                            allPatterns = stats.training.allPatternMastery,
                            onStartTraining = launchTraining
                        )
                    }
                }

                if (stats.actionableProblemThemes.isNotEmpty()) {
                    item(key = "game_problem_themes") {
                        ProblemThemesCard(
                            themes = stats.actionableProblemThemes,
                            onStartTraining = launchTraining
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ==================== ВЕРХНЯЯ ПАНЕЛЬ ПЕРИОДА И РАЗДЕЛОВ ====================

/** Never render a cached report under a different period's controls. */
@Composable
internal fun StatisticsPeriodLayout(
    paddingValues: PaddingValues,
    selected: StatsPeriod,
    cached: GetUserStatisticsUseCase.UserStatistics?,
    refreshing: Boolean,
    error: String?,
    select: (StatsPeriod) -> Unit,
    retry: () -> Unit,
    content: LazyListScope.(GetUserStatisticsUseCase.UserStatistics) -> Unit
) {
    val report = cached?.takeIf { it.period == selected }
    LazyColumn(Modifier.fillMaxSize().padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "period_bar") { PeriodSelectorRow(selected, refreshing, select, retry) }
        if (error != null) item(key = "refresh_error") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (report == null) "Не удалось загрузить отчёт: ${selected.displayName}"
                         else "Отчёт не обновлён: ${selected.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = retry) { Text("Повторить") }
                }
            }
        }
        if (report != null) {
            content(report)
        } else if (refreshing || cached == null) {
            item(key = "stats_skeleton") {
                ListSkeleton(itemCount = 4)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelectorRow(
    selectedPeriod: StatsPeriod,
    isRefreshing: Boolean,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FlowRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { onSelectPeriod(period) },
                    label = {
                        Text(
                            text = period.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedPeriod == period) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        IconButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            modifier = Modifier.size(48.dp)
        ) {
            if (isRefreshing) {
                SkeletonBox(modifier = Modifier.size(18.dp).semantics { contentDescription = "Загружаем статистику" })
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Обновить статистику",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionSegmentedBar(
    selected: StatisticsSection,
    openingsCount: Int,
    dueCount: Int,
    onSelect: (StatisticsSection) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StatisticsSection.entries.forEach { section ->
                val isSelected = section == selected
                val badge = when (section) {
                    StatisticsSection.OVERVIEW -> null
                    StatisticsSection.OPENINGS -> openingsCount.takeIf { it > 0 }?.toString()
                    StatisticsSection.TACTICS -> dueCount.takeIf { it > 0 }?.toString()
                }
                Surface(
                    onClick = { onSelect(section) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    shadowElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = section.title,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (badge != null) {
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                }
                            ) {
                                Text(
                                    text = badge,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPeriodNoticeCard(
    period: StatsPeriod,
    totalAllTime: Int,
    onShowAllTime: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "За «${period.displayName}» партий нет (в архиве: $totalAllTime)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onShowAllTime) {
                Text("Всё время")
            }
        }
    }
}

// ==================== ОБЗОР: ГЛАВНАЯ КАРТОЧКА ПРОФИЛЯ ====================

@Composable
private fun PlayerHeroSummaryCard(stats: GetUserStatisticsUseCase.UserStatistics) {
    val overall = stats.gameAnalytics.overall
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stats.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (stats.activeDaysStreak > 0) {
                        Text(
                            text = "Серия дней: ${stats.activeDaysStreak}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                overall.bestWinOpponentElo?.let { bestElo ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = "Рекорд победы",
                                tint = LocalChessPalette.current.caution,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Победа $bestElo",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            OverviewMetrics(stats)

            // Полоса W/D/L
            if (overall.decidedGames > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    WdlStackedBar(
                        wins = overall.wins,
                        draws = overall.draws,
                        losses = overall.losses,
                        height = 10.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Победы ${overall.wins}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChessPalette.current.positive,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Ничьи ${overall.draws}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        )
                        Text(
                            text = "Поражения ${overall.losses}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChessPalette.current.negative,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun OverviewMetrics(stats: GetUserStatisticsUseCase.UserStatistics) {
    val overall = stats.gameAnalytics.overall
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SummaryMetric("Партий: ${stats.totalGames}", "Разобрано: ${stats.analyzedGames}")
        val accuracy = overall.averageAccuracy
        if (accuracy != null) {
            val n = overall.accuracySampleSize
            val noun = if (n % 10 == 1 && n % 100 != 11) "партии" else "партиям"
            SummaryMetric("Точность: ${formatPct(accuracy)}%", "По $n $noun")
        } else {
            SummaryMetric("Точность не рассчитана")
        }
        SummaryMetric(overall.scorePercent?.let { "Набрано очков: ${it.roundToInt()}%" } ?: "Нет известных исходов")
    }
}

@Composable
internal fun TrainingRatingMetric(current: Int, training: GetUserStatisticsUseCase.TrainingStatistics) {
    if (training.ratingHistory.isNotEmpty()) {
        SummaryMetric("Рейтинг в задачах ChessMentor: $current",
            if (training.isRatingProvisional) "Предварительная оценка" else null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryMetric(label: String, detail: String? = null) {
    FlowRow(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

// ==================== ПЛАН ТРЕНЕРА (С ДЕЙСТВИЯМИ В 1 НАЖАТИЕ) ====================

@Composable
private fun CoachingPlanCard(
    actions: List<CoachingAction>,
    onStartTraining: ((TrainingMode) -> Unit)?,
    onOpenGamesLibrary: ((query: String, color: ChessColor?) -> Unit)?,
    onOpenGameById: ((Long) -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = "План тренера",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "План тренера",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            actions.forEachIndexed { idx, action ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = action.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val clickHandler: (() -> Unit)? = when {
                        action.trainingMode != null && onStartTraining != null -> {
                            { onStartTraining(action.trainingMode) }
                        }
                        action.openingSearchQuery != null && onOpenGamesLibrary != null -> {
                            { onOpenGamesLibrary(action.openingSearchQuery, null) }
                        }
                        action.gameIdToOpen != null && onOpenGameById != null -> {
                            { onOpenGameById(action.gameIdToOpen) }
                        }
                        else -> null
                    }
                    if (clickHandler != null && !action.actionLabel.isNullOrBlank()) {
                        FilledTonalButton(
                            onClick = clickHandler,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Text(action.actionLabel, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ==================== ИНТЕРАКТИВНЫЙ ГРАФИК ДИНАМИКИ ТОЧНОСТИ ====================

@Composable
private fun AccuracyTrendCard(
    points: List<AccuracyTrendPoint>,
    direction: TrendDirection,
    delta: Double?,
    onOpenGameById: ((Long) -> Unit)?
) {
    var selectedIdx by remember(points) { mutableIntStateOf(points.lastIndex) }
    val safeIndex = selectedIdx.coerceIn(0, points.lastIndex)
    val selected = points[safeIndex]

    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val chartPalette = LocalChessPalette.current
    val highlightRing = MaterialTheme.colorScheme.onSurface

    val badgeColor = when (direction) {
        TrendDirection.IMPROVING -> LocalChessPalette.current.positive
        TrendDirection.DECLINING -> LocalChessPalette.current.negative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = "Динамика точности",
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Динамика точности",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Последние ${points.size} разобранных · скользящая средняя (5)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (delta != null) {
                    val sign = if (delta >= 0) "+" else ""
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "$sign${formatPct(delta)}%",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                }
            }

            // Шкала 0..100% с адаптивным нижним порогом для наглядности
            val minAcc = ((points.minOf { it.accuracy } - 10.0) / 10.0).toInt() * 10
            val yMin = minAcc.coerceIn(0, 60).toFloat()
            val yMax = 100f
            val ySpan = (yMax - yMin).coerceAtLeast(20f)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .height(145.dp)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text("100%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${((yMin + yMax) / 2f).roundToInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${yMin.roundToInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(145.dp)
                        .pointerInput(points) {
                            detectTapGestures { tap ->
                                if (points.size == 1) {
                                    selectedIdx = 0
                                } else {
                                    val padX = 10.dp.toPx()
                                    val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                                    val ratio = ((tap.x - padX) / usableW).coerceIn(0f, 1f)
                                    selectedIdx = (ratio * (points.size - 1)).roundToInt()
                                        .coerceIn(0, points.lastIndex)
                                }
                            }
                        }
                ) {
                    val padX = 10.dp.toPx()
                    val padY = 8.dp.toPx()
                    val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                    val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

                    fun xOf(index: Int): Float = if (points.size == 1) {
                        size.width / 2f
                    } else {
                        padX + (index.toFloat() / (points.size - 1)) * usableW
                    }

                    fun yOf(value: Double): Float {
                        val norm = ((value.toFloat() - yMin) / ySpan).coerceIn(0f, 1f)
                        return padY + (1f - norm) * usableH
                    }

                    // Сетка
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    listOf(0f, 0.5f, 1f).forEach { frac ->
                        val y = padY + frac * usableH
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash
                        )
                    }

                    // Заливка и линия скользящей средней
                    if (points.size >= 2) {
                        val linePath = Path()
                        val fillPath = Path()
                        points.forEachIndexed { i, pt ->
                            val x = xOf(i)
                            val y = yOf(pt.rollingAccuracy)
                            if (i == 0) {
                                linePath.moveTo(x, y)
                                fillPath.moveTo(x, padY + usableH)
                                fillPath.lineTo(x, y)
                            } else {
                                linePath.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }
                        }
                        fillPath.lineTo(xOf(points.lastIndex), padY + usableH)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.24f),
                                    primaryColor.copy(alpha = 0.02f)
                                ),
                                startY = padY,
                                endY = padY + usableH
                            )
                        )
                        drawPath(
                            path = linePath,
                            color = primaryColor,
                            style = Stroke(
                                width = 2.6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // Вертикальный маркер выбранной партии
                    val selX = xOf(safeIndex)
                    drawLine(
                        color = primaryColor.copy(alpha = 0.35f),
                        start = Offset(selX, padY),
                        end = Offset(selX, padY + usableH),
                        strokeWidth = 1.2.dp.toPx()
                    )

                    // Точки отдельных партий, окрашенные по исходу
                    points.forEachIndexed { i, pt ->
                        val cx = xOf(i)
                        val cy = yOf(pt.accuracy)
                        val dotColor = when (pt.outcome) {
                            PgnParser.PlayerOutcome.WIN -> chartPalette.positive
                            PgnParser.PlayerOutcome.LOSS -> chartPalette.negative
                            PgnParser.PlayerOutcome.DRAW -> chartPalette.neutral
                            PgnParser.PlayerOutcome.UNKNOWN -> primaryColor
                        }
                        val isSel = i == safeIndex
                        drawCircle(
                            color = dotColor,
                            radius = if (isSel) 5.5.dp.toPx() else 3.8.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                        if (isSel) {
                            drawCircle(
                                color = highlightRing,
                                radius = 7.5.dp.toPx(),
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.8.dp.toPx())
                            )
                        }
                    }
                }
            }

            // Карточка деталей выбранной на графике партии
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val playerColor = selected.playerColor.getDisplayName()
                        Text(
                            text = "$playerColor против ${selected.opponentLabel} · ${selected.outcome.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append("Точность ${formatPct(selected.accuracy)}%")
                                append(" (ср. ${formatPct(selected.rollingAccuracy)}%)")
                                selected.openingShort?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val targetId = selected.gameId
                    if (targetId != null && onOpenGameById != null) {
                        TextButton(
                            onClick = { onOpenGameById(targetId) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Insights, contentDescription = "Разбор партии", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Разбор", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ==================== СРАВНЕНИЕ ИГРЫ БЕЛЫМИ И ЧЁРНЫМИ ====================

@Composable
private fun ColorSplitComparisonCard(
    white: ColorPerformance,
    black: ColorPerformance,
    onOpenColorRepertoire: (ChessColor) -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Игра по цвету фигур",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ColorSideTile(
                    modifier = Modifier.weight(1f),
                    icon = "♔",
                    perf = white,
                    onClick = { onOpenColorRepertoire(ChessColor.WHITE) }
                )
                ColorSideTile(
                    modifier = Modifier.weight(1f),
                    icon = "♚",
                    perf = black,
                    onClick = { onOpenColorRepertoire(ChessColor.BLACK) }
                )
            }
        }
    }
}

@Composable
private fun ColorSideTile(
    modifier: Modifier = Modifier,
    icon: String,
    perf: ColorPerformance,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$icon ${perf.label}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${perf.gamesCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (perf.gamesCount == 0) {
                Text(
                    text = "Пока нет партий",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = perf.scorePercent?.let { "${it.roundToInt()}% очков" } ?: "${perf.gamesCount} парт.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = scoreColor(perf.scorePercent)
                )

                if (perf.decidedGames > 0) {
                    WdlStackedBar(
                        wins = perf.wins,
                        draws = perf.draws,
                        losses = perf.losses,
                        height = 7.dp
                    )
                    Text(
                        text = perf.wdlSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                MetricInlineRow(
                    label = "Точность",
                    value = perf.averageAccuracy?.let { "${formatPct(it)}%" } ?: "—"
                )
                MetricInlineRow(
                    label = "Потеря (ACPL)",
                    value = perf.averageAcpl?.let { "$it сП" } ?: "—"
                )
                MetricInlineRow(
                    label = "Зевков / парт.",
                    value = perf.blundersPerGame?.let { formatPct(it) } ?: "—"
                )

                Text(
                    text = "Дебюты →",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ==================== ФАЗЫ ПАРТИИ ====================

@Composable
private fun GamePhaseCard(phases: PhaseOverview) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "Точность по фазам партии",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val weak = phases.weakest
                val strong = phases.strongest
                if (weak?.accuracy != null && strong?.accuracy != null) {
                    Text(
                        text = "Лучшая: ${strong.phase.displayName} (${strong.accuracy.roundToInt()}%) · " +
                            "Зона роста: ${weak.phase.displayName} (${weak.accuracy.roundToInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            phases.all.forEach { phase ->
                PhaseProgressRow(
                    perf = phase,
                    isWeakest = phases.weakest?.phase == phase.phase && (phases.gapPercent ?: 0.0) >= 5.0
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseProgressRow(
    perf: PhasePerformance,
    isWeakest: Boolean
) {
    val acc = perf.accuracy
    val barColor = when {
        acc == null -> MaterialTheme.colorScheme.outline
        isWeakest -> LocalChessPalette.current.caution
        acc >= 82.0 -> LocalChessPalette.current.positive
        acc >= 68.0 -> MaterialTheme.colorScheme.primary
        else -> LocalChessPalette.current.negative
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = perf.phase.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = perf.phase.moveRangeHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = acc?.let { "${formatPct(it)}%" } ?: "нет ходов",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (acc != null) barColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { ((acc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        if (perf.movesCount > 0) {
            val details = buildList {
                add("${perf.movesCount} ход.")
                if (perf.bookMoves > 0) add("теория: ${perf.bookMoves}")
                if (perf.greatAndBrilliant > 0) add("сильных: ${perf.greatAndBrilliant}")
                if (perf.blunders > 0) add("зевков: ${perf.blunders}")
                if (perf.mistakes + perf.missedWins > 0) add("ошибок: ${perf.mistakes + perf.missedWins}")
                if (perf.inaccuracies > 0) add("неточн.: ${perf.inaccuracies}")
            }.joinToString(" · ")
            Text(
                text = details,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== КАЧЕСТВО ХОДОВ И НАКАЗАНИЕ ЗЕВКОВ ====================

@Composable
private fun MoveQualityBreakdownCard(
    quality: MoveQualityDistribution,
    fallbackBlunders: Int,
    fallbackMistakes: Int,
    fallbackInaccuracies: Int,
    averageAcpl: Double
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Профиль качества ходов",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (averageAcpl > 0.0) {
                    Text(
                        text = "ACPL ${averageAcpl.roundToInt()} сП",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (quality.totalPlayerMoves > 0) {
                Text(
                    text = "Точных и хороших ходов: ${formatPct(quality.cleanMovePercent)}% " +
                        "(${quality.cleanMoves} из ${quality.totalPlayerMoves})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QualityBadgeChip(Modifier.weight(1f), "!! / !", quality.brilliant + quality.great, LocalChessPalette.current.brilliant)
                    QualityBadgeChip(Modifier.weight(1f), "Лучшие", quality.best, LocalChessPalette.current.positive)
                    QualityBadgeChip(Modifier.weight(1f), "Хорошие", quality.excellent + quality.good, LocalChessPalette.current.positive)
                    QualityBadgeChip(Modifier.weight(1f), "Теория", quality.book, LocalChessPalette.current.theory)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QualityBadgeChip(Modifier.weight(1f), "Неточн. ?!", quality.inaccuracy, LocalChessPalette.current.caution)
                    QualityBadgeChip(Modifier.weight(1f), "Ошибки ?", quality.mistake, LocalChessPalette.current.caution)
                    QualityBadgeChip(Modifier.weight(1f), "Упущено", quality.missedWin, LocalChessPalette.current.negative)
                    QualityBadgeChip(Modifier.weight(1f), "Зевки ??", quality.blunder, LocalChessPalette.current.negative)
                }

                val punishPct = quality.opponentBlunderPunishPercent
                if (punishPct != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Наказание зевков соперника",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "$punishPct% (${quality.opponentBlundersPunished}/${quality.opponentBlundersTotal})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (punishPct >= 65) LocalChessPalette.current.positive else LocalChessPalette.current.caution
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QualityBadgeChip(Modifier.weight(1f), "Зевки ??", fallbackBlunders, LocalChessPalette.current.negative)
                    QualityBadgeChip(Modifier.weight(1f), "Ошибки ?", fallbackMistakes, LocalChessPalette.current.caution)
                    QualityBadgeChip(Modifier.weight(1f), "Неточн. ?!", fallbackInaccuracies, LocalChessPalette.current.caution)
                }
            }
        }
    }
}

@Composable
private fun QualityBadgeChip(
    modifier: Modifier = Modifier,
    label: String,
    count: Int,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.11f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== КОНТРОЛИ ВРЕМЕНИ И ЦЕЙТНОТ ====================

@Composable
private fun TimeControlsAndClockCard(
    timeControls: List<TimeControlPerformance>,
    timeTrouble: TimeTroubleReport,
    onTrainVision: (() -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "Контроли времени и цейтнот",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Контроли времени и цейтнот",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            timeControls.forEach { tc ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${tc.category.displayName} (${tc.gamesCount})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = buildString {
                                tc.scorePercent?.let { append("${it.roundToInt()}% очков") }
                                tc.averageAccuracy?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("точн. ${it.roundToInt()}%")
                                }
                            },
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (tc.decidedGames > 0) {
                        WdlStackedBar(tc.wins, tc.draws, tc.losses, height = 6.dp)
                    }
                }
            }

            if (timeTrouble.gamesWithClocks > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if ((timeTrouble.accuracyDropPercent ?: 0.0) >= 7.0) {
                        LocalChessPalette.current.caution.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Диагностика часов (${timeTrouble.gamesWithClocks} парт. с [%clk])",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (timeTrouble.hasComparison) {
                            Text(
                                text = "Обычный темп: ${formatPct(timeTrouble.normalAccuracy!!)}% точн. → " +
                                    "Цейтнот (<20с, ${timeTrouble.timeTroubleMovesCount} ход.): " +
                                    "${formatPct(timeTrouble.timeTroubleAccuracy!!)}% точн.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val ttBl = timeTrouble.timeTroubleBlunderRatePercent
                            val nBl = timeTrouble.normalBlunderRatePercent
                            if (ttBl != null && nBl != null && ttBl > nBl) {
                                Text(
                                    text = "Частота зевков в цейтноте: ${formatPct(ttBl)}% против ${formatPct(nBl)}% в норме",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChessPalette.current.negative
                                )
                            }
                            if ((timeTrouble.accuracyDropPercent ?: 0.0) >= 7.0 && onTrainVision != null) {
                                TextButton(
                                    onClick = onTrainVision,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text("Прокачать тактическую зоркость →", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            Text(
                                text = if (timeTrouble.timeTroubleMovesCount == 0) {
                                    "Отличный тайм-менеджмент: ходов с остатком менее 20 секунд не зафиксировано."
                                } else {
                                    "Ходов в цейтноте (<20с): ${timeTrouble.timeTroubleMovesCount} в ${timeTrouble.gamesWithTimeTrouble} парт."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 14-ДНЕВНАЯ АКТИВНОСТЬ ====================

@Composable
private fun DailyActivityCard(
    points: List<DailyActivityPoint>,
    activeDaysStreak: Int
) {
    val totalGames14 = points.sumOf { it.gamesCount }
    val totalSolved14 = points.sumOf { it.puzzlesSolved }
    val maxDay = points.maxOfOrNull { it.totalActions }?.coerceAtLeast(1) ?: 1
    val gameColor = MaterialTheme.colorScheme.primary
    val puzzleColor = LocalChessPalette.current.brilliant
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ритм занятий (14 дней)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Партий: $totalGames14 · Решено задач: $totalSolved14",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (activeDaysStreak > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LocalChessPalette.current.caution.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = "Серия дней",
                                tint = LocalChessPalette.current.caution,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "$activeDaysStreak дн.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = LocalChessPalette.current.caution
                            )
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val n = points.size.coerceAtLeast(1)
                val gap = 4.dp.toPx()
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(4f)
                val minStub = 4.dp.toPx()

                points.forEachIndexed { i, day ->
                    val x = i * (barW + gap)
                    if (day.totalActions == 0) {
                        drawRoundRect(
                            color = emptyColor,
                            topLeft = Offset(x, size.height - minStub),
                            size = Size(barW, minStub),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                    } else {
                        val totalH = ((day.totalActions.toFloat() / maxDay) * size.height)
                            .coerceAtLeast(8.dp.toPx())
                        val gamesFrac = day.gamesCount.toFloat() / day.totalActions
                        val gamesH = totalH * gamesFrac
                        val puzzlesH = totalH - gamesH

                        if (puzzlesH > 0.5f) {
                            drawRoundRect(
                                color = puzzleColor,
                                topLeft = Offset(x, size.height - totalH),
                                size = Size(barW, totalH),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }
                        if (gamesH > 0.5f) {
                            drawRoundRect(
                                color = gameColor,
                                topLeft = Offset(x, size.height - gamesH),
                                size = Size(barW, gamesH),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = points.firstOrNull()?.dayLabel.orEmpty(),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "■ партии   ■ задачи",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Сегодня (${points.lastOrNull()?.dayLabel.orEmpty()})",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== ВКЛАДКА «ДЕБЮТЫ» (ECO-РЕПЕРТУАР) ====================

@Composable
private fun RepertoireOverviewBanner(
    repertoire: OpeningRepertoireSummary,
    selectedColor: ChessColor,
    onSelectColor: (ChessColor) -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = "Дебютный репертуар (ECO)",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Дебютный репертуар (ECO)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = buildString {
                                append("Дебютов: ${repertoire.uniqueOpeningsCount}")
                                append(" · Партий: ${repertoire.recognizedGamesCount}")
                                repertoire.averageBookMoves?.let {
                                    append(" · Теория: ${formatPct(it)} х.")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Переключатель репертуара За белых / За чёрных
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val whiteCount = repertoire.whiteOpenings.sumOf { it.gamesCount }
                val blackCount = repertoire.blackOpenings.sumOf { it.gamesCount }

                FilterChip(
                    selected = selectedColor == ChessColor.WHITE,
                    onClick = { onSelectColor(ChessColor.WHITE) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            text = "♔ За белых ($whiteCount)",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                FilterChip(
                    selected = selectedColor == ChessColor.BLACK,
                    onClick = { onSelectColor(ChessColor.BLACK) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            text = "♚ За чёрных ($blackCount)",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OpeningSpotlightsCard(
    color: ChessColor,
    best: OpeningLineStat?,
    weakest: OpeningLineStat?,
    onOpenLibrary: ((query: String, color: ChessColor?) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (best != null) {
            SpotlightOpeningBanner(
                badge = "Коронный дебют",
                icon = Icons.Default.EmojiEvents,
                accent = LocalChessPalette.current.positive,
                line = best,
                onClick = onOpenLibrary?.let { fn -> { fn(best.searchQuery, color) } }
            )
        }
        if (weakest != null) {
            SpotlightOpeningBanner(
                badge = "Уязвимый дебют",
                icon = Icons.Default.Warning,
                accent = LocalChessPalette.current.caution,
                line = weakest,
                onClick = onOpenLibrary?.let { fn -> { fn(weakest.searchQuery, color) } }
            )
        }
    }
}

@Composable
private fun SpotlightOpeningBanner(
    badge: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    line: OpeningLineStat,
    onClick: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = badge,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = line.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildList {
                        add("${line.gamesCount} парт. (+${line.wins} =${line.draws} −${line.losses})")
                        line.scorePercent?.let { add("${it.roundToInt()}% очков") }
                        (line.openingPhaseAccuracy ?: line.averageAccuracy)?.let {
                            add("точн. ${it.roundToInt()}%")
                        }
                        line.formattedExitEval()?.let { add("выход $it") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OpeningLinesListCard(
    color: ChessColor,
    lines: List<OpeningLineStat>,
    onOpenLibrary: ((query: String, color: ChessColor?) -> Unit)?,
    onOpenGameById: ((Long) -> Unit)?
) {
    val sideTitle = if (color == ChessColor.WHITE) "Репертуар белыми" else "Репертуар чёрными"
    var expandedKey by remember(color) { mutableStateOf<String?>(null) }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "$sideTitle (${lines.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (lines.isEmpty()) {
                Text(
                    text = "За этот цвет пока нет партий с определённым дебютом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                lines.forEachIndexed { idx, line ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    val itemKey = "${line.primaryEco.orEmpty()}_${line.openingName}"
                    val isExpanded = expandedKey == itemKey
                    OpeningLineItem(
                        line = line,
                        expanded = isExpanded,
                        onToggle = { expandedKey = if (isExpanded) null else itemKey },
                        onOpenLibrary = onOpenLibrary?.let { fn -> { fn(line.searchQuery, color) } },
                        onOpenLatestGame = line.latestGameId?.let { gid ->
                            onOpenGameById?.let { fn -> { fn(gid) } }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpeningLineItem(
    line: OpeningLineStat,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLibrary: (() -> Unit)?,
    onOpenLatestGame: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!line.primaryEco.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = line.primaryEco,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.openingName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildList {
                        add("${line.gamesCount} парт.")
                        (line.openingPhaseAccuracy ?: line.averageAccuracy)?.let {
                            add("точн. ${formatPct(it)}%")
                        }
                        line.averageBookMoves?.takeIf { it > 0.0 }?.let {
                            add("книга ${formatPct(it)} х.")
                        }
                        line.formattedExitEval()?.let { add("оценка $it") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = line.scorePercent?.let { "${it.roundToInt()}%" } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = scoreColor(line.scorePercent)
                )
                Text(
                    text = "+${line.wins}=${line.draws}−${line.losses}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        if (line.decidedGames > 0) {
            WdlStackedBar(
                wins = line.wins,
                draws = line.draws,
                losses = line.losses,
                height = 6.dp
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (line.ecoCodes.size > 1) {
                    Text(
                        text = "Коды ECO: ${line.ecoCodes.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (line.openingPhaseAccuracy != null || line.averageAccuracy != null) {
                    Text(
                        text = buildList {
                            line.openingPhaseAccuracy?.let { add("Точность ходов 1–12: ${formatPct(it)}%") }
                            line.averageAccuracy?.let { add("Общая точность: ${formatPct(it)}%") }
                            if (line.earlyMistakesCount > 0) add("Ранних ошибок: ${line.earlyMistakesCount}")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                line.keyIdea?.let { idea ->
                    Text(
                        text = "Ключевая идея: $idea",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                line.commonMistakeTip?.let { tip ->
                    Text(
                        text = "Типичная ловушка: $tip",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChessPalette.current.caution
                    )
                }

                if (onOpenLibrary != null || onOpenLatestGame != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (onOpenLibrary != null) {
                            FilledTonalButton(
                                onClick = onOpenLibrary,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Все партии", modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Все партии (${line.gamesCount})", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (onOpenLatestGame != null) {
                            OutlinedButton(
                                onClick = onOpenLatestGame,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 36.dp)
                            ) {
                                Text("Последняя", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EcoVolumesCard(
    families: List<EcoFamilyStat>,
    onSearchEco: ((String) -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Профиль по томам ECO (A–E)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            families.forEach { fam ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (onSearchEco != null) {
                                Modifier.clickable { onSearchEco(fam.code.toString()) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${fam.code}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${fam.title} (${fam.gamesCount})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = fam.subtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fam.decidedGames > 0) {
                            Spacer(Modifier.height(3.dp))
                            WdlStackedBar(fam.wins, fam.draws, fam.losses, height = 5.dp)
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = fam.scorePercent?.let { "${it.roundToInt()}%" } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor(fam.scorePercent)
                        )
                        fam.averageAccuracy?.let {
                            Text(
                                text = "точн. ${it.roundToInt()}%",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== ВКЛАДКА «ТАКТИКА» ====================

@Composable
private fun TacticalOverviewCard(
    currentRating: Int,
    training: GetUserStatisticsUseCase.TrainingStatistics,
    onStartTraining: ((TrainingMode) -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FitnessCenter, contentDescription = "Тактическая подготовка", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Text("Тактическая подготовка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Прогресс банка задач
            if (training.totalAvailableExercises > 0) {
                val solvedRatio = (training.uniqueExercisesSolved.toFloat() /
                    training.totalAvailableExercises.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Освоено задач банка",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${training.uniqueExercisesSolved} / ${training.totalAvailableExercises}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { solvedRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            // Карточки интервальной памяти SM-2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QualityBadgeChip(Modifier.weight(1f), "Закреплено", training.masteredExercisesCount, LocalChessPalette.current.positive)
                QualityBadgeChip(Modifier.weight(1f), "В изучении", training.inLearningCount, LocalChessPalette.current.insight)
                QualityBadgeChip(Modifier.weight(1f), "К повтору", training.dueForReviewCount, LocalChessPalette.current.caution)
                QualityBadgeChip(Modifier.weight(1f), "Из партий", training.personalMistakeExercises, LocalChessPalette.current.brilliant)
            }

            if (training.totalAttempts > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                MetricInlineRow("Решено с 1-й попытки", "${training.firstTryAccuracyPercent}%")
                MetricInlineRow("Общий процент решений", "${training.solveAccuracyPercent}% (${training.solvedAttempts}/${training.totalAttempts})")
                MetricInlineRow("Чистая серия (текущая / рекорд)", "${training.currentStreak} / ${training.bestStreak}")
                if (training.averageSolveTimeSeconds > 0) {
                    MetricInlineRow("Среднее время расчёта", "${training.averageSolveTimeSeconds} сек")
                }
                if (training.impulsiveMistakeCount > 0) {
                    MetricInlineRow(
                        label = "Импульсивные ошибки",
                        value = "${training.impulsiveMistakeCount} (${training.impulsiveMistakeRatePercent}%)",
                        valueColor = if (training.impulsiveMistakeRatePercent >= 35) LocalChessPalette.current.negative else LocalChessPalette.current.caution
                    )
                }
            } else {
                Text(
                    text = "Вы ещё не решали задачи за выбранный период — начните с умной сессии из 10 позиций.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TrainingRatingMetric(currentRating, training)

            if (onStartTraining != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val primaryMode = when {
                        training.dueForReviewCount > 0 -> TrainingMode.WeakPatterns
                        training.personalMistakeExercises > 0 -> TrainingMode.MyMistakes
                        else -> TrainingMode.SmartSession
                    }
                    val primaryText = when {
                        training.dueForReviewCount > 0 -> "Повторить (${training.dueForReviewCount})"
                        training.personalMistakeExercises > 0 -> "Мои ошибки (${training.personalMistakeExercises})"
                        else -> "Сессия 10"
                    }
                    Button(
                        onClick = { onStartTraining(primaryMode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { onStartTraining(TrainingMode.SmartSession) }
                    ) {
                        Text("Сессия 10")
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingTrendCard(
    points: List<RatingTrendPoint>,
    isProvisional: Boolean
) {
    val startElo = points.first().rating - points.first().delta
    val currentElo = points.last().rating
    val minElo = minOf(points.minOf { it.rating }, startElo)
    val maxElo = maxOf(points.maxOf { it.rating }, startElo)
    val netDelta = currentElo - startElo
    val lineColor = if (netDelta >= 0) LocalChessPalette.current.positive else LocalChessPalette.current.negative
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Кривая тактического рейтинга",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append("Последние ${points.size} задач · мин $minElo / макс $maxElo")
                            if (isProvisional) append(" · калибровка")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val sign = if (netDelta >= 0) "+$netDelta" else "$netDelta"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = lineColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = sign,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                }
            }

            val paddedMin = (minElo - 15).toFloat()
            val paddedMax = (maxElo + 15).toFloat()
            val span = (paddedMax - paddedMin).coerceAtLeast(30f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(125.dp)
            ) {
                val padX = 8.dp.toPx()
                val padY = 8.dp.toPx()
                val w = (size.width - padX * 2f).coerceAtLeast(1f)
                val h = (size.height - padY * 2f).coerceAtLeast(1f)

                val allValues = listOf(startElo) + points.map { it.rating }
                fun xOf(i: Int): Float = padX + (i.toFloat() / (allValues.size - 1).coerceAtLeast(1)) * w
                fun yOf(elo: Int): Float = padY + (1f - ((elo - paddedMin) / span).coerceIn(0f, 1f)) * h

                drawLine(
                    color = gridColor,
                    start = Offset(0f, yOf(startElo)),
                    end = Offset(size.width, yOf(startElo)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )

                val strokePath = Path()
                val fillPath = Path()
                allValues.forEachIndexed { i, elo ->
                    val x = xOf(i)
                    val y = yOf(elo)
                    if (i == 0) {
                        strokePath.moveTo(x, y)
                        fillPath.moveTo(x, padY + h)
                        fillPath.lineTo(x, y)
                    } else {
                        strokePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xOf(allValues.lastIndex), padY + h)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0.01f)),
                        startY = padY,
                        endY = padY + h
                    )
                )
                drawPath(
                    path = strokePath,
                    color = lineColor,
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawCircle(
                    color = lineColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(xOf(allValues.lastIndex), yOf(currentElo))
                )
            }
        }
    }
}

@Composable
private fun PatternMasteryCard(
    weakPatterns: List<PatternMastery>,
    strongPatterns: List<PatternMastery>,
    allPatterns: List<PatternMastery>,
    onStartTraining: ((TrainingMode) -> Unit)?
) {
    val displayed = remember(weakPatterns, strongPatterns, allPatterns) {
        (weakPatterns + strongPatterns)
            .distinctBy { it.pattern }
            .ifEmpty { allPatterns.take(6) }
    }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Владение тактическими мотивами",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            displayed.forEach { item ->
                val color = when {
                    item.masteryPercent >= 78 -> LocalChessPalette.current.positive
                    item.masteryPercent >= 55 -> LocalChessPalette.current.insight
                    else -> LocalChessPalette.current.caution
                }
                val mode = PlayerAnalyticsCalculator.mapPatternToTrainingMode(item)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onStartTraining != null) {
                                Modifier.clickable { onStartTraining(mode) }
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.pattern.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${item.masteryPercent}% (с 1-й: ${item.firstTryAccuracyPercent}%)",
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (item.masteryPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            if (weakPatterns.isNotEmpty() && onStartTraining != null) {
                FilledTonalButton(
                    onClick = { onStartTraining(TrainingMode.WeakPatterns) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отработать слабые мотивы")
                }
            }
        }
    }
}

@Composable
private fun ProblemThemesCard(
    themes: List<ActionableProblemTheme>,
    onStartTraining: ((TrainingMode) -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Частые ошибки в ваших партиях",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            themes.forEach { theme ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${theme.themeName} · ${theme.category}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${theme.mistakeCount} (${theme.percentage.roundToInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (theme.percentage / 100.0).toFloat().coerceIn(0.04f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = LocalChessPalette.current.caution,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    if (onStartTraining != null) {
                        TextButton(
                            onClick = { onStartTraining(theme.suggestedMode) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Тренир.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WdlStackedBar(
    wins: Int,
    draws: Int,
    losses: Int,
    height: Dp
) {
    val total = wins + draws + losses
    if (total <= 0) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (wins > 0) {
            Box(
                modifier = Modifier
                    .weight(wins.toFloat())
                    .fillMaxHeight()
                    .background(LocalChessPalette.current.positive)
            )
        }
        if (draws > 0) {
            Box(
                modifier = Modifier
                    .weight(draws.toFloat())
                    .fillMaxHeight()
                    .background(LocalChessPalette.current.neutral)
            )
        }
        if (losses > 0) {
            Box(
                modifier = Modifier
                    .weight(losses.toFloat())
                    .fillMaxHeight()
                    .background(LocalChessPalette.current.negative)
            )
        }
    }
}

@Composable
private fun MetricInlineRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun scoreColor(scorePercent: Double?): Color = when {
    scorePercent == null -> MaterialTheme.colorScheme.onSurface
    scorePercent >= 55.0 -> LocalChessPalette.current.positive
    scorePercent <= 44.0 -> LocalChessPalette.current.negative
    else -> MaterialTheme.colorScheme.onSurface
}


private fun formatPct(value: Double): String {
    val rounded = (value * 10.0).roundToInt() / 10.0
    return if (abs(rounded - rounded.roundToInt()) < 0.05) {
        "${rounded.roundToInt()}"
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}
