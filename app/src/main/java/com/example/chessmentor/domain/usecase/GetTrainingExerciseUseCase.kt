// domain/usecase/GetTrainingExerciseUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.util.ChessMoveUtils

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
        val preferredPattern: String? = null
    )

    sealed class Result {
        data class Success(val exercise: Exercise) : Result()
        data class Error(val message: String) : Result()
        object NoSuitableExercises : Result()
    }

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val userId = user.id ?: return Result.Error("Некорректный идентификатор пользователя")
        Log.d(TAG, "Looking for exercise for user $userId, excluding ${input.excludeExerciseId}")

        // 1. Если есть предпочтительный паттерн (после ошибки) — ищем нерешённую задачу того же типа
        if (input.preferredPattern != null) {
            val targetDifficulty = ratingToDifficulty(user.rating)
            val patternExercise = exerciseRepository.findForMistakeRepetition(
                userId = userId,
                pattern = input.preferredPattern,
                difficulty = targetDifficulty,
                excludeId = input.excludeExerciseId,
                limit = 1
            ).firstOrNull() ?: exerciseRepository.findSimilarByPattern(
                pattern = input.preferredPattern,
                excludeId = input.excludeExerciseId,
                minDifficulty = 0,
                maxDifficulty = 100,
                limit = 1
            ).firstOrNull()

            if (patternExercise != null) {
                Log.d(TAG, "Found exercise with preferred pattern: ${patternExercise.id}")
                return Result.Success(patternExercise)
            }
        }

        // 2. Найти нерешённое упражнение, подходящее по сложности
        var exercise = exerciseRepository.findSuitableExercise(
            userId = userId,
            userRating = user.rating,
            excludeId = input.excludeExerciseId
        )

        if (exercise != null) {
            Log.d(TAG, "Found unsolved exercise: ${exercise.id}")
            return Result.Success(exercise)
        }

        // 3. Если все решены — вернуть другое упражнение для повторения
        exercise = exerciseRepository.findAnyExerciseExcept(input.excludeExerciseId)
        if (exercise != null) {
            Log.d(TAG, "All solved, returning random exercise: ${exercise.id}")
            return Result.Success(exercise)
        }

        // 4. Создать новое упражнение из последней ошибки (с конвертацией bestMove в UCI)
        val candidateMistakes = mistakeRepository.findByUserId(userId).asReversed()
        for (mistake in candidateMistakes) {
            val fen = mistake.fenBefore?.takeIf { it.isNotBlank() } ?: continue
            val solutionUci = ChessMoveUtils.sanOrUciToUci(mistake.bestMove, fen) ?: continue

            Log.d(TAG, "Creating new exercise from mistake ${mistake.id}")
            val newExercise = Exercise(
                fenPosition = fen,
                solutionMoves = listOf(solutionUci),
                tacticalPattern = TacticalPattern.MIXED,
                patternClusterId = "MIXED_MEDIUM",
                difficultyRating = 50,
                patternFeatures = emptyList(),
                theme = TacticalTheme.TACTIC,
                rating = user.rating,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = mistake.gameId,
                sourceMoveNumber = mistake.moveNumber
            )

            val savedExercise = exerciseRepository.saveExercise(newExercise)
            return Result.Success(savedExercise)
        }

        Log.d(TAG, "No exercises available")
        return Result.NoSuitableExercises
    }

    private fun ratingToDifficulty(elo: Int): Int {
        return ((elo - 600) / 18).coerceIn(10, 95)
    }
}
