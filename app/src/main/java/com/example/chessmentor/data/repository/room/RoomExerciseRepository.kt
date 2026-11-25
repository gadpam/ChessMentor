// data/repository/room/RoomExerciseRepository.kt
package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.ExerciseDao
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomExerciseRepository(private val exerciseDao: ExerciseDao) : ExerciseRepository {

    companion object {
        private const val DIFFICULTY_WINDOW = 25
        private const val REPETITION_WINDOW = 25
    }

    override suspend fun findById(id: Long): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findById(id)
    }

    override suspend fun findByIds(ids: List<Long>): List<Exercise> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyList()
        else ids.distinct().chunked(500).flatMap { chunk -> exerciseDao.findByIds(chunk) }
    }

    override suspend fun findSuitableExercise(
        userId: Long,
        userRating: Int,
        excludeId: Long
    ): Exercise? = withContext(Dispatchers.IO) {
        val targetDifficulty = TrainingRatingCalculator.eloToDifficulty(userRating)
        exerciseDao.findUnsolvedExercise(
            userId = userId,
            minDifficulty = (targetDifficulty - DIFFICULTY_WINDOW).coerceAtLeast(0),
            maxDifficulty = (targetDifficulty + DIFFICULTY_WINDOW).coerceAtMost(100),
            excludeId = excludeId
        ) ?: exerciseDao.findAnyUnsolvedExercise(
            userId = userId,
            excludeId = excludeId
        )
    }

    override suspend fun findFailedUnresolvedExercise(
        userId: Long,
        excludeId: Long
    ): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findFailedUnresolvedExercise(userId = userId, excludeId = excludeId)
    }

    override suspend fun findUserMistakeExercise(
        userId: Long,
        excludeId: Long
    ): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findFailedUnresolvedUserMistakeExercise(userId = userId, excludeId = excludeId)
            ?: exerciseDao.findUnsolvedUserMistakeExercise(userId = userId, excludeId = excludeId)
            ?: exerciseDao.findAnyUserMistakeExerciseExcept(userId = userId, excludeId = excludeId)
    }

    override suspend fun findExerciseByTheme(
        userId: Long,
        theme: TacticalTheme,
        userRating: Int,
        excludeId: Long
    ): Exercise? = withContext(Dispatchers.IO) {
        val targetDifficulty = TrainingRatingCalculator.eloToDifficulty(userRating)
        val themeName = theme.name
        exerciseDao.findUnsolvedByTheme(
            userId = userId,
            theme = themeName,
            minDifficulty = (targetDifficulty - DIFFICULTY_WINDOW).coerceAtLeast(0),
            maxDifficulty = (targetDifficulty + DIFFICULTY_WINDOW).coerceAtMost(100),
            excludeId = excludeId
        ) ?: exerciseDao.findAnyUnsolvedByTheme(
            userId = userId,
            theme = themeName,
            excludeId = excludeId
        ) ?: exerciseDao.findAnyByThemeForUser(
            userId = userId,
            theme = themeName,
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

    override suspend fun findAccessibleByUserId(userId: Long): List<Exercise> =
        withContext(Dispatchers.IO) {
            exerciseDao.findAccessibleByUserId(userId)
        }

    override suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise? =
        withContext(Dispatchers.IO) {
            exerciseDao.findAnyAccessibleExcept(userId = userId, excludeId = excludeId)
        }

    override suspend fun findGlobalByFen(fen: String): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findGlobalByFen(fen)
    }

    override suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise? =
        withContext(Dispatchers.IO) {
            exerciseDao.findUserExerciseByFen(userId = userId, fen = fen)
        }

    override suspend fun findByFen(fen: String): Exercise? = withContext(Dispatchers.IO) {
        exerciseDao.findByFen(fen)
    }

    override suspend fun deleteDuplicatesForUser(userId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.deleteDuplicatesForUser(userId)
    }

    override suspend fun deleteGlobalDuplicates() = withContext(Dispatchers.IO) {
        exerciseDao.deleteGlobalDuplicates()
    }

    override suspend fun deleteBySourceGameId(gameId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.deleteBySourceGameId(gameId)
    }

    override suspend fun countExercises(): Int = withContext(Dispatchers.IO) {
        exerciseDao.countExercises()
    }

    override suspend fun countGlobalExercises(): Int = withContext(Dispatchers.IO) {
        exerciseDao.countGlobalExercises()
    }

    override suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean =
        withContext(Dispatchers.IO) {
            exerciseDao.getSolvedAttemptsForExercise(userId = userId, exerciseId = exerciseId)
                .any(AttemptMetadata::isIndependentSolve)
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
        userId: Long,
        clusterId: String,
        excludeId: Long,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findByClusterForUser(
            userId = userId,
            clusterId = clusterId,
            excludeId = excludeId,
            limit = limit
        )
    }

    override suspend fun findSimilarByPattern(
        userId: Long,
        pattern: String,
        excludeId: Long,
        minDifficulty: Int,
        maxDifficulty: Int,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findSimilarByPatternForUser(
            userId = userId,
            pattern = pattern,
            excludeId = excludeId,
            minDifficulty = minDifficulty,
            maxDifficulty = maxDifficulty,
            limit = limit
        )
    }

    override suspend fun findForMistakeRepetition(
        userId: Long,
        pattern: String,
        difficulty: Int,
        excludeId: Long,
        limit: Int
    ): List<Exercise> = withContext(Dispatchers.IO) {
        exerciseDao.findForMistakeRepetition(
            userId = userId,
            pattern = pattern,
            minDifficulty = (difficulty - REPETITION_WINDOW).coerceAtLeast(0),
            maxDifficulty = (difficulty + REPETITION_WINDOW).coerceAtMost(100),
            excludeId = excludeId,
            limit = limit
        )
    }

    override suspend fun incrementTimesShown(exerciseId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.incrementTimesShown(exerciseId)
    }

    override suspend fun incrementTimesSolved(exerciseId: Long) = withContext(Dispatchers.IO) {
        exerciseDao.incrementTimesSolved(exerciseId)
    }
}
