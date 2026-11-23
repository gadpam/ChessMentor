// data/repository/room/RoomAnalyzedMoveRepository.kt
package com.example.chessmentor.data.repository.room

import com.example.chessmentor.data.local.dao.AnalyzedMoveDao
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomAnalyzedMoveRepository(
    private val dao: AnalyzedMoveDao
) : AnalyzedMoveRepository {

    override suspend fun saveAll(moves: List<AnalyzedMove>): List<AnalyzedMove> = withContext(Dispatchers.IO) {
        if (moves.isEmpty()) return@withContext emptyList()

        val ids = dao.insertAll(moves)
        moves.mapIndexed { index, move ->
            move.copy(id = ids[index])
        }
    }

    override suspend fun findByGameId(gameId: Long): List<AnalyzedMove> = withContext(Dispatchers.IO) {
        dao.findByGameId(gameId)
    }

    override suspend fun findByGameIds(gameIds: List<Long>): List<AnalyzedMove> = withContext(Dispatchers.IO) {
        if (gameIds.isEmpty()) return@withContext emptyList()
        gameIds.distinct().chunked(500).flatMap { chunk ->
            dao.findByGameIds(chunk)
        }
    }

    override suspend fun findMistakesByGameId(gameId: Long): List<AnalyzedMove> = withContext(Dispatchers.IO) {
        dao.findByGameIdAndQualities(
            gameId,
            listOf(MoveQuality.BLUNDER, MoveQuality.MISTAKE, MoveQuality.INACCURACY)
        )
    }

    override suspend fun findGoodMovesByGameId(gameId: Long): List<AnalyzedMove> = withContext(Dispatchers.IO) {
        dao.findByGameIdAndQualities(
            gameId,
            listOf(
                MoveQuality.BRILLIANT,
                MoveQuality.GREAT_MOVE,
                MoveQuality.BEST_MOVE,
                MoveQuality.EXCELLENT,
                MoveQuality.GOOD
            )
        )
    }

    override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove? = withContext(Dispatchers.IO) {
        dao.findByGameIdAndMoveIndex(gameId, moveIndex)
    }

    override suspend fun deleteByGameId(gameId: Long) = withContext(Dispatchers.IO) {
        dao.deleteByGameId(gameId)
    }
}