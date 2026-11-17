// domain/usecase/GetTrainingExerciseUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import com.example.chessmentor.domain.util.ChessMoveUtils

/**
 * Use Case: Адаптивный подбор тактического упражнения с поддержкой:
 * - автоматического наполнения встроенного банка задач;
 * - изоляции пользовательских задач по `userId`;
 * - интервального повторения нерешённых задач после ошибок;
 * - режимов [TrainingMode] (все, мои ошибки, слабые темы, задача дня, по теме).
 */
class GetTrainingExerciseUseCase(
    private val userRepository: UserRepository,
    private val mistakeRepository: MistakeRepository,
    private val exerciseRepository: ExerciseRepository
) {
    companion object {
        private const val TAG = "GetTrainingExercise"
    }

    data class Input(
        val userId: Long,
        val excludeExerciseId: Long = -1,
        val preferredPattern: String? = null,
        val mode: TrainingMode = TrainingMode.All,
        val epochDay: Long = CuratedExerciseBank.currentEpochDay()
    )

    sealed class Result {
        data class Success(
            val exercise: Exercise,
            val isAlreadySolvedDaily: Boolean = false,
            val isReviewOfFailed: Boolean = false
        ) : Result()

        data class Error(val message: String) : Result()
        object NoSuitableExercises : Result()
    }

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val userId = user.id ?: return Result.Error("Некорректный идентификатор пользователя")

        // Гарантируем, что встроенный банк тактических задач доступен
        if (input.mode !is TrainingMode.MyMistakes) {
            CuratedExerciseBank.ensureSeeded(exerciseRepository)
        }

        Log.d(TAG, "Selecting exercise for user=$userId mode=${input.mode.routeKey} exclude=${input.excludeExerciseId}")

        return when (val mode = input.mode) {
            TrainingMode.Daily -> selectDailyExercise(userId, input.epochDay)
            TrainingMode.MyMistakes -> selectUserMistakeExercise(user, userId, input.excludeExerciseId)
            TrainingMode.WeakPatterns -> selectWeakPatternExercise(user, userId, input.excludeExerciseId)
            is TrainingMode.ByTheme -> selectThemeExercise(user, userId, mode.theme, input.excludeExerciseId)
            TrainingMode.All -> selectAdaptiveExercise(user, userId, input.excludeExerciseId, input.preferredPattern)
        }
    }

    private suspend fun selectDailyExercise(userId: Long, epochDay: Long): Result {
        val (_, dailyExercise) = CuratedExerciseBank.getOrCreateDailyExercise(
            exerciseRepository = exerciseRepository,
            epochDay = epochDay
        )
        val alreadySolved = dailyExercise.id?.let { id ->
            exerciseRepository.hasUserSolvedExercise(userId, id)
        } ?: false
        return Result.Success(
            exercise = dailyExercise,
            isAlreadySolvedDaily = alreadySolved
        )
    }

    private suspend fun selectUserMistakeExercise(
        user: User,
        userId: Long,
        excludeId: Long
    ): Result {
        val existing = exerciseRepository.findUserMistakeExercise(userId, excludeId)
        if (existing != null) {
            return Result.Success(existing)
        }

        // Резерв: если генератор ещё не успел обработать свежие ошибки — создаём задачу на лету
        val fallback = createFallbackExerciseFromMistake(user, userId)
        return if (fallback != null) {
            Result.Success(fallback)
        } else {
            Result.NoSuitableExercises
        }
    }

    private suspend fun selectWeakPatternExercise(
        user: User,
        userId: Long,
        excludeId: Long
    ): Result {
        val targetDifficulty = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Сначала даём дорешать конкретную задачу, где пользователь ошибался и ещё не решил
        val unresolvedFailed = exerciseRepository.findFailedUnresolvedExercise(userId, excludeId)
        if (unresolvedFailed != null) {
            return Result.Success(unresolvedFailed, isReviewOfFailed = true)
        }

        // 2. Анализируем точность по тактическим паттернам и подбираем задачи по самым слабым
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
        if (attempts.isNotEmpty()) {
            val exercises = exerciseRepository.findAccessibleByUserId(userId)
                .mapNotNull { ex -> ex.id?.let { it to ex } }
                .toMap()
            val mastery = TrainingRatingCalculator.computePatternMastery(exercises, attempts)
            val candidatePatterns = mastery
                .filter { it.level == MasteryLevel.WEAK || it.level == MasteryLevel.LEARNING }
                .ifEmpty { mastery.take(3) }

            for (pm in candidatePatterns) {
                val patternName = pm.pattern.name
                val found = exerciseRepository.findForMistakeRepetition(
                    userId = userId,
                    pattern = patternName,
                    difficulty = targetDifficulty,
                    excludeId = excludeId,
                    limit = 1
                ).firstOrNull() ?: exerciseRepository.findSimilarByPattern(
                    userId = userId,
                    pattern = patternName,
                    excludeId = excludeId,
                    minDifficulty = 0,
                    maxDifficulty = 100,
                    limit = 1
                ).firstOrNull()

                if (found != null) {
                    return Result.Success(found)
                }
            }
        }

        // 3. Если статистика пока пуста — переходим к стандартному адаптивному подбору
        return selectAdaptiveExercise(user, userId, excludeId, preferredPattern = null)
    }

    private suspend fun selectThemeExercise(
        user: User,
        userId: Long,
        theme: TacticalTheme,
        excludeId: Long
    ): Result {
        val exercise = exerciseRepository.findExerciseByTheme(
            userId = userId,
            theme = theme,
            userRating = user.rating,
            excludeId = excludeId
        )
        return if (exercise != null) {
            Result.Success(exercise)
        } else {
            Result.NoSuitableExercises
        }
    }

    private suspend fun selectAdaptiveExercise(
        user: User,
        userId: Long,
        excludeId: Long,
        preferredPattern: String?
    ): Result {
        val targetDifficulty = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Целевое закрепление паттерна сразу после допущенной ошибки
        if (!preferredPattern.isNullOrBlank()) {
            val patternExercise = exerciseRepository.findForMistakeRepetition(
                userId = userId,
                pattern = preferredPattern,
                difficulty = targetDifficulty,
                excludeId = excludeId,
                limit = 1
            ).firstOrNull()

            if (patternExercise != null) {
                return Result.Success(patternExercise)
            }
        }

        // 2. Интервальное повторение: возвращаем ранее проваленные и не решённые задачи
        val failedUnresolved = exerciseRepository.findFailedUnresolvedExercise(userId, excludeId)
        if (failedUnresolved != null) {
            return Result.Success(failedUnresolved, isReviewOfFailed = true)
        }

        // 3. Новая нерешённая задача подходящей сложности (с приоритетом ошибок из партий пользователя)
        val suitable = exerciseRepository.findSuitableExercise(
            userId = userId,
            userRating = user.rating,
            excludeId = excludeId
        )
        if (suitable != null) {
            return Result.Success(suitable)
        }

        // 4. Повтор доступных задач с наименьшим числом решений
        val review = exerciseRepository.findAnyAccessibleExcept(userId, excludeId)
        if (review != null) {
            return Result.Success(review)
        }

        // 5. Крайний резерв — создать из необработанной ошибки
        val fallback = createFallbackExerciseFromMistake(user, userId)
        return if (fallback != null) {
            Result.Success(fallback)
        } else {
            Result.NoSuitableExercises
        }
    }

    private suspend fun createFallbackExerciseFromMistake(user: User, userId: Long): Exercise? {
        val candidateMistakes = mistakeRepository.findByUserId(userId).asReversed()
        for (mistake in candidateMistakes) {
            if (!GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(mistake, null)) {
                continue
            }
            val fen = mistake.fenBefore?.trim().takeIf { !it.isNullOrEmpty() } ?: continue
            if (exerciseRepository.findUserExerciseByFen(userId, fen) != null) continue
            val solutionUci = ChessMoveUtils.sanOrUciToUci(mistake.bestMove, fen) ?: continue

            val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)
            val newExercise = Exercise(
                fenPosition = fen,
                solutionMoves = listOf(solutionUci),
                tacticalPattern = TacticalPattern.MIXED,
                patternClusterId = "MIXED_MEDIUM",
                difficultyRating = targetDiff,
                patternFeatures = emptyList(),
                theme = TacticalTheme.TACTIC,
                rating = TrainingRatingCalculator.difficultyToElo(targetDiff),
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = mistake.gameId,
                sourceMoveNumber = mistake.moveNumber
            )
            return exerciseRepository.saveExercise(newExercise)
        }
        return null
    }
}
