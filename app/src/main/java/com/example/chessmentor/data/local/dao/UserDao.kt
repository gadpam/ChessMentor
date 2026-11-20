package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.User

@Dao
interface UserDao {
    @Query("SELECT EXISTS(SELECT 1 FROM users)")
    suspend fun hasUsers(): Boolean

    @Query("SELECT * FROM users WHERE passwordHash = :marker LIMIT 1")
    suspend fun findPasswordlessProfile(marker: String): User?

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findById(id: Long): User?

    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun findByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE nickname = :nickname COLLATE NOCASE LIMIT 1")
    suspend fun findByNickname(nickname: String): User?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: User): Long

    @Update
    suspend fun update(user: User)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE email = :email COLLATE NOCASE)")
    suspend fun existsByEmail(email: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE nickname = :nickname COLLATE NOCASE)")
    suspend fun existsByNickname(nickname: String): Boolean
}