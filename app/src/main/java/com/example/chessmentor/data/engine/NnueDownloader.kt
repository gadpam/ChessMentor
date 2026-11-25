// data/engine/NnueDownloader.kt
package com.example.chessmentor.data.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.chessmentor.domain.engine.EngineErrorReason
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.engine.NnueIntegrity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Загрузка и верификация файла нейросети (NNUE) для Stockfish с живым прогрессом.
 *
 * Имя файла содержит первые 12 hex-символов SHA-256 (правило Stockfish `evaluate.h`).
 * Файл атомарно перемещается на целевое место только после прохождения проверки SHA-256,
 * поэтому повреждённый или неполный файл никогда не передаётся в нативный Stockfish.
 */
class NnueDownloader(context: Context) {

    sealed interface EnsureResult {
        data class Ready(val file: File, val wasDownloaded: Boolean) : EnsureResult
        data class Failed(val reason: EngineErrorReason, val message: String) : EnsureResult
    }

    companion object {
        private const val TAG = "NnueDownloader"

        /** Имя сети по умолчанию для Stockfish 19 (см. `evaluate.h`). */
        const val NET_NAME: String = NnueIntegrity.NET_NAME

        const val EXPECTED_HASH_PREFIX: String = NnueIntegrity.EXPECTED_SHA256_PREFIX

        private const val MIN_NET_BYTES: Long = NnueIntegrity.MIN_NET_BYTES
        private const val MAX_NET_BYTES: Long = NnueIntegrity.MAX_NET_BYTES
        private const val MAX_REDIRECTS: Int = NnueIntegrity.MAX_REDIRECTS
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L

        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308
        )

