package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.PredefinedThemes
import com.example.chessmentor.domain.entity.ProblemTheme
import com.example.chessmentor.domain.entity.Theme
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.PatternMastery
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import kotlin.math.roundToInt

class GetUserStatisticsUseCase(
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository,
    private val exerciseRepository: ExerciseRepository
) {
    companion object {
        private const val BASELINE_RATING = 1200
        private const val MAX_CP_LOSS_FOR_AVG = 1000
        private const val MAX_SOLVE_SECONDS_FOR_AVG = 600
    }

    data class Input(
        val userId: Long,
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
        val currentStreak: Int = 0,
        val bestStreak: Int = 0,
        val averageSolveTimeSeconds: Int = 0,
        val weakPatterns: List<PatternMastery> = emptyList(),
        val strongPatterns: List<PatternMastery> = emptyList()
    )

    data class UserStatistics(
        val userId: Long,
        val nickname: String,
        val currentRating: Int,
        val skillLevel: String,
        val totalGames: Int,
        val analyzedGames: Int,
        val averageAccuracy: Double,
        val totalMistakes: Int,
        val blunders: Int,
        val mistakes: Int,
        val inaccuracies: Int,
        val averageEvaluationLoss: Double,
        val problemThemes: List<ProblemTheme>,
        val ratingChange: Int,
        val accuracyTrend: String,
        val training: TrainingStatistics,
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
        val analyzedGames = allGames.filter { it.isAnalyzed() }

        val totalGames = allGames.size
        val analyzedCount = analyzedGames.size
        val accuracies = analyzedGames.mapNotNull { it.accuracy }
        val averageAccuracy = if (accuracies.isNotEmpty()) accuracies.average() else 0.0

        val allMistakes = mistakeRepository.findByUserId(userId)
        val blundersCount = allMistakes.count { it.mistakeType == MistakeType.BLUNDER }
        val mistakesCount = allMistakes.count { it.mistakeType == MistakeType.MISTAKE }
        val inaccuraciesCount = allMistakes.count { it.mistakeType == MistakeType.INACCURACY }

        val avgEvalLoss = if (allMistakes.isNotEmpty()) {
            allMistakes.map { it.evaluationLoss.coerceIn(0, MAX_CP_LOSS_FOR_AVG) }.average()
        } else {
            0.0
        }

        val themeFrequency = mistakeRepository.getMostFrequentThemesByUserId(userId, 5)
        val problemThemes = calculateProblemThemes(themeFrequency, allMistakes.size)

        val trainingStats = calculateTrainingStatistics(userId, input.nowMs)

        val ratingChange = user.rating - BASELINE_RATING
        val accuracyTrend = calculateAccuracyTrend(analyzedGames)

        val recommendations = generateRecommendations(
            analyzedCount = analyzedCount,
            blundersCount = blundersCount,
            problemThemes = problemThemes,
            averageAccuracy = averageAccuracy,
            training = trainingStats
        )

        val statistics = UserStatistics(
            userId = userId,
            nickname = user.nickname,
            currentRating = user.rating,
            skillLevel = user.skillLevel.getDisplayName(),
            totalGames = totalGames,
            analyzedGames = analyzedCount,
            averageAccuracy = averageAccuracy,
            totalMistakes = allMistakes.size,
            blunders = blundersCount,
            mistakes = mistakesCount,
            inaccuracies = inaccuraciesCount,
            averageEvaluationLoss = avgEvalLoss,
            problemThemes = problemThemes,
            ratingChange = ratingChange,
            accuracyTrend = accuracyTrend,
            training = trainingStats,
            recommendations = recommendations
        )

        return Result.Success(statistics)
    }

    private suspend fun calculateTrainingStatistics(userId: Long, nowMs: Long): TrainingStatistics {
        // Гарантируем учёт встроенного банка задач в общем счётчике доступных упражнений
        CuratedExerciseBank.ensureSeeded(exerciseRepository)

        val accessibleExercises = exerciseRepository.findAccessibleByUserId(userId)
        val personalCount = accessibleExercises.count { it.sourceGameId != null }
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
            .sortedBy { it.attemptedAt }

        if (attempts.isEmpty()) {
            return TrainingStatistics(
                totalAvailableExercises = accessibleExercises.size,
                personalMistakeExercises = personalCount
            )
        }

        // Подтягиваем также упражнения, по которым были попытки (на случай если партия позже удалена)
        val exercisesById = accessibleExercises
            .mapNotNull { ex -> ex.id?.let { it to ex } }
            .toMutableMap()
        val missingIds = attempts.map { it.exerciseId }.distinct().filter { it !in exercisesById }
        if (missingIds.isNotEmpty()) {
            exerciseRepository.findByIds(missingIds).forEach { ex ->
                ex.id?.let { exercisesById[it] = it }
            }
        }

        val totalAttempts = attempts.size
        val solvedAttempts = attempts.count { it.solved }
        val solveAccuracy = ((solvedAttempts * 100.0) / totalAttempts).roundToInt().coerceIn(0, 100)

        val byExercise = attempts.groupBy { it.exerciseId }
        val uniqueSolved = byExercise.values.count { list -> list.any { it.solved } }
        val unresolvedFailed = byExercise.values.count { list ->
            list.any { !it.solved } && list.none { it.solved }
        }

        // Расчёт долговременного усвоения (Лейтнер / SM-2) и честной точности с 1-й попытки
        val allSittings = byExercise.flatMap { (exId, list) ->
            SpacedRepetitionScheduler.groupIntoSittings(list, exercisesById[exId])
        }
        val firstTryClean = allSittings.count { it.quality.isCleanFirstTry }
        val firstTryAccuracy = if (allSittings.isNotEmpty()) {
            ((firstTryClean * 100.0) / allSittings.size).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val schedule = SpacedRepetitionScheduler.buildSchedule(attempts, exercisesById)
        val accessibleIds = accessibleExercises.mapNotNull { it.id }.toSet()
        val accessibleCards = schedule.filterKeys { it in accessibleIds }.values
        val dueCount = accessibleCards.count { it.isDue(nowMs) }
        val masteredCount = accessibleCards.count { it.isMastered }
        val inLearningCount = accessibleCards.count { !it.isMastered }

        val (currentStreak, bestStreak) = calculateStreaks(attempts)

        val solvedDurations = attempts
            .filter { it.solved && it.timeSpentSeconds > 0 }
            .map { it.timeSpentSeconds.coerceAtMost(MAX_SOLVE_SECONDS_FOR_AVG) }
        val avgSolveSeconds = if (solvedDurations.isNotEmpty()) {
            solvedDurations.average().roundToInt()
        } else {
            0
        }

        val mastery = TrainingRatingCalculator.computePatternMastery(exercisesById, attempts)
        val weakPatterns = mastery
            .filter { it.level == MasteryLevel.WEAK || (it.level == MasteryLevel.LEARNING && it.accuracyPercent < 70) }
            .take(4)
        val strongPatterns = mastery
            .filter { it.level == MasteryLevel.STRONG || (it.solvedAttempts >= 1 && it.accuracyPercent >= 80) }
            .sortedWith(
                compareByDescending<PatternMastery> { it.accuracyPercent }
                    .thenByDescending { it.solvedAttempts }
            )
            .take(4)

        return TrainingStatistics(
            totalAttempts = totalAttempts,
            solvedAttempts = solvedAttempts,
            uniqueExercisesSolved = uniqueSolved,
            totalAvailableExercises = accessibleExercises.size,
            personalMistakeExercises = personalCount,
            unresolvedFailedCount = unresolvedFailed,
            dueForReviewCount = dueCount,
            inLearningCount = inLearningCount,
            masteredExercisesCount = masteredCount,
            firstTryAccuracyPercent = firstTryAccuracy,
            solveAccuracyPercent = solveAccuracy,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            averageSolveTimeSeconds = avgSolveSeconds,
            weakPatterns = weakPatterns,
            strongPatterns = strongPatterns
        )
    }

    private fun calculateStreaks(orderedAttempts: List<ExerciseAttempt>): Pair<Int, Int> {
        var best = 0
        var running = 0
        for (attempt in orderedAttempts) {
            if (attempt.solved) {
                running++
                if (running > best) best = running
            } else {
                running = 0
            }
        }
        return running to best
    }

    private fun calculateProblemThemes(
        themeFrequency: Map<Long, Int>,
        totalMistakes: Int
    ): List<ProblemTheme> {
        if (themeFrequency.isEmpty() || totalMistakes == 0) {
            return emptyList()
        }

        return themeFrequency.map { (themeId, count) ->
            val theme = getThemeById(themeId)
            ProblemTheme(
                themeName = theme.description,
                category = theme.category.getDisplayName(),
                mistakeCount = count,
                percentage = (count.toDouble() / totalMistakes) * 100
            )
        }.sortedByDescending { it.mistakeCount }
    }

    private fun getThemeById(id: Long): Theme {
        return PredefinedThemes.getAll().firstOrNull { it.id == id }
            ?: PredefinedThemes.PIECE_ACTIVITY
    }

    private fun calculateAccuracyTrend(analyzedGames: List<Game>): String {
        val withAccuracy = analyzedGames.filter { it.accuracy != null }.sortedBy { it.playedAt }
        if (withAccuracy.size < 4) {
            return "Стабильно"
        }

        val midpoint = withAccuracy.size / 2
        val firstAvg = withAccuracy.take(midpoint).mapNotNull { it.accuracy }.average()
        val secondAvg = withAccuracy.drop(midpoint).mapNotNull { it.accuracy }.average()

        return when {
            secondAvg > firstAvg + 5 -> "↑ Растёт"
            secondAvg < firstAvg - 5 -> "↓ Снижается"
            else -> "Стабильно"
        }
    }

    private fun generateRecommendations(
        analyzedCount: Int,
        blundersCount: Int,
        problemThemes: List<ProblemTheme>,
        averageAccuracy: Double,
        training: TrainingStatistics
    ): List<String> {
        if (analyzedCount == 0 && training.totalAttempts == 0) {
            return listOf(
                "Решите «Задачу дня» или загрузите и проанализируйте первую партию, чтобы получить персональные советы."
            )
        }

        val recommendations = mutableListOf<String>()

        if (training.dueForReviewCount > 0) {
            recommendations.add(
                "По расписанию интервального повторения подошло задач: ${training.dueForReviewCount} — " +
                    "пройдите повтор, чтобы закрепить мотивы в долговременной памяти."
            )
        } else if (training.unresolvedFailedCount > 0) {
            recommendations.add(
                "К повторению осталось ${training.unresolvedFailedCount} нерешённых задач — " +
                    "откройте тренировку, чтобы закрыть пробелы."
            )
        }

        training.weakPatterns.firstOrNull()?.let { weakest ->
            recommendations.add(
                "Слабый тактический мотив: «${weakest.pattern.displayName}» " +
                    "(точность ${weakest.accuracyPercent}%). Рекомендуем режим «Слабые темы»."
            )
        }

        if (analyzedCount > 0) {
            if (blundersCount > 5) {
                recommendations.add(
                    "В партиях много зевков ($blundersCount). " +
                        "Перед каждым ходом проверяйте шахи, взятия и угрозы соперника."
                )
            }

            when {
                averageAccuracy < 70 -> {
                    recommendations.add(
                        "Средняя точность в партиях — ${averageAccuracy.toInt()}%. " +
                            "Попробуйте контроль времени с добавлением и отработку «Моих ошибок»."
                    )
                }
                averageAccuracy > 85 -> {
                    recommendations.add(
                        "Отличная точность игры (${averageAccuracy.toInt()}%)! " +
                            "Попробуйте сложные комбинации на мат и двойной шах."
                    )
                }
            }

            problemThemes.firstOrNull()?.let { topProblem ->
                recommendations.add(
                    "В партиях чаще всего встречается тема «${topProblem.themeName}» " +
                        "(${topProblem.mistakeCount} раз)."
                )
            }
        } else {
            recommendations.add(
                "Загрузите свои партии в формате PGN — мы автоматически создадим задачи по вашим реальным ошибкам."
            )
        }

        if (recommendations.isEmpty()) {
            recommendations.add(
                "Отличная динамика! Продолжайте чередовать разбор собственных партий и ежедневные задачи."
            )
        }

        return recommendations
    }
}
