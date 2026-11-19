// domain/engine/EngineStatus.kt
package com.example.chessmentor.domain.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Причина сбоя при загрузке нейросети NNUE или инициализации Stockfish.
 */
enum class EngineErrorReason(
    val title: String,
    val userHint: String
) {
    NETWORK_UNAVAILABLE(
        title = "Нет подключения к сети",
        userHint = "Подключитесь к Wi-Fi или мобильной сети и нажмите «Повторить». Сеть NNUE скачивается один раз и затем работает полностью офлайн."
    ),
    TIMEOUT(
        title = "Превышено время ожидания",
        userHint = "Соединение с сервером Stockfish оказалось слишком медленным. Проверьте сеть и повторите попытку."
    ),
    HTTP_ERROR(
        title = "Сервер нейросети недоступен",
        userHint = "Зеркала Stockfish временно не ответили. Попробуйте повторить загрузку чуть позже."
    ),
    INSUFFICIENT_STORAGE(
        title = "Недостаточно свободного места",
        userHint = "Для загрузки и проверки файла нейросети NNUE требуется около 100 МБ свободной памяти на устройстве."
    ),
    INTEGRITY_MISMATCH(
        title = "Ошибка проверки SHA-256",
        userHint = "Скачанный файл нейросети повреждён и был удалён для безопасности. Нажмите «Повторить» для чистой загрузки."
    ),
    NATIVE_LIBRARY_MISSING(
        title = "Библиотека движка недоступна",
        userHint = "Нативный модуль stockfish-lib не найден для архитектуры этого устройства. Переустановите приложение."
    ),
    ENGINE_INIT_FAILED(
        title = "Ошибка запуска Stockfish",
        userHint = "Не удалось инициализировать движок. Попробуйте проверить или перекачать файл нейросети NNUE."
    ),
    CANCELLED(
        title = "Загрузка приостановлена",
        userHint = "Вы можете возобновить загрузку нейросети NNUE в любой момент."
    )
}

/**
 * Реактивное состояние готовности Stockfish и его нейросети NNUE.
 * Не содержит зависимостей от Android SDK и полностью тестируется в JVM.
 */
sealed interface EngineStatus {

    /** Движок ещё не запускался в текущей сессии. */
    data object Idle : EngineStatus

    /** Проверка локального файла нейросети NNUE. */
    data object CheckingNetwork : EngineStatus

    /** Идёт скачивание файла нейросети NNUE с одного из официальных зеркал. */
    data class DownloadingNetwork(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val sourceIndex: Int,
        val totalSources: Int,
        val sourceHost: String
    ) : EngineStatus {

        /** Доля прогресса `0f..1f` или `null`, если сервер не передал `Content-Length`. */
        val progressFraction: Float?
            get() = if (totalBytes > 0L) {
                (downloadedBytes.coerceAtLeast(0L).toDouble() / totalBytes.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
            } else {
                null
            }

        /** Процент загрузки `0..100` или `null`, если общий размер неизвестен. */
        val progressPercent: Int?
            get() = progressFraction?.let { (it * 100f).toInt().coerceIn(0, 100) }

        /** Человекочитаемый объём: `"28.4 / 67.2 МБ"` или `"28.4 МБ"`. */
        fun formattedTransferred(): String {
            val done = NnueIntegrity.formatMegabytes(downloadedBytes)
            return if (totalBytes > 0L) {
                "$done / ${NnueIntegrity.formatMegabytes(totalBytes)}"
            } else {
                done
            }
        }

        /** Подпись текущего источника загрузки. */
        fun formattedSource(): String {
            val mirror = if (totalSources > 1) {
                "зеркало ${sourceIndex.coerceIn(1, totalSources)}/$totalSources"
            } else {
                "сервер"
            }
            return if (sourceHost.isNotBlank()) "$mirror · $sourceHost" else mirror
        }
    }

    /** Проверка контрольной суммы SHA-256 файла нейросети. */
    data class VerifyingNetwork(val fileBytes: Long) : EngineStatus

    /** Запуск нативного движка Stockfish и применение параметров UCI. */
    data class InitializingEngine(val netSizeBytes: Long) : EngineStatus

    /** Движок и проверенная сеть NNUE полностью готовы к офлайн-работе. */
    data class Ready(
        val netName: String = NnueIntegrity.NET_NAME,
        val netSizeBytes: Long = 0L,
        val hashPrefix: String = NnueIntegrity.EXPECTED_SHA256_PREFIX
    ) : EngineStatus {

        fun formattedSummary(): String = buildString {
            append(netName)
            if (netSizeBytes > 0L) {
                append(" · ")
                append(NnueIntegrity.formatMegabytes(netSizeBytes))
            }
            append(" · SHA-256 ✓")
        }
    }

    /** Загрузка сети или инициализация движка завершилась ошибкой. */
    data class Error(
        val reason: EngineErrorReason,
        val message: String = reason.title,
        val canRetry: Boolean = reason != EngineErrorReason.NATIVE_LIBRARY_MISSING
    ) : EngineStatus

    val isReady: Boolean get() = this is Ready
    val isError: Boolean get() = this is Error
    val isDownloading: Boolean get() = this is DownloadingNetwork
    val isBusy: Boolean
        get() = this is CheckingNetwork ||
            this is DownloadingNetwork ||
            this is VerifyingNetwork ||
            this is InitializingEngine

    /** Короткая метка состояния для карточек и настроек. */
    fun shortBadgeLabel(): String = when (this) {
        Idle -> "Ожидание"
        CheckingNetwork -> "Проверка NNUE…"
        is DownloadingNetwork -> {
            val pct = progressPercent
            if (pct != null) "Загрузка NNUE $pct%" else "Загрузка NNUE (${formattedTransferred()})"
        }
        is VerifyingNetwork -> "Проверка SHA-256…"
        is InitializingEngine -> "Запуск Stockfish…"
        is Ready -> "NNUE готов"
        is Error -> when (reason) {
            EngineErrorReason.CANCELLED -> "Пауза"
            EngineErrorReason.NETWORK_UNAVAILABLE -> "Нет сети"
            EngineErrorReason.INSUFFICIENT_STORAGE -> "Мало памяти"
            else -> "Нужна загрузка"
        }
    }
}

/**
 * Чистые JVM-утилиты проверки целостности NNUE, оценки места на диске
 * и классификации сетевых/файловых ошибок.
 */
object NnueIntegrity {

