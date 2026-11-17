// data/local/dao/ExerciseDao.kt
package com.example.chessmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt

@Dao
interface ExerciseDao {

    // ============================================================
    // БАЗОВЫЕ ЗАПРОСЫ (С ИЗОЛЯЦИЕЙ ПО ПОЛЬЗОВАТЕЛЮ)
    // ============================================================

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun findById(id: Long): Exercise?

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<Exercise>

    @Query("SELECT * FROM exercises WHERE tacticalPattern = :pattern AND sourceGameId IS NULL")
    suspend fun findByPattern(pattern: String): List<Exercise>

    @Query("SELECT * FROM exercises WHERE theme = :theme AND sourceGameId IS NULL")
    suspend fun findByTheme(theme: String): List<Exercise>

    @Query("SELECT * FROM exercises WHERE sourceGameId = :gameId AND sourceMoveNumber = :moveNumber LIMIT 1")
    suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise?

    /**
     * Только персональные упражнения пользователя (из его собственных партий).
     */
    @Query("""
        SELECT e.* FROM exercises e
        INNER JOIN games g ON e.sourceGameId = g.id
        WHERE g.userId = :userId
    """)
    suspend fun findByUserId(userId: Long): List<Exercise>

    /**
     * Все упражнения, доступные пользователю: общие (`sourceGameId IS NULL`) + из его партий.
     */
    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.sourceGameId IS NULL
        OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
    """)
    suspend fun findAccessibleByUserId(userId: Long): List<Exercise>

    @Query("SELECT * FROM exercises WHERE fenPosition = :fen AND sourceGameId IS NULL LIMIT 1")
    suspend fun findGlobalByFen(fen: String): Exercise?

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.fenPosition = :fen
        AND e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        LIMIT 1
    """)
    suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise?

    @Query("SELECT * FROM exercises WHERE fenPosition = :fen LIMIT 1")
    suspend fun findByFen(fen: String): Exercise?

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun countExercises(): Int

    @Query("SELECT COUNT(*) FROM exercises WHERE sourceGameId IS NULL")
    suspend fun countGlobalExercises(): Int

    // ============================================================
    // ПОДБОР ЗАДАЧ: ОБЩИЙ РЕЖИМ (С ИЗОЛЯЦИЕЙ И ПОВТОРЕНИЕМ ОШИБОК)
    // ============================================================

    /**
     * Интервальное повторение: задача, в которой пользователь ранее ошибся и так и не решил,
     * начиная с наиболее давней попытки.
     */
    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 0
        )
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        ORDER BY (
            SELECT MAX(attemptedAt) FROM exercise_attempts
            WHERE userId = :userId AND exerciseId = e.id
        ) ASC
        LIMIT 1
    """)
    suspend fun findFailedUnresolvedExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    /**
     * Поиск нерешённого упражнения в целевом диапазоне сложности (шкала 0..100).
     * Приоритет отдаётся задачам из собственных партий пользователя.
     */
    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        AND e.difficultyRating BETWEEN :minDifficulty AND :maxDifficulty
        ORDER BY CASE WHEN e.sourceGameId IS NOT NULL THEN 0 ELSE 1 END, RANDOM()
        LIMIT 1
    """)
    suspend fun findUnsolvedExercise(
        userId: Long,
        minDifficulty: Int,
        maxDifficulty: Int,
        excludeId: Long = -1
    ): Exercise?

    /**
     * Поиск любого нерешённого упражнения пользователя (без ограничения по сложности).
     */
    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        ORDER BY CASE WHEN e.sourceGameId IS NOT NULL THEN 0 ELSE 1 END, RANDOM()
        LIMIT 1
    """)
    suspend fun findAnyUnsolvedExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    /**
     * Повтор любой доступной пользователю задачи (когда все уже решены).
     */
    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        ORDER BY e.timesSolved ASC, RANDOM()
        LIMIT 1
    """)
    suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise?

    // ============================================================
    // ПОДБОР ЗАДАЧ: ТОЛЬКО ИЗ СОБСТВЕННЫХ ОШИБОК (MY_MISTAKES)
    // ============================================================

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        AND e.id IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 0
        )
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        ORDER BY (
            SELECT MAX(attemptedAt) FROM exercise_attempts
            WHERE userId = :userId AND exerciseId = e.id
        ) ASC
        LIMIT 1
    """)
    suspend fun findFailedUnresolvedUserMistakeExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        ORDER BY RANDOM()
        LIMIT 1
    """)
    suspend fun findUnsolvedUserMistakeExercise(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.id != :excludeId
        AND e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        ORDER BY e.timesSolved ASC, RANDOM()
        LIMIT 1
    """)
    suspend fun findAnyUserMistakeExerciseExcept(
        userId: Long,
        excludeId: Long = -1
    ): Exercise?

    // ============================================================
    // ПОДБОР ЗАДАЧ: ПО ТЕМАМ (BY_THEME)
    // ============================================================

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.theme = :theme
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        AND e.difficultyRating BETWEEN :minDifficulty AND :maxDifficulty
        ORDER BY RANDOM()
        LIMIT 1
    """)
    suspend fun findUnsolvedByTheme(
        userId: Long,
        theme: String,
        minDifficulty: Int,
        maxDifficulty: Int,
        excludeId: Long = -1
    ): Exercise?

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.theme = :theme
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        ORDER BY RANDOM()
        LIMIT 1
    """)
    suspend fun findAnyUnsolvedByTheme(
        userId: Long,
        theme: String,
        excludeId: Long = -1
    ): Exercise?

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.theme = :theme
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        ORDER BY e.timesSolved ASC, RANDOM()
        LIMIT 1
    """)
    suspend fun findAnyByThemeForUser(
        userId: Long,
        theme: String,
        excludeId: Long = -1
    ): Exercise?

    // ============================================================
    // КЛАСТЕРИЗАЦИЯ И ПОВТОРЕНИЕ ПАТТЕРНОВ (С ИЗОЛЯЦИЕЙ)
    // ============================================================

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.patternClusterId = :clusterId
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    suspend fun findByClusterForUser(
        userId: Long,
        clusterId: String,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.tacticalPattern = :pattern
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.difficultyRating BETWEEN :minDifficulty AND :maxDifficulty
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    suspend fun findSimilarByPatternForUser(
        userId: Long,
        pattern: String,
        excludeId: Long = -1,
        minDifficulty: Int = 0,
        maxDifficulty: Int = 100,
        limit: Int = 3
    ): List<Exercise>

    @Query("""
        SELECT e.* FROM exercises e
        WHERE e.tacticalPattern = :pattern
        AND e.id != :excludeId
        AND (e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId))
        AND e.id NOT IN (
            SELECT exerciseId FROM exercise_attempts
            WHERE userId = :userId AND solved = 1
        )
        AND e.difficultyRating BETWEEN :minDifficulty AND :maxDifficulty
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    suspend fun findForMistakeRepetition(
        userId: Long,
        pattern: String,
        minDifficulty: Int,
        maxDifficulty: Int,
        excludeId: Long = -1,
        limit: Int = 3
    ): List<Exercise>

    // ============================================================
    // СТАТИСТИКА
    // ============================================================

    @Query("SELECT COUNT(DISTINCT exerciseId) FROM exercise_attempts WHERE userId = :userId AND solved = 1")
    suspend fun countSolvedExercises(userId: Long): Int

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM exercise_attempts
            WHERE userId = :userId AND exerciseId = :exerciseId AND solved = 1
        )
    """)
    suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean

    @Query("""
        SELECT e.tacticalPattern AS tacticalPattern, COUNT(*) AS count
        FROM exercises e
        WHERE e.sourceGameId IS NULL OR e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        GROUP BY e.tacticalPattern
        ORDER BY count DESC
    """)
    suspend fun getPatternDistributionForUser(userId: Long): List<PatternCount>

    // ============================================================
    // ВСТАВКА, УДАЛЕНИЕ И ОБНОВЛЕНИЕ
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExercise(exercise: Exercise): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: ExerciseAttempt): Long

    @Update
    suspend fun updateExercise(exercise: Exercise)

    @Query("SELECT * FROM exercise_attempts WHERE userId = :userId ORDER BY attemptedAt ASC")
    suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt>

    @Query("DELETE FROM exercises WHERE sourceGameId = :gameId")
    suspend fun deleteBySourceGameId(gameId: Long)

    /**
     * Удалить дубликаты только среди общих задач (`sourceGameId IS NULL`).
     */
    @Query("""
        DELETE FROM exercises
        WHERE sourceGameId IS NULL
        AND id NOT IN (
            SELECT MIN(id) FROM exercises
            WHERE sourceGameId IS NULL
            GROUP BY fenPosition
        )
    """)
    suspend fun deleteGlobalDuplicates()

    /**
     * Удалить дубликаты только среди персональных задач конкретного пользователя,
     * не затрагивая задачи других пользователей и общие задачи.
     */
    @Query("""
        DELETE FROM exercises
        WHERE sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
        AND id NOT IN (
            SELECT MIN(e.id) FROM exercises e
            WHERE e.sourceGameId IN (SELECT id FROM games WHERE userId = :userId)
            GROUP BY e.fenPosition
        )
    """)
    suspend fun deleteDuplicatesForUser(userId: Long)

    /**
     * Обновить статистику показа упражнения (с пересчётом successRate).
     */
    @Query("""
        UPDATE exercises
        SET timesShown = timesShown + 1,
            successRate = (timesSolved * 100) / (timesShown + 1)
        WHERE id = :exerciseId
    """)
    suspend fun incrementTimesShown(exerciseId: Long)

    /**
     * Обновить статистику решения упражнения (timesShown уже был увеличен при показе).
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
 * Для запроса статистики паттернов.
 */
data class PatternCount(
    val tacticalPattern: String,
    val count: Int
)
