// data/repository/room/RoomExerciseRepository.kt
package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.ExerciseDao
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.repository.ExerciseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomExerciseRepository(private val exerciseDao: ExerciseDao) : ExerciseRepository {

    override suspend fun findById(id: Long): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findById(id)
    }

    override suspend fun findSuitableExercise(
        userId: Long,
        userRating: Int,
        excludeId: Long
    ): Exercise? = withContext(Dispatchers.IO) {
        // Переводим рейтинг Эло игрока (например, 1200) в шкалу difficultyRating (0..100)
        val targetDifficulty = ((userRating - 600) / 18).coerceIn(10, 95)
        val range = 30
        exerciseDao.findUnsolvedExercise(
            userId = userId,
            minDifficulty = (targetDifficulty - range).coerceAtLeast(0),
            maxDifficulty = (targetDifficulty + range).coerceAtMost(100),
            excludeId = excludeId
        ) ?: exerciseDao.findAnyUnsolvedExercise(
            userId = userId,
            excludeId = excludeId
        )
    }

    override suspend fun findByTheme(theme: String): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findByTheme(theme)
    }

    override suspend fun findByPattern(pattern: String): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findByPattern(pattern)
    }

    override suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise? =
        withContext(Dispatchers.IO) {
            exerciseDao.findBySourceGameAndMove(gameId, moveNumber)
        }

    override suspend fun findByUserId(userId: Long): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findByUserId(userId)
    }

    override suspend fun findAnyExerciseExcept(excludeId: Long): Exercise? =
        withContext(Dispatchers.IO) {
            exerciseDao.findAnyExerciseExcept(excludeId)
        }

    override suspend fun findByFen(fen: String): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findByFen(fen)
    }

    override suspend fun deleteDuplicates() = withContext(Dispatchers.IO) {
        exerciseDao.deleteDuplicates()
    }

    override suspend fun countExercises(): Int = withContext(Dispatchers.IO) {
        exerciseDao.countExercises()
    }

    override suspend fun saveExercise(exercise: Exercise): Exercise = withContext(Dispatchers.IO) {
        val id = exerciseDao.insertExercise(exercise)
        exercise.copy(id = id)
    }

    override suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt =
        withContext(Dispatchers.IO) {
            val id = exerciseDao.insertAttempt(attempt)
            attempt.copy(id = id)
        }

    override suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt> =
        withContext(Dispatchers.IO) {
            exerciseDao.getAttemptsByUserId(userId)
        }

    override suspend fun findByCluster(
        clusterId: String,
        excludeId: Long,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findByCluster(clusterId, excludeId, limit)
    }

    override suspend fun findSimilarByPattern(
        pattern: String,
        excludeId: Long,
        minDifficulty: Int,
        maxDifficulty: Int,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findSimilarByPattern(pattern, excludeId, minDifficulty, maxDifficulty, limit)
    }

    override suspend fun findForMistakeRepetition(
        userId: Long,
        pattern: String,
        difficulty: Int,
        excludeId: Long,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findForMistakeRepetition(userId, pattern, difficulty, excludeId, limit)
    }

    override suspend fun incrementTimesShown(exerciseId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.incrementTimesShown(exerciseId)
    }

    override suspend fun incrementTimesSolved(exerciseId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.incrementTimesSolved(exerciseId)
    }
}