    /** Имя сети по умолчанию для Stockfish 19 (`evaluate.h`: `EvalFileDefaultName`). */
    const val NET_NAME = "nn-1a298aa575a0.nnue"

    /** Ожидаемые первые 12 hex-символов SHA-256 файла нейросети. */
    const val EXPECTED_SHA256_PREFIX = "1a298aa575a0"

    /** Минимальный допустимый размер валидной сети (1 МиБ). */
    const val MIN_NET_BYTES = 1L shl 20

    /** Запас свободного места сверх размера файла сети (16 МиБ). */
    const val SAFETY_HEADROOM_BYTES = 16L shl 20

    /** Требуемый объём свободного места, если `Content-Length` неизвестен (96 МиБ). */
    const val FALLBACK_REQUIRED_BYTES = 96L shl 20

    private val NET_NAME_REGEX = Regex("""^nn-([0-9a-fA-F]{12})\.nnue$""")

    /** Извлекает 12-символьный префикс SHA-256 из канонического имени `nn-<hash12>.nnue`. */
    fun extractExpectedHashPrefix(netName: String): String? =
        NET_NAME_REGEX.matchEntire(netName.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** Вычисляет первые [prefixLength] hex-символов SHA-256 потока без загрузки файла в память. */
    fun computeSha256Prefix(
        input: InputStream,
        prefixLength: Int = 12,
        onChunk: () -> Unit = {}
    ): String {
        require(prefixLength in 1..64) { "prefixLength must be in 1..64" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            onChunk()
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        val bytesNeeded = (prefixLength + 1) / 2
        val hash = digest.digest()
        val hex = buildString(bytesNeeded * 2) {
            for (i in 0 until bytesNeeded.coerceAtMost(hash.size)) {
                val v = hash[i].toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
        return hex.take(prefixLength)
    }

    /** Проверяет, что файл существует, не меньше [minBytes] и его SHA-256 начинается с [expectedPrefix]. */
    fun verifyFile(
        file: File,
        expectedPrefix: String = EXPECTED_SHA256_PREFIX,
        minBytes: Long = MIN_NET_BYTES,
        onChunk: () -> Unit = {}
    ): Boolean {
        if (!file.isFile || file.length() < minBytes) return false
        val normalizedExpected = expectedPrefix.trim().lowercase(Locale.ROOT)
        if (normalizedExpected.isEmpty()) return false
        val actual = file.inputStream().use { stream ->
            computeSha256Prefix(
                input = stream,
                prefixLength = normalizedExpected.length,
                onChunk = onChunk
            )
        }
        return actual == normalizedExpected
    }

    /** Достаточно ли [usableBytes] на разделе для скачивания файла размера [expectedContentLength]. */
    fun hasEnoughStorage(usableBytes: Long, expectedContentLength: Long): Boolean {
        if (usableBytes <= 0L) return true // ФС не сообщила размер — не блокируем ложным отказом
        val required = if (expectedContentLength > 0L) {
            expectedContentLength + SAFETY_HEADROOM_BYTES
        } else {
            FALLBACK_REQUIRED_BYTES
        }
        return usableBytes >= required
    }

    /** Форматирует размер в МБ (например, `"67.4 МБ"`). */
    fun formatMegabytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val mb = safe.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 10.0) {
            String.format(Locale.US, "%.1f МБ", mb)
        } else {
            String.format(Locale.US, "%.2f МБ", mb)
        }
    }

    /** Безопасно извлекает имя хоста из URL зеркала. */
    fun extractHost(url: String): String =
        runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")

    /** Классифицирует исключение загрузки в понятную пользователю причину [EngineErrorReason]. */
    fun classifyError(error: Throwable, httpCode: Int? = null): EngineErrorReason {
        if (httpCode != null && httpCode >= 400) {
            return EngineErrorReason.HTTP_ERROR
        }
        val msg = error.message.orEmpty().lowercase(Locale.ROOT)
        if ("enospc" in msg || "no space left" in msg || "not enough space" in msg || "disk full" in msg) {
            return EngineErrorReason.INSUFFICIENT_STORAGE
        }
        return when (error) {
            is SocketTimeoutException -> EngineErrorReason.TIMEOUT
            is InterruptedIOException -> EngineErrorReason.TIMEOUT
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SSLException -> EngineErrorReason.NETWORK_UNAVAILABLE
            is IOException -> {
                if ("http " in msg) EngineErrorReason.HTTP_ERROR else EngineErrorReason.NETWORK_UNAVAILABLE
            }
            else -> EngineErrorReason.ENGINE_INIT_FAILED
        }
    }

    private const val HEX_CHARS = "0123456789abcdef"
}
