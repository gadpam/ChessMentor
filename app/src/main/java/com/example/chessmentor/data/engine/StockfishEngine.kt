// data/engine/StockfishEngine.kt
package com.example.chessmentor.data.engine

import android.content.Context
import android.util.Log
import com.example.chessmentor.domain.engine.EngineErrorReason
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.engine.NnueIntegrity
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stockfish через JNI (нативная библиотека `stockfish-lib`, см. `app/src/main/cpp`).
 *
 * Все обращения к нативному коду сериализуются через [mutex] и внутренний мьютекс
 * `StockfishBridge`. Ход проверки/скачивания нейросети NNUE и готовность движка
 * публикуются в реальном времени через [status].
 */
class StockfishEngine(
    context: Context,
    private val engineSettingsRepository: EngineSettingsRepository? = null
) : ChessEngine {

    private val netDownloader = NnueDownloader(context.applicationContext)
    private val mutex = Mutex()
    private val nativeControlLock = Any()
    private val stateLock = Any()

    @Volatile
    private var isInitialized = false

    private val lifecycleEpoch = AtomicInteger(0)

    @Volatile
    private var activeInitJob: Job? = null

    private val _status = MutableStateFlow<EngineStatus>(
        if (libraryLoadError != null) {
            EngineStatus.Error(
                reason = EngineErrorReason.NATIVE_LIBRARY_MISSING,
                message = EngineErrorReason.NATIVE_LIBRARY_MISSING.title,
                canRetry = false
            )
        } else {
            EngineStatus.Idle
        }
    )
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    companion object {
        private const val TAG = "StockfishEngine"

        /** Ошибка загрузки нативной библиотеки (например, если сборка NDK не выполнена). */
        private val libraryLoadError: Throwable? = runCatching {
            System.loadLibrary("stockfish-lib")
        }.exceptionOrNull()
    }

    override suspend fun init(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            initLocked(forceRedownload = false)
        }
    }

    /**
     * Идемпотентный прогрев для вызова из любого места (например, из Activity при каждом
     * `onCreate`, включая повторное создание после поворота экрана).
     *
     * - уже готовый движок возвращает `true` без повторной работы;
     * - при незавершённой загрузке/проверке NNUE новый проход не запускается,
     *   поэтому прогресс не сбрасывается и `.part`-файл не удаляется;
     * - пауза, поставленная пользователем ([ChessEngine.cancelInit]), не перетирается
     *   автостартом: возобновление остаётся явным действием в настройках;
     * - аварийно оборванная инициализация (отмена корутины, `CancellationException`)
     *   при следующем старте запускается заново, а не оставляет вечный прогресс.
     *
     * Ошибки загрузки (нет сети, сбой SHA-256) не ретраятся автоматически бесконечно:
     * каждая новая попытка привязана к новому жизненному циклу Activity, а сообщение
     * об ошибке пользователь видит в карточке статуса движка.
     */
    override suspend fun ensureReadyOnStartup(): Boolean {
        val current = _status.value
        if (isInitialized && current is EngineStatus.Ready) return true
        // Явная пауза пользователя важнее автостарта: возобновление только кнопкой.
        if (current is EngineStatus.Error && current.reason == EngineErrorReason.CANCELLED) return false
        // «Занято» засчитываем только при реально живой корутине инициализации,
        // иначе после аварийной отмены UI остался бы на вечном прогрессе.
        if (current.isBusy && activeInitJob?.isActive == true) return false
        return init()
    }

    override suspend fun retryInit(forceRedownload: Boolean): Boolean =
        withContext(Dispatchers.Default) {
            if (forceRedownload) {
                cancelInit()
                stop()
            }
            mutex.withLock {
                if (forceRedownload && libraryLoadError == null) {
                    synchronized(nativeControlLock) {
                        if (isInitialized) {
                            synchronized(stateLock) { isInitialized = false }
                            nativeDestroy()
                        }
                    }
                }
                initLocked(forceRedownload = forceRedownload)
            }
        }

    override suspend fun verifyNetwork(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            val startEpoch = lifecycleEpoch.get()
            val callerJob = coroutineContext[Job]
            activeInitJob = callerJob
            try {
                val ok = netDownloader.verifyInstalledNet { progress ->
                    synchronized(stateLock) {
                        if (lifecycleEpoch.get() == startEpoch) {
                            _status.value = progress
                        }
                    }
                }
                if (lifecycleEpoch.get() != startEpoch) {
                    return@withLock false
                }
                if (!ok) {
                    if (libraryLoadError == null) {
                        synchronized(nativeControlLock) {
                            if (isInitialized) {
                                synchronized(stateLock) { isInitialized = false }
                                nativeDestroy()
                            }
                        }
                    }
                    synchronized(stateLock) {
                        if (lifecycleEpoch.get() == startEpoch) {
                            _status.value = EngineStatus.Error(
                                reason = EngineErrorReason.INTEGRITY_MISMATCH,
                                message = "Файл нейросети NNUE отсутствует или повреждён",
                                canRetry = true
                            )
                        }
                    }
                    return@withLock false
                }

                val file = netDownloader.netFile()
                if (!isInitialized) {
                    return@withLock initLocked(forceRedownload = false)
                }

                synchronized(stateLock) {
                    if (lifecycleEpoch.get() != startEpoch) return@withLock false
                    _status.value = EngineStatus.Ready(
                        netName = file.name,
                        netSizeBytes = file.length(),
                        hashPrefix = NnueIntegrity.EXPECTED_SHA256_PREFIX
                    )
                }
                true
            } catch (e: CancellationException) {
                // Отмена проверки — не ошибка целостности: статус не подменяем.
                throw e
            } finally {
                if (activeInitJob === callerJob) {
                    activeInitJob = null
                }
            }
        }
    }

    override suspend fun applySettings(settings: EngineSettings) =
        withContext(Dispatchers.Default) {
            val valid = settings.validate()
            mutex.withLock {
                // Применяем сразу, если движок уже запущен; иначе настройки подхватятся в initLocked()
                if (libraryLoadError == null) {
                    synchronized(nativeControlLock) {
                        if (isInitialized) {
                            nativeSetOption("Threads", valid.threads.toString())
                            nativeSetOption("Hash", valid.hashSizeMb.toString())
                        }
                    }
                }
            }
        }

    override fun cancelInit() {
        cancelInitInternal(markCancelled = true)
    }

    private fun cancelInitInternal(markCancelled: Boolean) {
        val job = activeInitJob
        // Статус выставляется до отмены: отменённая корутина больше не пишет свой статус,
        // поэтому пауза видна пользователю и не перетирается следующим автостартом.
        if (markCancelled && job != null && job.isActive) {
            synchronized(stateLock) {
                if (!isInitialized) {
                    _status.value = EngineStatus.Error(
                        reason = EngineErrorReason.CANCELLED,
                        message = EngineErrorReason.CANCELLED.title,
                        canRetry = true
                    )
                }
            }
        }
        if (job != null && job.isActive) {
            job.cancel(CancellationException("NNUE initialization cancelled by user"))
        }
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
            synchronized(nativeControlLock) {
                if (!isInitialized) {
                    throw IllegalStateException("Шахматный движок Stockfish был остановлен")
                }
                nativeSetOption(name, value)
            }
        }
    }

    override fun stop() {
        if (libraryLoadError != null) return
        nativeStop()
    }

    /**
     * Прерывает активный поиск/загрузку и безопасно освобождает нативный движок.
     * Следующий вызов [init] создаст движок заново.
     */
    override fun destroy() {
        lifecycleEpoch.incrementAndGet()
        cancelInitInternal(markCancelled = false)
        if (libraryLoadError != null) return
        nativeStop()
        synchronized(nativeControlLock) {
            nativeDestroy()
            synchronized(stateLock) {
                isInitialized = false
                _status.value = EngineStatus.Idle
            }
        }
    }

    private suspend fun <T> runCancellableSearch(block: () -> T): T =
        withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            mutex.withLock {
                coroutineContext.ensureActive()
                ensureReadyLocked()
                val stopTriggered = AtomicBoolean(false)
                val cancelWatcher = Job(parent = coroutineContext[Job])
                val cancelHandle = cancelWatcher.invokeOnCompletion { cause ->
                    if (cause is CancellationException && stopTriggered.compareAndSet(false, true)) {
                        nativeStop()
                    }
                }
                try {
                    coroutineContext.ensureActive()
                    if (stopTriggered.get()) {
                        throw CancellationException("Stockfish search cancelled before native start")
                    }
                    val result = block()
                    coroutineContext.ensureActive()
                    result
                } finally {
                    cancelHandle.dispose()
                    cancelWatcher.complete()
                }
            }
        }

    /** Вызывать только под [mutex]. */
    private suspend fun initLocked(forceRedownload: Boolean): Boolean {
        val startEpoch = lifecycleEpoch.get()
        if (isInitialized && !forceRedownload) {
            synchronized(stateLock) {
                if (lifecycleEpoch.get() != startEpoch || !isInitialized) return false
                if (_status.value !is EngineStatus.Ready) {
                    val file = netDownloader.netFile()
                    _status.value = EngineStatus.Ready(
                        netName = file.name,
                        netSizeBytes = file.takeIf { it.isFile }?.length() ?: 0L,
                        hashPrefix = NnueIntegrity.EXPECTED_SHA256_PREFIX
                    )
                }
            }
            return true
        }

        libraryLoadError?.let {
            Log.e(TAG, "Native library stockfish-lib is not available", it)
            synchronized(stateLock) {
                _status.value = EngineStatus.Error(
                    reason = EngineErrorReason.NATIVE_LIBRARY_MISSING,
                    message = EngineErrorReason.NATIVE_LIBRARY_MISSING.title,
                    canRetry = false
                )
            }
            return false
        }

        val callerJob = coroutineContext[Job]
        activeInitJob = callerJob
        try {
            val netResult = netDownloader.ensureNetWithStatus(
                forceRedownload = forceRedownload,
                onStatus = { progress ->
                    synchronized(stateLock) {
                        if (lifecycleEpoch.get() == startEpoch) {
                            _status.value = progress
                        }
                    }
                }
            )

            if (lifecycleEpoch.get() != startEpoch) return false

            val net = when (netResult) {
                is NnueDownloader.EnsureResult.Ready -> netResult.file
                is NnueDownloader.EnsureResult.Failed -> {
                    Log.e(TAG, "Neural network unavailable: ${netResult.reason} (${netResult.message})")
                    synchronized(stateLock) {
                        if (lifecycleEpoch.get() == startEpoch) {
                            _status.value = EngineStatus.Error(
                                reason = netResult.reason,
                                message = netResult.message,
                                canRetry = true
                            )
                        }
                    }
                    return false
                }
            }

            coroutineContext.ensureActive()
            val netBytes = net.length()
            synchronized(stateLock) {
                if (lifecycleEpoch.get() != startEpoch) return false
                _status.value = EngineStatus.InitializingEngine(netSizeBytes = netBytes)
            }

            val ok = synchronized(nativeControlLock) {
                if (lifecycleEpoch.get() != startEpoch) return@synchronized false
                val initOk = nativeInit(net.absolutePath)
                if (lifecycleEpoch.get() != startEpoch) {
                    if (initOk) {
                        nativeDestroy()
                    }
                    synchronized(stateLock) { isInitialized = false }
                    return@synchronized false
                }
                if (initOk) {
                    engineSettingsRepository?.let { repo ->
                        runCatching {
                            val settings = repo.getSettings().validate()
                            nativeSetOption("Threads", settings.threads.toString())
                            nativeSetOption("Hash", settings.hashSizeMb.toString())
                        }.onFailure { err ->
                            Log.w(TAG, "Failed to apply initial UCI settings", err)
                        }
                    }
                }
                var committed = false
                synchronized(stateLock) {
                    if (lifecycleEpoch.get() != startEpoch) {
                        isInitialized = false
                    } else {
                        isInitialized = initOk
                        committed = initOk
                        _status.value = if (initOk) {
                            EngineStatus.Ready(
                                netName = net.name,
                                netSizeBytes = netBytes,
                                hashPrefix = NnueIntegrity.EXPECTED_SHA256_PREFIX
                            )
                        } else {
                            EngineStatus.Error(
                                reason = EngineErrorReason.ENGINE_INIT_FAILED,
                                message = EngineErrorReason.ENGINE_INIT_FAILED.title,
                                canRetry = true
                            )
                        }
                    }
                }
                if (!committed && initOk && lifecycleEpoch.get() != startEpoch) {
                    nativeDestroy()
                }
                committed
            }
            if (lifecycleEpoch.get() != startEpoch) return false
            Log.i(TAG, "Stockfish initialized: $ok")
            return ok
        } catch (e: CancellationException) {
            // Отмена корутины (уход приложения в фон, поворот экрана) — не ошибка загрузки.
            // Статус остаётся текущим, а следующий ensureReadyOnStartup() продолжит работу.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Stockfish initialization", e)
            val reason = NnueIntegrity.classifyError(e)
            synchronized(stateLock) {
                if (lifecycleEpoch.get() == startEpoch) {
                    _status.value = EngineStatus.Error(
                        reason = reason,
                        message = e.message ?: reason.title,
                        canRetry = true
                    )
                }
            }
            return false
        } finally {
            if (activeInitJob === callerJob) {
                activeInitJob = null
            }
        }
    }

    /** Вызывать только под [mutex]. Бросает понятное исключение, если движок не готов. */
    private suspend fun ensureReadyLocked() {
        if (!isInitialized && !initLocked(forceRedownload = false)) {
            val err = _status.value as? EngineStatus.Error
            val detailedMessage = if (err != null) {
                "${err.message}. ${err.reason.userHint}"
            } else {
                "Шахматный движок Stockfish не инициализирован"
            }
            throw IllegalStateException(detailedMessage)
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
