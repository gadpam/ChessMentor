// domain/repository/ExerciseRepository.kt
package com.example.chessmentor.domain.repository

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalTheme

interface ExerciseRepository {

    // ============================================================
    // БАЗОВЫЕ МЕТОДЫ (С ИЗОЛЯЦИЕЙ ПО ПОЛЬЗОВАТЕЛЮ)
    // ============================================================

    suspend fun findById(id: Long): Exercise?

    suspend fun findByIds(ids: List<Long>): List<Exercise>

    suspend fun findSuitableExercise(
        userId: Long,
        userRating: Int,
        excludeId: Long = -1
    ): Exercise?

    suspend fun findFailedUnresolvedExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    suspend fun findUserMistakeExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    suspend fun findExerciseByTheme(
        userId: Long,
        theme: TacticalTheme,
        userRating: Int,
        excludeId: Long = -1
    ): Exercise?

    suspend fun findByTheme(theme: String): List<Exercise>

    suspend fun findByPattern(pattern: String): List<Exercise>

    suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise?

    /** Только упражнения, созданные из партий данного пользователя. */
    suspend fun findByUserId(userId: Long): List<Exercise>

    /** Все упражнения, доступные пользователю (общие + из его партий). */
    suspend fun findAccessibleByUserId(userId: Long): List<Exercise>

    /** Любая доступная пользователю задача, кроме [excludeId]. */
    suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long = -1): Exercise?

    suspend fun findGlobalByFen(fen: String): Exercise?

    suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise?

    suspend fun findByFen(fen: String): Exercise?

    suspend fun deleteDuplicatesForUser(userId: Long)

    suspend fun deleteGlobalDuplicates()

    suspend fun countExercises(): Int

    suspend fun countGlobalExercises(): Int

    suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean

    suspend fun saveExercise(exercise: Exercise): Exercise

    suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt

    suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt>

    // ============================================================
    // КЛАСТЕРИЗАЦИЯ И ПОВТОРЕНИЕ ОШИБОК
    // ============================================================

    suspend fun findByCluster(
        userId: Long,
        clusterId: String,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    suspend fun findSimilarByPattern(
        userId: Long,
        pattern: String,
        excludeId: Long = -1,
        minDifficulty: Int = 0,
        maxDifficulty: Int = 100,
        limit: Int = 3
    ): List<Exercise>

    suspend fun findForMistakeRepetition(
        userId: Long,
        pattern: String,
        difficulty: Int,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    suspend fun incrementTimesShown(exerciseId: Long)

    suspend fun incrementTimesSolved(exerciseId: Long)
}
