package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.UserDao
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomUserRepository(private val userDao: UserDao) : UserRepository {
    override suspend fun findById(id: Long): User? = withContext(Dispatchers.IO) {
        userDao.findById(id)
    }

    override suspend fun findByEmail(email: String): User? = withContext(Dispatchers.IO) {
        userDao.findByEmail(email)
    }

    override suspend fun findByNickname(nickname: String): User? = withContext(Dispatchers.IO) {
        userDao.findByNickname(nickname)
    }

    override suspend fun save(user: User): User = withContext(Dispatchers.IO) {
        val id = userDao.insert(user)
        user.copy(id = id)
    }

    override suspend fun update(user: User): User = withContext(Dispatchers.IO) {
        userDao.update(user)
        user
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        userDao.deleteById(id)
    }

    override suspend fun existsByEmail(email: String): Boolean = withContext(Dispatchers.IO) {
        userDao.existsByEmail(email)
    }

    override suspend fun existsByNickname(nickname: String): Boolean = withContext(Dispatchers.IO) {
        userDao.existsByNickname(nickname)
    }

    override suspend fun findAll(): List<User> = withContext(Dispatchers.IO) {
        userDao.findAll()
    }

    override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
        withContext(Dispatchers.IO) {
            userDao.findBySkillLevel(skillLevel.name)
        }

    override suspend fun findTopByRating(limit: Int): List<User> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            emptyList()
        } else {
            userDao.findTopByRating(limit)
        }
    }
}