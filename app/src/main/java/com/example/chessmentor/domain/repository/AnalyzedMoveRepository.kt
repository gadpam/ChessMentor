// domain/repository/AnalyzedMoveRepository.kt
package com.example.chessmentor.domain.repository

import com.example.chessmentor.domain.entity.AnalyzedMove

interface AnalyzedMoveRepository {
    suspend fun saveAll(moves: List<AnalyzedMove>): List<AnalyzedMove>
    suspend fun findByGameId(gameId: Long): List<AnalyzedMove>
    suspend fun findByGameIds(gameIds: List<Long>): List<AnalyzedMove> =
        gameIds.distinct().flatMap { findByGameId(it) }
    suspend fun findMistakesByGameId(gameId: Long): List<AnalyzedMove>
    suspend fun findGoodMovesByGameId(gameId: Long): List<AnalyzedMove>
    suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove?
    suspend fun deleteByGameId(gameId: Long)
}