        /** Те же официальные зеркала, что использует `scripts/net.sh` из Stockfish. */
        private val SOURCES = listOf(
            "https://tests.stockfishchess.org/api/nn/$NET_NAME",
            "https://github.com/official-stockfish/networks/raw/master/$NET_NAME"
        )
    }

    private val netDirectory = File(context.filesDir, "nnue")

    @Volatile
    private var verifiedExistingFile = false

    /** Путь, по которому хранится проверенный файл нейросети. */
    fun netFile(): File = File(netDirectory, NET_NAME)

    /** Удаляет установленный и временный файлы сети (перед принудительной перезагрузкой). */
    fun deleteNetFiles() {
        verifiedExistingFile = false
        runCatching { File(netDirectory, "$NET_NAME.part").delete() }
        runCatching { netFile().delete() }
    }

    /**
     * Явная полная проверка SHA-256 установленного на диске файла NNUE
     * (игнорирует кэш [verifiedExistingFile]).
     */
    suspend fun verifyInstalledNet(
        onStatus: (EngineStatus) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val target = netFile()
        if (!target.isFile || target.length() < MIN_NET_BYTES) {
            verifiedExistingFile = false
            return@withContext false
        }
        onStatus(EngineStatus.VerifyingNetwork(target.length()))
        val ok = isValidNetFile(target) {
            coroutineContext.ensureActive()
        }
        verifiedExistingFile = ok
        if (!ok) {
            Log.w(TAG, "Installed network failed explicit SHA-256 check, removing")
            target.delete()
        }
        ok
    }

    /** Совместимый упрощённый метод, возвращающий готовый [File] или `null`. */
    suspend fun ensureNet(): File? =
        (ensureNetWithStatus() as? EnsureResult.Ready)?.file

    /**
     * Проверяет локальную сеть NNUE или скачивает её с официальных зеркал,
     * публикуя промежуточные состояния через [onStatus].
     */
    suspend fun ensureNetWithStatus(
        forceRedownload: Boolean = false,
        onStatus: (EngineStatus) -> Unit = {}
    ): EnsureResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        onStatus(EngineStatus.CheckingNetwork)

        if (forceRedownload) {
            deleteNetFiles()
        }

        val target = netFile()
        if (!forceRedownload && target.isFile && target.length() >= MIN_NET_BYTES) {
            if (verifiedExistingFile) {
                return@withContext EnsureResult.Ready(target, wasDownloaded = false)
            }
            onStatus(EngineStatus.VerifyingNetwork(target.length()))
            if (isValidNetFile(target) { coroutineContext.ensureActive() }) {
                verifiedExistingFile = true
                return@withContext EnsureResult.Ready(target, wasDownloaded = false)
            }
            Log.w(TAG, "Existing network file failed SHA-256 check, re-downloading")
            target.delete()
        }

        if (!netDirectory.exists() && !netDirectory.mkdirs()) {
            return@withContext EnsureResult.Failed(
                reason = EngineErrorReason.INSUFFICIENT_STORAGE,
                message = "Не удалось создать каталог для нейросети NNUE"
            )
        }

        // Предварительная проверка свободного места
        if (!NnueIntegrity.hasEnoughStorage(netDirectory.usableSpace, expectedContentLength = -1L)) {
            return@withContext EnsureResult.Failed(
                reason = EngineErrorReason.INSUFFICIENT_STORAGE,
                message = EngineErrorReason.INSUFFICIENT_STORAGE.title
            )
        }

        val partFile = File(netDirectory, "$NET_NAME.part")
        runCatching { partFile.delete() }

        var lastReason = EngineErrorReason.NETWORK_UNAVAILABLE
        var lastMessage = lastReason.title

        for ((index, source) in SOURCES.withIndex()) {
            coroutineContext.ensureActive()
            val sourceNumber = index + 1
            val host = NnueIntegrity.extractHost(source)
            var lastHttpCode: Int? = null

            try {
                Log.i(TAG, "Downloading NNUE ($sourceNumber/${SOURCES.size}) from $source")
                onStatus(
                    EngineStatus.DownloadingNetwork(
                        downloadedBytes = 0L,
                        totalBytes = -1L,
                        sourceIndex = sourceNumber,
                        totalSources = SOURCES.size,
                        sourceHost = host
                    )
                )

                download(
                    source = source,
                    destination = partFile,
                    onHttpError = { code -> lastHttpCode = code },
                    onContentLength = { contentLength ->
                        if (!NnueIntegrity.hasEnoughStorage(netDirectory.usableSpace, contentLength)) {
                            throw InsufficientStorageException(
                                "Недостаточно свободного места для файла ${NnueIntegrity.formatMegabytes(contentLength)}"
                            )
                        }
                    },
                    onProgress = { downloaded, total ->
                        onStatus(
                            EngineStatus.DownloadingNetwork(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                sourceIndex = sourceNumber,
                                totalSources = SOURCES.size,
                                sourceHost = host
                            )
                        )
                    }
                )

                coroutineContext.ensureActive()
                onStatus(EngineStatus.VerifyingNetwork(partFile.length()))

                if (!isValidNetFile(partFile) { coroutineContext.ensureActive() }) {
                    Log.w(TAG, "Downloaded NNUE from $source failed SHA-256 verification")
                    partFile.delete()
                    lastReason = EngineErrorReason.INTEGRITY_MISMATCH
                    lastMessage = EngineErrorReason.INTEGRITY_MISMATCH.title
                    continue
                }

                if (moveIntoPlace(partFile, target)) {
                    verifiedExistingFile = true
                    Log.i(
                        TAG,
                        "NNUE verified and installed: ${target.name} (${NnueIntegrity.formatMegabytes(target.length())})"
                    )
                    return@withContext EnsureResult.Ready(target, wasDownloaded = true)
                }

                Log.w(TAG, "Failed to move verified NNUE file into place")
                partFile.delete()
                lastReason = EngineErrorReason.INSUFFICIENT_STORAGE
                lastMessage = "Не удалось сохранить проверенный файл нейросети"
            } catch (e: CancellationException) {
                partFile.delete()
                throw e
            } catch (e: InsufficientStorageException) {
                partFile.delete()
                return@withContext EnsureResult.Failed(
                    reason = EngineErrorReason.INSUFFICIENT_STORAGE,
                    message = e.message ?: EngineErrorReason.INSUFFICIENT_STORAGE.title
                )
            } catch (e: Exception) {
                Log.w(TAG, "NNUE download failed from $source", e)
                partFile.delete()
                val classified = NnueIntegrity.classifyError(e, lastHttpCode)
                // Не затираем более точную ошибку целостности общей сетевой ошибкой второго зеркала
                if (lastReason != EngineErrorReason.INTEGRITY_MISMATCH) {
                    lastReason = classified
                    lastMessage = if (lastHttpCode != null) {
                        "${classified.title} (HTTP $lastHttpCode)"
                    } else {
                        classified.title
                    }
                }
                if (classified == EngineErrorReason.INSUFFICIENT_STORAGE) {
                    return@withContext EnsureResult.Failed(classified, classified.title)
                }
            }
        }

        EnsureResult.Failed(reason = lastReason, message = lastMessage)
    }

    private inline fun isValidNetFile(file: File, crossinline onChunk: () -> Unit): Boolean {
        if (!file.isFile || file.length() < MIN_NET_BYTES) return false
        return try {
            NnueIntegrity.verifyFile(
                file = file,
                expectedPrefix = EXPECTED_HASH_PREFIX,
                minBytes = MIN_NET_BYTES,
                onChunk = { onChunk() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Failed to verify NNUE SHA-256", e)
            false
        }
    }

    private fun moveIntoPlace(partFile: File, target: File): Boolean {
        runCatching { if (target.exists()) target.delete() }
        if (partFile.renameTo(target)) return true
        return try {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
            target.isFile && target.length() >= MIN_NET_BYTES
        } catch (e: IOException) {
            Log.w(TAG, "Fallback copy failed for NNUE file", e)
            runCatching { target.delete() }
            false
        }
    }

    private suspend fun download(
        source: String,
        destination: File,
        onHttpError: (Int) -> Unit,
        onContentLength: (Long) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val callerContext = coroutineContext
        var currentUrl = source
        val visitedUrls = mutableSetOf(currentUrl)

        for (hop in 0..MAX_REDIRECTS) {
            callerContext.ensureActive()
            val url = URL(currentUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Unsafe non-HTTPS NNUE source: $currentUrl")
            }

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")

                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) {
                        onHttpError(code)
                        throw IOException("HTTP $code: too many redirects ($MAX_REDIRECTS) for $source")
                    }
                    val nextUrl = NnueIntegrity.resolveSafeRedirectUrl(
                        currentUrl = currentUrl,
                        locationHeader = connection.getHeaderField("Location")
                    )
                    if (nextUrl == null || !visitedUrls.add(nextUrl)) {
                        onHttpError(code)
                        throw IOException("Unsafe or cyclic redirect from $currentUrl")
                    }
                    currentUrl = nextUrl
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    onHttpError(code)
                    throw IOException("HTTP $code for $currentUrl")
                }

                val totalBytes = connection.contentLengthLong
                if (totalBytes > MAX_NET_BYTES) {
                    throw IOException("Download exceeds maximum allowed NNUE size ($totalBytes > $MAX_NET_BYTES bytes)")
                }
                onContentLength(totalBytes)
                onProgress(0L, totalBytes)

                var lastEmitMs = SystemClock.elapsedRealtime()
                val downloaded = connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        NnueIntegrity.copyStreamWithLimit(
                            input = input,
                            output = output,
                            maxBytes = MAX_NET_BYTES,
                            onChunk = { callerContext.ensureActive() },
                            onProgress = { bytes ->
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmitMs = nowMs
                                    onProgress(bytes, totalBytes)
                                }
                            }
                        )
                    }
                }
                onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded)
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects ($MAX_REDIRECTS) for $source")
    }

    private class InsufficientStorageException(message: String) : IOException(message)
}
