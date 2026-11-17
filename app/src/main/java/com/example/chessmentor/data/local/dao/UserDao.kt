package com.example.chessmentor.data.local.dao

import androidx.room.*
import com.example.chessmentor.domain.entity.User

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findById(id: Long): User?

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun findByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE nickname = :nickname")
    suspend fun findByNickname(nickname: String): User?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: User): Long

    @Update
    suspend fun update(user: User)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT EXISTS(SELECT * FROM users WHERE email = :email)")
    suspend fun existsByEmail(email: String): Boolean

    @Query("SELECT EXISTS(SELECT * FROM users WHERE nickname = :nickname)")
    suspend fun existsByNickname(nickname: String): Boolean
}