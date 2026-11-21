package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.PredefinedThemes
import com.example.chessmentor.domain.entity.ProblemTheme
import com.example.chessmentor.domain.entity.Theme
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.ActionableProblemTheme
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.CoachingAction
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.DailyActivityPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.GameAnalyticsReport
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.RatingTrendPoint
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.StatsPeriod
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.AttemptQuality
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseSitting
import com.example.chessmentor.domain.training.HintUsage
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.PatternMastery
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import kotlin.math.roundToInt

class GetUserStatisticsUseCase(
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository,
    private val exerciseRepository: ExerciseRepository,
    private val analyzedMoveRepository: AnalyzedMoveRepository? = null
) {
    companion object {
        internal const val BASELINE_RATING = 1200
        private const val MAX_CP_LOSS_FOR_AVG = 1000
        private const val MAX_SOLVE_SECONDS_FOR_AVG = 600
    }

    data class Input(
        val userId: Long,
        val period: StatsPeriod = StatsPeriod.ALL,
        val nowMs: Long = System.currentTimeMillis()
    )

    /**
     * Сводка тактической тренировки пользователя.
     */
    data class TrainingStatistics(
        val totalAttempts: Int = 0,
        val solvedAttempts: Int = 0,
        val uniqueExercisesSolved: Int = 0,
        val totalAvailableExercises: Int = 0,
        val personalMistakeExercises: Int = 0,
        val unresolvedFailedCount: Int = 0,
        val dueForReviewCount: Int = 0,
        val inLearningCount: Int = 0,
        val masteredExercisesCount: Int = 0,
        val firstTryAccuracyPercent: Int = 0,
        val solveAccuracyPercent: Int = 0,
        val impulsiveMistakeCount: Int = 0,
        val impulsiveMistakeRatePercent: Int = 0,
        val isRatingProvisional: Boolean = true,
        val currentStreak: Int = 0,
        val bestStreak: Int = 0,
        val averageSolveTimeSeconds: Int = 0,
        val weakPatterns: List<PatternMastery> = emptyList(),
        val strongPatterns: List<PatternMastery> = emptyList(),
        val allPatternMastery: List<PatternMastery> = emptyList(),
        val ratingHistory: List<RatingTrendPoint> = emptyList()
    )

    data class UserStatistics(
        val userId: Long,
        val nickname: String,
        val currentRating: Int,
        val skillLevel: String,
        val period: StatsPeriod = StatsPeriod.ALL,
        val totalGamesAllTime: Int = 0,
        val totalGames: Int,
        val analyzedGames: Int,
        val averageAccuracy: Double,
        val totalMistakes: Int,
        val blunders: Int,
        val mistakes: Int,
        val inaccuracies: Int,
        val averageEvaluationLoss: Double,
        val problemThemes: List<ProblemTheme>,
        val actionableProblemThemes: List<ActionableProblemTheme> = emptyList(),
        val ratingChange: Int,
        val accuracyTrend: String,
        val gameAnalytics: GameAnalyticsReport = GameAnalyticsReport(),
        val dailyActivity: List<DailyActivityPoint> = emptyList(),
        val activeDaysStreak: Int = 0,
        val training: TrainingStatistics,
        val coachingActions: List<CoachingAction> = emptyList(),
        val recommendations: List<String>
    )

    sealed class Result {
        data class Success(val statistics: UserStatistics) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val userId = user.id ?: return Result.Error("Некорректный идентификатор пользователя")

        val allGames = gameRepository.findByUserId(userId)
        val cutoff = input.period.cutoffMillis(input.nowMs)
        val scopedGames = if (cutoff != null) {
            allGames.filter { it.playedAt >= cutoff }
        } else {
            allGames
        }
        val scopedGameIds = scopedGames.mapNotNull { it.id }.toSet()

        val allMistakes = mistakeRepository.findByUserId(userId)
        val scopedMistakes = if (cutoff != null) {
            allMistakes.filter { it.gameId in scopedGameIds }
        } else {
            allMistakes
        }
        val mistakesByGameId = scopedMistakes.groupBy { it.gameId }

        // Пакетная загрузка проанализированных ходов без N+1 запросов
        val analyzedGameIds = scopedGames.filter { it.isAnalyzed() }.mapNotNull { it.id }
        val analyzedMovesByGameId: Map<Long, List<AnalyzedMove>> =
            if (analyzedMoveRepository != null && analyzedGameIds.isNotEmpty()) {
                analyzedMoveRepository.findByGameIds(analyzedGameIds).groupBy { it.gameId }
            } else {
                emptyMap()
            }

        val gameAnalytics = PlayerAnalyticsCalculator.computeGameAnalytics(
            games = scopedGames,
            analyzedMovesByGameId = analyzedMovesByGameId,
            mistakesByGameId = mistakesByGameId,
            period = input.period,
            nowMs = input.nowMs
        )

        val analyzedCount = gameAnalytics.overall.analyzedCount
        val averageAccuracy = gameAnalytics.overall.averageAccuracy ?: 0.0

        val blundersCount = scopedMistakes.count { it.mistakeType == MistakeType.BLUNDER }
        val mistakesCount = scopedMistakes.count { it.mistakeType == MistakeType.MISTAKE }
        val inaccuraciesCount = scopedMistakes.count { it.mistakeType == MistakeType.INACCURACY }

        val avgEvalLoss = gameAnalytics.overall.averageAcpl?.toDouble()
            ?: if (scopedMistakes.isNotEmpty()) {
                scopedMistakes.map { it.evaluationLoss.coerceIn(0, MAX_CP_LOSS_FOR_AVG) }.average()
            } else {
                0.0
            }

        val themeFrequency = scopedMistakes
            .groupingBy { it.themeId }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .associate { it.key to it.value }

        val actionableThemes = calculateActionableThemes(themeFrequency, scopedMistakes.size)
        val problemThemes = actionableThemes.map { t ->
            ProblemTheme(
                themeName = t.themeName,
                category = t.category,
                mistakeCount = t.mistakeCount,
                percentage = t.percentage
            )
        }

        val allAttempts = exerciseRepository.getAttemptsByUserId(userId)
            .sortedBy { it.attemptedAt }
        val scopedAttempts = if (cutoff != null) {
            allAttempts.filter { it.attemptedAt >= cutoff }
        } else {
            allAttempts
        }

        val trainingStats = calculateTrainingStatistics(
            userId = userId,
            playerElo = user.rating,
            nowMs = input.nowMs,
            allAttempts = allAttempts,
            scopedAttempts = scopedAttempts
        )

        val (dailyActivity, activeDaysStreak) = PlayerAnalyticsCalculator.computeDailyActivity(
            games = allGames,
            attempts = allAttempts,
            nowMs = input.nowMs
        )

        val coachingActions = PlayerAnalyticsCalculator.buildCoachingActions(
            report = gameAnalytics,
            dueForReviewCount = trainingStats.dueForReviewCount,
            personalMistakeExercises = trainingStats.personalMistakeExercises,
            impulsiveMistakeCount = trainingStats.impulsiveMistakeCount,
            impulsiveMistakeRatePercent = trainingStats.impulsiveMistakeRatePercent,
            weakPatterns = trainingStats.weakPatterns,
            problemThemes = problemThemes
        )

        val ratingChange = user.rating - BASELINE_RATING
        val accuracyTrend = formatAccuracyTrend(gameAnalytics)
        val recommendations = coachingActions.map { "${it.title}. ${it.description}" }

        return Result.Success(
            UserStatistics(
                userId = userId,
                nickname = user.nickname,
                currentRating = user.rating,
                skillLevel = user.skillLevel.getDisplayName(),
                period = input.period,
                totalGamesAllTime = allGames.size,
                totalGames = scopedGames.size,
                analyzedGames = analyzedCount,
                averageAccuracy = averageAccuracy,
                totalMistakes = scopedMistakes.size,
                blunders = blundersCount,
                mistakes = mistakesCount,
                inaccuracies = inaccuraciesCount,
                averageEvaluationLoss = avgEvalLoss,
                problemThemes = problemThemes,
                actionableProblemThemes = actionableThemes,
                ratingChange = ratingChange,
                accuracyTrend = accuracyTrend,
                gameAnalytics = gameAnalytics,
                dailyActivity = dailyActivity,
                activeDaysStreak = activeDaysStreak,
                training = trainingStats,
                coachingActions = coachingActions,
                recommendations = recommendations
            )
        )
    }

    private fun formatAccuracyTrend(report: GameAnalyticsReport): String {
        val delta = report.accuracyTrendDelta ?: return "Стабильно"
        val signed = if (delta >= 0) {
            "+${PlayerAnalyticsCalculator.formatOne(delta)}%"
        } else {
            "${PlayerAnalyticsCalculator.formatOne(delta)}%"
        }
        return when (report.accuracyTrendDirection) {
            PlayerAnalyticsCalculator.TrendDirection.IMPROVING -> "↑ $signed"
            PlayerAnalyticsCalculator.TrendDirection.DECLINING -> "↓ $signed"
            PlayerAnalyticsCalculator.TrendDirection.STABLE -> "→ $signed"
            PlayerAnalyticsCalculator.TrendDirection.NOT_ENOUGH_DATA -> "Стабильно"
        }
    }

    private suspend fun calculateTrainingStatistics(
        userId: Long,
        playerElo: Int,
        nowMs: Long,
        allAttempts: List<com.example.chessmentor.domain.entity.ExerciseAttempt>,
        scopedAttempts: List<com.example.chessmentor.domain.entity.ExerciseAttempt>
    ): TrainingStatistics {
        // Гарантируем учёт встроенного банка задач в общем счётчике доступных упражнений
        CuratedExerciseBank.ensureSeeded(exerciseRepository)

        val accessibleExercises = exerciseRepository.findAccessibleByUserId(userId)
        val personalCount = accessibleExercises.count { it.sourceGameId != null }

        if (allAttempts.isEmpty()) {
            return TrainingStatistics(
                totalAvailableExercises = accessibleExercises.size,
                personalMistakeExercises = personalCount
            )
        }

        // Подтягиваем упражнения по всем попыткам для точного расписания SM-2 и истории рейтинга
        val exercisesById: MutableMap<Long, Exercise> = accessibleExercises
            .mapNotNull { ex -> ex.id?.let { it to ex } }
            .toMap()
            .toMutableMap()
        val missingIds = allAttempts.map { it.exerciseId }.distinct().filter { it !in exercisesById }
        if (missingIds.isNotEmpty()) {
            exerciseRepository.findByIds(missingIds).forEach { ex ->
                ex.id?.let { id -> exercisesById[id] = ex }
            }
        }

        // Расписание интервального повторения и усвоение всегда считаются по полной истории памяти
        val schedule = SpacedRepetitionScheduler.buildSchedule(allAttempts, exercisesById)
        val accessibleIds = accessibleExercises.mapNotNull { it.id }.toSet()
        val accessibleCards = schedule.filterKeys { it in accessibleIds }.values
        val dueCount = accessibleCards.count { it.isDue(nowMs) }
        val masteredCount = accessibleCards.count { it.isMastered }
        val inLearningCount = accessibleCards.count { !it.isMastered }

        val uniqueSolvedAll = allAttempts.groupBy { it.exerciseId }
            .values
            .count { list -> list.any { it.solved } }
        val unresolvedFailedAll = allAttempts.groupBy { it.exerciseId }
            .values
            .count { list -> list.any { !it.solved } && list.none { it.solved } }

        val allHistorySittingsCount = allAttempts.groupBy { it.exerciseId }
            .values
            .sumOf { list ->
                val exId = list.first().exerciseId
                SpacedRepetitionScheduler.groupIntoSittings(list, exercisesById[exId]).size
            }

        // Просмотр решения без своей попытки не превращает стартовую оценку в результат.
        val hasRatedAttempt = allAttempts.any {
            AttemptMetadata.extractHintUsage(it) != HintUsage.FULL_SOLUTION || AttemptMetadata.extractRetryCount(it) > 0
        }
        val ratingHistory = if (!hasRatedAttempt) emptyList() else PlayerAnalyticsCalculator.computeRatingHistory(
            currentRating = playerElo,
            attempts = allAttempts,
            exercisesById = exercisesById
        )

        if (scopedAttempts.isEmpty()) {
            return TrainingStatistics(
                totalAttempts = 0,
                uniqueExercisesSolved = uniqueSolvedAll,
                totalAvailableExercises = accessibleExercises.size,
                personalMistakeExercises = personalCount,
                unresolvedFailedCount = unresolvedFailedAll,
                dueForReviewCount = dueCount,
                inLearningCount = inLearningCount,
                masteredExercisesCount = masteredCount,
                isRatingProvisional = allHistorySittingsCount < 10,
                ratingHistory = ratingHistory
            )
        }

        val totalAttempts = scopedAttempts.size
        val solvedAttempts = scopedAttempts.count { it.solved }
        val solveAccuracy = ((solvedAttempts * 100.0) / totalAttempts).roundToInt().coerceIn(0, 100)

        val scopedSittings = scopedAttempts
            .groupBy { it.exerciseId }
            .flatMap { (exId, list) ->
                SpacedRepetitionScheduler.groupIntoSittings(list, exercisesById[exId])
            }
            .sortedBy { it.finishedAt }

        val firstTryClean = scopedSittings.count { it.quality.isCleanFirstTry }
        val firstTryAccuracy = if (scopedSittings.isNotEmpty()) {
            ((firstTryClean * 100.0) / scopedSittings.size).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val errorSittings = scopedSittings.count {
            it.quality == AttemptQuality.FAILED || it.quality == AttemptQuality.RETRY_SOLVE
        }
        val impulsiveCount = scopedAttempts.count { attempt ->
            !attempt.solved && AttemptMetadata.isImpulsive(attempt)
        }
        val impulsiveRate = if (errorSittings > 0) {
            ((impulsiveCount.coerceAtMost(errorSittings) * 100.0) / errorSittings)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val (currentStreak, bestStreak) = calculateCleanStreaks(scopedSittings)

        val solvedDurations = scopedAttempts
            .filter { it.solved && it.timeSpentSeconds > 0 }
            .map { it.timeSpentSeconds.coerceAtMost(MAX_SOLVE_SECONDS_FOR_AVG) }
        val avgSolveSeconds = if (solvedDurations.isNotEmpty()) {
            solvedDurations.average().roundToInt()
        } else {
            0
        }

        val mastery = TrainingRatingCalculator.computePatternMastery(
            exercisesById = exercisesById,
            attempts = scopedAttempts,
            playerElo = playerElo
        )
        val weakPatterns = mastery
            .filter { it.level == MasteryLevel.WEAK || (it.level == MasteryLevel.LEARNING && it.masteryPercent < 68) }
            .take(5)
        val strongPatterns = mastery
            .filter { it.level == MasteryLevel.STRONG || (it.solvedAttempts >= 1 && it.masteryPercent >= 80) }
            .sortedWith(
                compareByDescending<PatternMastery> { it.masteryPercent }
                    .thenByDescending { it.firstTryAccuracyPercent }
                    .thenByDescending { it.solvedAttempts }
            )
            .take(5)

        return TrainingStatistics(
            totalAttempts = totalAttempts,
            solvedAttempts = solvedAttempts,
            uniqueExercisesSolved = uniqueSolvedAll,
            totalAvailableExercises = accessibleExercises.size,
            personalMistakeExercises = personalCount,
            unresolvedFailedCount = unresolvedFailedAll,
            dueForReviewCount = dueCount,
            inLearningCount = inLearningCount,
            masteredExercisesCount = masteredCount,
            firstTryAccuracyPercent = firstTryAccuracy,
            solveAccuracyPercent = solveAccuracy,
            impulsiveMistakeCount = impulsiveCount,
            impulsiveMistakeRatePercent = impulsiveRate,
            isRatingProvisional = allHistorySittingsCount < 10,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            averageSolveTimeSeconds = avgSolveSeconds,
            weakPatterns = weakPatterns,
            strongPatterns = strongPatterns,
            allPatternMastery = mastery,
            ratingHistory = ratingHistory
        )
    }

    /**
     * Честная серия решений без ошибок и без раскрытия полного ответа:
     * дорешивание со 2-й попытки после промаха НЕ раздувает серию.
     */
    private fun calculateCleanStreaks(orderedSittings: List<ExerciseSitting>): Pair<Int, Int> {
        var best = 0
        var running = 0
        for (sitting in orderedSittings) {
            when (sitting.quality) {
                AttemptQuality.CLEAN_FAST_SOLVE,
                AttemptQuality.SLOW_CLEAN_SOLVE,
                AttemptQuality.HINT_SOLVE -> {
                    running++
                    if (running > best) best = running
                }
                AttemptQuality.RETRY_SOLVE,
                AttemptQuality.FAILED -> {
                    running = 0
                }
            }
        }
        return running to best
    }

    private fun calculateActionableThemes(
        themeFrequency: Map<Long, Int>,
        totalMistakes: Int
    ): List<ActionableProblemTheme> {
        if (themeFrequency.isEmpty() || totalMistakes == 0) {
            return emptyList()
        }

        return themeFrequency.map { (themeId, count) ->
            val theme = getThemeById(themeId)
            ActionableProblemTheme(
                themeId = themeId,
                themeName = theme.description,
                category = theme.category.getDisplayName(),
                mistakeCount = count,
                percentage = (count.toDouble() / totalMistakes) * 100.0,
                suggestedMode = PlayerAnalyticsCalculator.mapThemeIdToTrainingMode(themeId)
            )
        }.sortedByDescending { it.mistakeCount }
    }

    private fun getThemeById(id: Long): Theme {
        return PredefinedThemes.getAll().firstOrNull { it.id == id }
            ?: PredefinedThemes.PIECE_ACTIVITY
    }
}
