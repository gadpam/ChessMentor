package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chessmentor.presentation.ui.components.screen.TrainingRatingMetric
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.RatingTrendPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.StatsPeriod
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase.TrainingStatistics
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase.UserStatistics
import com.example.chessmentor.presentation.ui.components.screen.OverviewMetrics
import com.example.chessmentor.presentation.ui.components.screen.StatisticsPeriodLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatisticsPeriodTest {
    @get:Rule val ui = createComposeRule()
    private val all = UserStatistics(userId = 1, nickname = "Rival", currentRating = 1200, skillLevel = "",
        totalGames = 18, analyzedGames = 10, averageAccuracy = 75.0, totalMistakes = 0,
        blunders = 0, mistakes = 0, inaccuracies = 0, averageEvaluationLoss = 0.0, problemThemes = emptyList(),
        ratingChange = 0, accuracyTrend = "", training = TrainingStatistics(), recommendations = emptyList())

    @Test fun overviewShowsAccuracySampleButNoTrainingRatingAtLargeType() {
        var stats by mutableStateOf(all.copy(currentRating = 1300, ratingChange = 100,
            gameAnalytics = all.gameAnalytics.copy(overall = all.gameAnalytics.overall.copy(
                averageAccuracy = 0.0, accuracySampleSize = 1))))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) { OverviewMetrics(stats) }
            }
        }
        ui.onNodeWithText("По 1 партии").assert(hasText("Точность: 0%")).assertIsDisplayed()
        ui.onNodeWithText("Разобрано: 10").assert(hasText("Партий: 18"))
        ui.onNodeWithText("Рейтинг", substring = true).assertDoesNotExist()
        ui.runOnIdle { stats = all.copy(training = all.training.copy(isRatingProvisional = false)) }
        ui.onNodeWithText("Точность не рассчитана").assertIsDisplayed()
        ui.onNodeWithText("Точность: 0%").assertDoesNotExist()
    }

    @Test fun tacticsShowsProvisionalRatingOnlyAfterRatedPractice() {
        var training by mutableStateOf(TrainingStatistics())
        ui.setContent { TrainingRatingMetric(1200, training) }
        val rating = ui.onNodeWithText("Рейтинг в задачах ChessMentor: 1200")
        rating.assertDoesNotExist()
        ui.runOnIdle { training = training.copy(ratingHistory = listOf(RatingTrendPoint(1, 0L, 1200, 0, true))) }
        rating.assert(hasText("Предварительная оценка")).assertIsDisplayed()
        ui.runOnIdle { training = training.copy(isRatingProvisional = false) }
        rating.assertIsDisplayed()
        ui.onNodeWithText("Предварительная оценка").assertDoesNotExist()
    }

    @Test fun staleReportNeverAppearsUnderAnotherPeriodAndRetryTargetsSelection() {
        var period by mutableStateOf(StatsPeriod.ALL)
        var report by mutableStateOf(all)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var retried: StatsPeriod? = null
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    StatisticsPeriodLayout(PaddingValues(), period, report, loading, error,
                        select = { period = it; loading = true }, retry = { retried = period }) { data ->
                        item { Text("Партий: ${data.totalGames}") }
                    }
                }
            }
        }
        ui.onNodeWithText("Партий: 18").assertIsDisplayed()
        ui.onNodeWithText("7 дней").performClick()
        ui.onNodeWithText("Партий: 18").assertDoesNotExist()
        ui.runOnIdle { loading = false; error = "Не удалось прочитать библиотеку" }
        ui.onNodeWithText("7 дней").assertIsSelected()
        ui.onNodeWithText("Не удалось загрузить отчёт: 7 дней").assertIsDisplayed()
        ui.onNodeWithText("Партий: 18").assertDoesNotExist()
        ui.onNodeWithText("Повторить").performClick()
        ui.runOnIdle {
            assertEquals(StatsPeriod.LAST_7_DAYS, retried)
            error = null; report = all.copy(period = period, totalGames = 2)
        }
        ui.onNodeWithText("Партий: 2").assertIsDisplayed()
        ui.runOnIdle { error = "Не удалось прочитать библиотеку" }
        ui.onNodeWithText("Отчёт не обновлён: 7 дней").assertIsDisplayed()
        ui.onNodeWithText("Партий: 2").performScrollTo().assertIsDisplayed()
    }
}
