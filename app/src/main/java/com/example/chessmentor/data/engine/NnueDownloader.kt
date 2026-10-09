// data/engine/NnueDownloader.kt
package com.example.chessmentor.data.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Загрузка файла нейросети (NNUE) для Stockfish.
 *
 * Имя файла содержит первые 12 hex-символов SHA-256 (правило Stockfish).
 * Файл сохраняется только после успешной проверки хеша, поэтому повреждённый
 * или подменённый файл не попадает в движок (иначе Stockfish завершит процесс).
 */
class NnueDownloader(context: Context) {

    companion object {
        private const val TAG = "NnueDownloader"

        /** Имя сети по умолчанию для Stockfish sf_19 (см. evaluate.h в vendored-исходниках). */
        const val NET_NAME = "nn-1a298aa575a0.nnue"

        private const val EXPECTED_HASH_PREFIX = "1a298aa575a0"

        /** Минимальный размер сети: всё меньше считаем неполной загрузкой. */
        private const val MIN_NET_BYTES = 1L shl 20

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /** Те же источники, что использует scripts/net.sh из Stockfish. */
        private val SOURCES = listOf(
            "https://tests.stockfishchess.org/api/nn/$NET_NAME",
            "https://github.com/official-stockfish/networks/raw/master/$NET_NAME"
        )
    }

    private val netDirectory = File(context.filesDir, "nnue")

    /** Путь, по которому лежит проверенный файл сети. */
    fun netFile(): File = File(netDirectory, NET_NAME)

    /**
     * Возвращает путь к готовой сети, при необходимости скачивая её.
     * Возвращает null, если сеть не удалось получить.
     */
    suspend fun ensureNet(): File? = withContext(Dispatchers.IO) {
        val target = netFile()
        if (target.isFile && target.length() >= MIN_NET_BYTES) {
            return@withContext target
        }

        netDirectory.mkdirs()
        val partFile = File(netDirectory, "$NET_NAME.part")

        for (source in SOURCES) {
            try {
                Log.i(TAG, "Downloading network from $source")
                download(source, partFile)

                if (partFile.length() < MIN_NET_BYTES) {
                    Log.w(TAG, "Downloaded file is too small, skipping")
                    partFile.delete()
                    continue
                }
                if (sha256Prefix(partFile) != EXPECTED_HASH_PREFIX) {
                    Log.w(TAG, "Hash mismatch for $source, skipping")
                    partFile.delete()
                    continue
                }

                target.delete()
                if (partFile.renameTo(target)) {
                    return@withContext target
                }
                Log.w(TAG, "Failed to move network into place")
                partFile.delete()
            } catch (e: IOException) {
                Log.w(TAG, "Download failed from $source", e)
                partFile.delete()
            }
        }

        null
    }

    private fun download(source: String, destination: File) {
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code for $source")
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Prefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
}
