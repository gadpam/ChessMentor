package com.example.chessmentor.data.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class StockfishEngine(
    context: Context
) : ChessEngine {

    private val mutex = Mutex()
    private var isInitialized = false

    companion object {
        init {
            System.loadLibrary("stockfish-lib")
        }
    }

    override suspend fun init(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            isInitialized = nativeInit()
            isInitialized
        }
    }

    override suspend fun evaluate(fen: String, depthLimit: Int): Int = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureInitialized()
            nativeEvaluate(fen, depthLimit)
        }
    }

    override suspend fun getBestMove(fen: String, depthLimit: Int): String? = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureInitialized()
            nativeGetBestMove(fen, depthLimit)?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureInitialized()
            nativeGetBestMoveWithLine(fen, depthLimit)?.let(::decodeAnalysisLine)
        }
    }

    override fun destroy() {
        isInitialized = false
        nativeDestroy()
    }

    override suspend fun setOption(name: String, value: String) = withContext(Dispatchers.Default) {
        nativeSetOption(name, value)
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            isInitialized = nativeInit()
        }
    }

    private fun decodeAnalysisLine(encoded: String): AnalysisLine? {
        val parts = encoded.split('\t')
        if (parts.size < 3) return null

        val bestMove = parts[0]
        val score = parts[1].toIntOrNull() ?: 0
        val pv = parts[2]
            .split(' ')
            .filter { it.isNotBlank() }

        return AnalysisLine(
            bestMove = bestMove,
            score = score,
            mateIn = null,
            principalVariation = if (pv.isEmpty()) listOf(bestMove) else pv
        )
    }

    private external fun nativeInit(): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetOption(name: String, value: String)
    private external fun nativeEvaluate(fen: String, depth: Int): Int
    private external fun nativeGetBestMove(fen: String, depth: Int): String?
    private external fun nativeGetBestMoveWithLine(fen: String, depth: Int): String?
}
