// data/local/dao/AnalyzedMoveDao.kt
package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.MoveQuality

@Dao
interface AnalyzedMoveDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(move: AnalyzedMove): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(moves: List<AnalyzedMove>): List<Long>

    @Query("SELECT * FROM analyzed_moves WHERE gameId = :gameId ORDER BY moveIndex ASC")
    suspend fun findByGameId(gameId: Long): List<AnalyzedMove>

    @Query("SELECT * FROM analyzed_moves WHERE gameId IN (:gameIds) ORDER BY gameId ASC, moveIndex ASC")
    suspend fun findByGameIds(gameIds: List<Long>): List<AnalyzedMove>

    @Query("SELECT * FROM analyzed_moves WHERE gameId = :gameId AND quality IN (:qualities) ORDER BY moveIndex ASC")
    suspend fun findByGameIdAndQualities(gameId: Long, qualities: List<MoveQuality>): List<AnalyzedMove>

    @Query("SELECT * FROM analyzed_moves WHERE gameId = :gameId AND moveIndex = :moveIndex LIMIT 1")
    suspend fun findByGameIdAndMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove?

    @Query("DELETE FROM analyzed_moves WHERE gameId = :gameId")
    suspend fun deleteByGameId(gameId: Long)

    @Query("SELECT COUNT(*) FROM analyzed_moves WHERE gameId = :gameId AND quality = :quality")
    suspend fun countByQuality(gameId: Long, quality: MoveQuality): Int
}