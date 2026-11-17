package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.PredefinedThemes
import com.example.chessmentor.domain.entity.ProblemTheme
import com.example.chessmentor.domain.entity.Theme
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository

class GetUserStatisticsUseCase(
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val mistakeRepository: MistakeRepository
) {
    companion object {
        private const val BASELINE_RATING = 1200
        private const val MAX_CP_LOSS_FOR_AVG = 1000
    }

    data class Input(
        val userId: Long
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

        val ratingChange = user.rating - BASELINE_RATING
        val accuracyTrend = calculateAccuracyTrend(analyzedGames)

        val recommendations = generateRecommendations(
            analyzedCount = analyzedCount,
            blundersCount = blundersCount,
            problemThemes = problemThemes,
            averageAccuracy = averageAccuracy
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
            recommendations = recommendations
        )

        return Result.Success(statistics)
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
        averageAccuracy: Double
    ): List<String> {
        if (analyzedCount == 0) {
            return listOf("Загрузите и проанализируйте первую партию, чтобы получить персональные советы.")
        }

        val recommendations = mutableListOf<String>()

        if (blundersCount > 5) {
            recommendations.add(
                "У вас много грубых ошибок ($blundersCount). " +
                    "Рекомендуем больше времени уделять проверке угроз соперника."
            )
        }

        when {
            averageAccuracy < 70 -> {
                recommendations.add(
                    "Средняя точность игры — ${averageAccuracy.toInt()}%. " +
                        "Попробуйте играть с более длинным контролем времени."
                )
            }
            averageAccuracy > 85 -> {
                recommendations.add(
                    "Отличная точность игры (${averageAccuracy.toInt()}%)! " +
                        "Можете пробовать играть против более сильных соперников."
                )
            }
        }

        if (problemThemes.isNotEmpty()) {
            val topProblem = problemThemes.first()
            recommendations.add(
                "Чаще всего ошибки встречаются в теме «${topProblem.themeName}» " +
                    "(${topProblem.mistakeCount} раз). Потренируйте похожие задачи."
            )
        }

        if (recommendations.isEmpty()) {
            recommendations.add(
                "Вы играете стабильно! Продолжайте регулярно разбирать свои партии."
            )
        }

        return recommendations
    }
}
