package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.GameDao
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.repository.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomGameRepository(private val gameDao: GameDao) : GameRepository {
    override suspend fun findById(id: Long): Game? = withContext(Dispatchers.IO) {
        gameDao.findById(id)
    }

    override suspend fun findByUserId(userId: Long): List<Game> = withContext(Dispatchers.IO) {
        gameDao.findByUserId(userId)
    }

    override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> = withContext(Dispatchers.IO) {
        gameDao.findByUserIdAndStatus(userId, status)
    }

    override suspend fun save(game: Game): Game = withContext(Dispatchers.IO) {
        val id = gameDao.insert(game)
        game.copy(id = id)
    }

    override suspend fun update(game: Game): Game = withContext(Dispatchers.IO) {
        gameDao.update(game)
        game
    }

    override suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        gameDao.deleteById(id)
    }

    // Дополнительные методы (не в интерфейсе, но нужны для приложения)
    suspend fun findLatestByUserId(userId: Long, limit: Int): List<Game> = withContext(Dispatchers.IO) {
        findByUserId(userId).take(limit)
    }

    suspend fun findPendingGames(): List<Game> = withContext(Dispatchers.IO) {
        // Заглушка возвращала пустой список, из-за чего вызывающий код считал,
        // что неразобранных партий нет. Явный отказ безопаснее ложного ответа.
        throw NotImplementedError("RoomGameRepository.findPendingGames не реализован")
    }

    suspend fun deleteByUserId(userId: Long): Unit = withContext(Dispatchers.IO) {
        // Раньше метод молча ничего не делал: «удаление» профиля оставляло бы партии.
        throw NotImplementedError("RoomGameRepository.deleteByUserId не реализован")
    }

    suspend fun countByUserId(userId: Long): Int = withContext(Dispatchers.IO) {
        findByUserId(userId).size
    }

    suspend fun countAnalyzedByUserId(userId: Long): Int = withContext(Dispatchers.IO) {
        findByUserId(userId).count { it.isAnalyzed() }
    }

    suspend fun getAverageAccuracyByUserId(userId: Long): Double? = withContext(Dispatchers.IO) {
        findByUserId(userId).mapNotNull { it.accuracy }.average().takeIf { it.isNaN().not() }
    }
}