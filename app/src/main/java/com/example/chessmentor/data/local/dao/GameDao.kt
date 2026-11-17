package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.AnalysisStatus

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun findById(id: Long): Game?

    @Query("SELECT * FROM games WHERE userId = :userId ORDER BY playedAt DESC")
    suspend fun findByUserId(userId: Long): List<Game>

    @Query("SELECT * FROM games WHERE userId = :userId AND analysisStatus = :status")
    suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(game: Game): Long

    @Update
    suspend fun update(game: Game)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)
}