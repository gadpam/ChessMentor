// data/local/dao/ExerciseDao.kt
package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt

@Dao
interface ExerciseDao {

    // ============================================================
    // БАЗОВЫЕ ЗАПРОСЫ
    // ============================================================

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun findById(id: Long): Exercise?

    @Query("SELECT * FROM exercises WHERE tacticalPattern = :pattern")
    suspend fun findByPattern(pattern: String): List<Exercise>

    @Query("SELECT * FROM exercises WHERE theme = :theme")
    suspend fun findByTheme(theme: String): List<Exercise>

    @Query("SELECT * FROM exercises WHERE sourceGameId = :gameId AND sourceMoveNumber = :moveNumber LIMIT 1")
    suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise?

    @Query("""
        SELECT e.* FROM exercises e 
        INNER JOIN games g ON e.sourceGameId = g.id 
        WHERE g.userId = :userId
    """)
    suspend fun findByUserId(userId: Long): List<Exercise>

    @Query("SELECT * FROM exercises WHERE fenPosition = :fen LIMIT 1")
    suspend fun findByFen(fen: String): Exercise?

    @Query("SELECT * FROM exercises")
    suspend fun getAllExercises(): List<Exercise>

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun countExercises(): Int

    // ============================================================
    // ПОИСК УПРАЖНЕНИЙ ДЛЯ ТРЕНИРОВКИ
    // ============================================================

    /**
     * Поиск нерешённого упражнения с учётом сложности (шкала 0..100)
     */
    @Query("""
        SELECT e.* FROM exercises e 
        WHERE e.id != :excludeId 
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts 
            WHERE userId = :userId AND solved = 1
        ) 
        AND e.difficultyRating BETWEEN :minDifficulty AND :maxDifficulty 
        ORDER BY RANDOM() 
        LIMIT 1
    """)
    suspend fun findUnsolvedExercise(
        userId: Long,
        minDifficulty: Int,
        maxDifficulty: Int,
        excludeId: Long = -1
    ): Exercise?

    /**
     * Поиск любого нерешённого упражнения (если в целевом диапазоне сложности задач нет)
     */
    @Query("""
        SELECT e.* FROM exercises e 
        WHERE e.id != :excludeId 
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts 
            WHERE userId = :userId AND solved = 1
        ) 
        ORDER BY RANDOM() 
        LIMIT 1
    """)
    suspend fun findAnyUnsolvedExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    /**
     * Получить любое упражнение кроме указанного
     */
    @Query("SELECT * FROM exercises WHERE id != :excludeId ORDER BY RANDOM() LIMIT 1")
    suspend fun findAnyExerciseExcept(excludeId: Long): Exercise?

    // ============================================================
    // КЛАСТЕРИЗАЦИЯ
    // ============================================================

    @Query("""
        SELECT * FROM exercises 
        WHERE patternClusterId = :clusterId 
        AND id != :excludeId 
        ORDER BY RANDOM() 
        LIMIT :limit
    """)
    suspend fun findByCluster(
        clusterId: String,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    @Query("""
        SELECT * FROM exercises 
        WHERE tacticalPattern = :pattern 
        AND id != :excludeId 
        AND difficultyRating BETWEEN :minDifficulty AND :maxDifficulty
        ORDER BY RANDOM() 
        LIMIT :limit
    """)
    suspend fun findSimilarByPattern(
        pattern: String,
        excludeId: Long = -1,
        minDifficulty: Int = 0,
        maxDifficulty: Int = 100,
        limit: Int = 3
    ): List<Exercise>

    @Query("""
        SELECT * FROM exercises 
        WHERE tacticalPattern = :pattern 
        AND id != :excludeId 
        AND id NOT IN (
            SELECT exerciseId FROM exercise_attempts 
            WHERE userId = :userId AND solved = 1
        )
        AND difficultyRating BETWEEN :difficulty - 20 AND :difficulty + 20
        ORDER BY RANDOM() 
        LIMIT :limit
    """)
    suspend fun findForMistakeRepetition(
        userId: Long,
        pattern: String,
        difficulty: Int,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    // ============================================================
    // СТАТИСТИКА
    // ============================================================

    @Query("SELECT COUNT(DISTINCT exerciseId) FROM exercise_attempts WHERE userId = :userId AND solved = 1")
    suspend fun countSolvedExercises(userId: Long): Int

    @Query("""
        SELECT tacticalPattern, COUNT(*) as count 
        FROM exercises 
        GROUP BY tacticalPattern 
        ORDER BY count DESC
    """)
    suspend fun getPatternDistribution(): List<PatternCount>

    // ============================================================
    // ВСТАВКА И ОБНОВЛЕНИЕ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExercise(exercise: Exercise): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: ExerciseAttempt): Long

    @Update
    suspend fun updateExercise(exercise: Exercise)

    @Query("SELECT * FROM exercise_attempts WHERE userId = :userId")
    suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt>

    @Query("DELETE FROM exercises WHERE sourceGameId = :gameId")
    suspend fun deleteBySourceGameId(gameId: Long)

    /**
     * Удалить дубликаты по FEN
     */
    @Query("""
        DELETE FROM exercises 
        WHERE id NOT IN (
            SELECT MIN(id) FROM exercises GROUP BY fenPosition
        )
    """)
    suspend fun deleteDuplicates()

    /**
     * Обновить статистику показа упражнения (с пересчётом successRate)
     */
    @Query("""
        UPDATE exercises 
        SET timesShown = timesShown + 1,
            successRate = (timesSolved * 100) / (timesShown + 1)
        WHERE id = :exerciseId
    """)
    suspend fun incrementTimesShown(exerciseId: Long)

    /**
     * Обновить статистику решения упражнения (timesShown уже был увеличен при показе)
     */
    @Query("""
        UPDATE exercises 
        SET timesSolved = timesSolved + 1,
            successRate = MIN(100, ((timesSolved + 1) * 100) / CASE WHEN timesShown > 0 THEN timesShown ELSE 1 END)
        WHERE id = :exerciseId
    """)
    suspend fun incrementTimesSolved(exerciseId: Long)
}

/**
 * Для запроса статистики паттернов
 */
data class PatternCount(
    val tacticalPattern: String,
    val count: Int
)
