// data/engine/StockfishEngine.kt
package com.example.chessmentor.data.engine

import android.content.Context
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stockfish через JNI (нативная библиотека stockfish-lib, см. app/src/main/cpp).
 *
 * Все обращения к нативному коду сериализуются через [mutex] и внутренний мьютекс
 * StockfishBridge. При отмене корутины автоматически вызывается [nativeStop], чтобы
 * не блокировать движок до окончания глубокого поиска.
 */
class StockfishEngine(
    context: Context
) : ChessEngine {

    private val netDownloader = NnueDownloader(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var isInitialized = false

    companion object {
        private const val TAG = "StockfishEngine"

        /** Ошибка загрузки нативной библиотеки (например, если сборка NDK не выполнена). */
        private val libraryLoadError: Throwable? = runCatching {
            System.loadLibrary("stockfish-lib")
        }.exceptionOrNull()
    }

    override suspend fun init(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock { initLocked() }
    }

    override suspend fun evaluate(fen: String, depthLimit: Int): Int =
        runCancellableSearch {
            nativeEvaluate(fen, depthLimit)
        }

    override suspend fun getBestMove(fen: String, depthLimit: Int): String? =
        runCancellableSearch {
            nativeGetBestMove(fen, depthLimit)?.takeIf { it.isNotBlank() }
        }

    override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? =
        runCancellableSearch {
            nativeGetBestMoveWithLine(fen, depthLimit)?.let(::decodeAnalysisLine)
        }

    override suspend fun setOption(name: String, value: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureReadyLocked()
            nativeSetOption(name, value)
        }
    }

    override fun stop() {
        if (libraryLoadError != null) return
        nativeStop()
    }

    /**
     * Прерывает активный поиск и безопасно освобождает нативный движок.
     * Следующий вызов [init] создаст движок заново.
     */
    override fun destroy() {
        if (libraryLoadError != null) return
        isInitialized = false
        nativeDestroy()
    }

    private suspend fun <T> runCancellableSearch(block: () -> T): T =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                coroutineContext.ensureActive()
                ensureReadyLocked()
                val cancelHandle = coroutineContext[Job]?.invokeOnCancellation {
                    nativeStop()
                }
                try {
                    val result = block()
                    coroutineContext.ensureActive()
                    result
                } finally {
                    cancelHandle?.dispose()
                }
            }
        }

    /** Вызывать только под [mutex]. */
    private suspend fun initLocked(): Boolean {
        if (isInitialized) return true

        libraryLoadError?.let {
            Log.e(TAG, "Native library stockfish-lib is not available", it)
            return false
        }

        val net = netDownloader.ensureNet()
        if (net == null) {
            Log.e(TAG, "Neural network file is not available")
            return false
        }

        coroutineContext.ensureActive()
        isInitialized = nativeInit(net.absolutePath)
        Log.i(TAG, "Stockfish initialized: $isInitialized")
        return isInitialized
    }

    /** Вызывать только под [mutex]. Бросает исключение, если движок не готов. */
    private suspend fun ensureReadyLocked() {
        if (!isInitialized && !initLocked()) {
            throw IllegalStateException("Шахматный движок не инициализирован")
        }
    }

    /**
     * Формат строки от JNI: "<bestMove>\t<score>\t<pv>".
     * Мат кодируется в score (см. ChessEngine.MATE_VALUE), поэтому mateIn вычисляем из него.
     */
    private fun decodeAnalysisLine(encoded: String): AnalysisLine? {
        val parts = encoded.split('\t')
        if (parts.size < 3) return null

        val bestMove = parts[0].trim()
        val score = parts[1].toIntOrNull() ?: return null
        val pv = parts[2]
            .split(' ')
            .filter { it.isNotBlank() }

        val mateIn = if (ChessEngine.isMateScore(score)) {
            ChessEngine.getMateInMoves(score)
        } else {
            null
        }

        val normalizedPv = when {
            pv.isNotEmpty() -> pv
            bestMove.isNotEmpty() -> listOf(bestMove)
            else -> emptyList()
        }

        return AnalysisLine(
            bestMove = bestMove,
            score = score,
            mateIn = mateIn,
            principalVariation = normalizedPv
        )
    }

    private external fun nativeInit(netPath: String): Boolean
    private external fun nativeStop()
    private external fun nativeDestroy()
    private external fun nativeSetOption(name: String, value: String)
    private external fun nativeEvaluate(fen: String, depth: Int): Int
    private external fun nativeGetBestMove(fen: String, depth: Int): String?
    private external fun nativeGetBestMoveWithLine(fen: String, depth: Int): String?
}
