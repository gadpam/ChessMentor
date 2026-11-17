package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.MistakeDao
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.repository.MistakeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomMistakeRepository(private val mistakeDao: MistakeDao) : MistakeRepository {
    override suspend fun findById(id: Long): Mistake? = withContext(Dispatchers.IO) {
        mistakeDao.findById(id)
    }

    override suspend fun findByGameId(gameId: Long): List<Mistake> = withContext(Dispatchers.IO) {
        mistakeDao.findByGameId(gameId)
    }

    override suspend fun findByUserId(userId: Long): List<Mistake> = withContext(Dispatchers.IO) {
        mistakeDao.findByUserId(userId)
    }

    override suspend fun findByGameIds(gameIds: List<Long>): List<Mistake> = withContext(Dispatchers.IO) {
        if (gameIds.isEmpty()) return@withContext emptyList()
        mistakeDao.findByGameIds(gameIds)
    }

    override suspend fun save(mistake: Mistake): Mistake = withContext(Dispatchers.IO) {
        val id = mistakeDao.insert(mistake)
        mistake.copy(id = id)
    }

    override suspend fun saveAll(mistakes: List<Mistake>): List<Mistake> = withContext(Dispatchers.IO) {
        if (mistakes.isEmpty()) return@withContext emptyList()
        val ids = mistakeDao.insertAll(mistakes)
        mistakes.mapIndexed { index, mistake -> mistake.copy(id = ids[index]) }
    }

    override suspend fun update(mistake: Mistake): Mistake = withContext(Dispatchers.IO) {
        mistakeDao.update(mistake)
        mistake
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        mistakeDao.deleteById(id)
    }

    override suspend fun deleteByGameId(gameId: Long) = withContext(Dispatchers.IO) {
        mistakeDao.deleteByGameId(gameId)
    }

    override suspend fun findByThemeId(themeId: Long): List<Mistake> = withContext(Dispatchers.IO) {
        mistakeDao.findByThemeId(themeId)
    }

    override suspend fun findByGameIdAndType(gameId: Long, type: MistakeType): List<Mistake> =
        withContext(Dispatchers.IO) {
            mistakeDao.findByGameIdAndType(gameId, type)
        }

    override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<Mistake> =
        withContext(Dispatchers.IO) {
            mistakeDao.findByUserIdAndTheme(userId, themeId)
        }

    override suspend fun countByGameId(gameId: Long): Int = withContext(Dispatchers.IO) {
        findByGameId(gameId).size
    }

    override suspend fun countByUserIdAndType(userId: Long, type: MistakeType): Int =
        withContext(Dispatchers.IO) {
            mistakeDao.countByUserIdAndType(userId, type)
        }

    override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): Map<Long, Int> =
        withContext(Dispatchers.IO) {
            mistakeDao.getMostFrequentThemesByUserId(userId, limit)
                .associate { it.themeId to it.count }
        }

    override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? =
        withContext(Dispatchers.IO) {
            mistakeDao.getAverageEvaluationLossByUserId(userId)
        }
}
