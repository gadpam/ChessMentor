package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType

@Dao
interface MistakeDao {
    @Query("SELECT * FROM mistakes WHERE id = :id")
    suspend fun findById(id: Long): Mistake?

    @Query("SELECT * FROM mistakes WHERE gameId = :gameId ORDER BY moveNumber ASC")
    suspend fun findByGameId(gameId: Long): List<Mistake>

    @Query("SELECT * FROM mistakes WHERE gameId IN (:gameIds) ORDER BY gameId, moveNumber ASC")
    suspend fun findByGameIds(gameIds: List<Long>): List<Mistake>

    @Query("SELECT * FROM mistakes WHERE themeId = :themeId ORDER BY id DESC")
    suspend fun findByThemeId(themeId: Long): List<Mistake>

    @Query("SELECT * FROM mistakes WHERE gameId = :gameId AND mistakeType = :type ORDER BY moveNumber ASC")
    suspend fun findByGameIdAndType(gameId: Long, type: MistakeType): List<Mistake>

    @Query("""
        SELECT m.* FROM mistakes m
        INNER JOIN games g ON m.gameId = g.id
        WHERE g.userId = :userId AND m.themeId = :themeId
    """)
    suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<Mistake>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(mistake: Mistake): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(mistakes: List<Mistake>): List<Long>

    @Update
    suspend fun update(mistake: Mistake)

    @Query("DELETE FROM mistakes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM mistakes WHERE gameId = :gameId")
    suspend fun deleteByGameId(gameId: Long)

    @Query("SELECT m.* FROM mistakes m INNER JOIN games g ON m.gameId = g.id WHERE g.userId = :userId")
    suspend fun findByUserId(userId: Long): List<Mistake>

    @Query("""
        SELECT COUNT(*) FROM mistakes m
        INNER JOIN games g ON m.gameId = g.id
        WHERE g.userId = :userId AND m.mistakeType = :type
    """)
    suspend fun countByUserIdAndType(userId: Long, type: MistakeType): Int

    @Query("""
        SELECT m.themeId AS themeId, COUNT(*) AS count
        FROM mistakes m
        INNER JOIN games g ON m.gameId = g.id
        WHERE g.userId = :userId
        GROUP BY m.themeId
        ORDER BY count DESC
        LIMIT :limit
    """)
    suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): List<ThemeCount>

    @Query("""
        SELECT AVG(CASE WHEN m.evaluationLoss > 1000 THEN 1000 ELSE m.evaluationLoss END)
        FROM mistakes m
        INNER JOIN games g ON m.gameId = g.id
        WHERE g.userId = :userId
    """)
    suspend fun getAverageEvaluationLossByUserId(userId: Long): Double?
}

data class ThemeCount(
    val themeId: Long,
    val count: Int
)
