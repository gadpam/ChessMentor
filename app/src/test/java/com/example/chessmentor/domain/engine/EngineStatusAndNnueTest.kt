package com.example.chessmentor.domain.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStatusAndNnueTest {

    @Test
    fun extractExpectedHashPrefix_andComputeSha256Prefix_validateOfficialConvention() {
        assertEquals(
            "1a298aa575a0",
            NnueIntegrity.extractExpectedHashPrefix(NnueIntegrity.NET_NAME)
        )
        assertEquals(
            "abcdef012345",
            NnueIntegrity.extractExpectedHashPrefix("nn-ABCDEF012345.nnue")
        )
        assertNull(NnueIntegrity.extractExpectedHashPrefix("nn-short.nnue"))
        assertNull(NnueIntegrity.extractExpectedHashPrefix("weights.bin"))

        // Сверяем с эталонным SHA-256 от "abc": ba7816bf8f01cfea...
        val prefix = NnueIntegrity.computeSha256Prefix(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
            prefixLength = 12
        )
        assertEquals("ba7816bf8f01", prefix)
    }

    @Test
    fun verifyFile_acceptsValidPayload_andRejectsCorruptedOrTooSmallFiles() {
        val tempFile = Files.createTempFile("nnue_test_", ".nnue").toFile()
        try {
            val payload = ByteArray(150_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            tempFile.writeBytes(payload)

            val fullHex = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .take(6)
                .joinToString("") { "%02x".format(it) }

            var chunksVisited = 0
            assertTrue(
                NnueIntegrity.verifyFile(
                    file = tempFile,
                    expectedPrefix = fullHex,
                    minBytes = 100_000L,
                    onChunk = { chunksVisited++ }
                )
            )
            // Для 150 КБ и буфера 64 КБ onChunk должен вызваться несколько раз (точки отмены корутины)
            assertTrue(chunksVisited >= 3)

            // Файл меньше минимального порога отклоняется как неполная загрузка
            assertFalse(
                NnueIntegrity.verifyFile(
                    file = tempFile,
                    expectedPrefix = fullHex,
                    minBytes = NnueIntegrity.MIN_NET_BYTES
                )
            )

            // Изменение хотя бы одного байта ломает SHA-256 и отклоняется
            payload[42] = (payload[42].toInt() xor 0xFF).toByte()
            tempFile.writeBytes(payload)
            assertFalse(
                NnueIntegrity.verifyFile(
                    file = tempFile,
                    expectedPrefix = fullHex,
                    minBytes = 100_000L
                )
            )

            // Несуществующий файл безопасно возвращает false
            assertFalse(
                NnueIntegrity.verifyFile(
                    file = File(tempFile.parentFile, "missing_nnue_file.nnue"),
                    expectedPrefix = fullHex,
                    minBytes = 1L
                )
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun storageCheck_errorClassification_andDownloadingStatusFormatting_workCorrectly() {
        val mb = 1024L * 1024L

        // Проверка свободного места с запасом 16 МиБ
        assertTrue(NnueIntegrity.hasEnoughStorage(usableBytes = 90 * mb, expectedContentLength = 70 * mb))
        assertFalse(NnueIntegrity.hasEnoughStorage(usableBytes = 75 * mb, expectedContentLength = 70 * mb))
        assertFalse(NnueIntegrity.hasEnoughStorage(usableBytes = 50 * mb, expectedContentLength = -1L))
        assertTrue(NnueIntegrity.hasEnoughStorage(usableBytes = 120 * mb, expectedContentLength = -1L))
        assertTrue(NnueIntegrity.hasEnoughStorage(usableBytes = 0L, expectedContentLength = 70 * mb))

        // Классификация сетевых и файловых ошибок
        assertEquals(
            EngineErrorReason.NETWORK_UNAVAILABLE,
            NnueIntegrity.classifyError(UnknownHostException("tests.stockfishchess.org"))
        )
        assertEquals(
            EngineErrorReason.NETWORK_UNAVAILABLE,
            NnueIntegrity.classifyError(ConnectException("Network unreachable"))
        )
        assertEquals(
            EngineErrorReason.TIMEOUT,
            NnueIntegrity.classifyError(SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            EngineErrorReason.HTTP_ERROR,
            NnueIntegrity.classifyError(IOException("HTTP 503"), httpCode = 503)
        )
        assertEquals(
            EngineErrorReason.INSUFFICIENT_STORAGE,
            NnueIntegrity.classifyError(IOException("write failed: ENOSPC (No space left on device)"))
        )

        // Хост зеркала и форматирование прогресса загрузки
        assertEquals(
            "tests.stockfishchess.org",
            NnueIntegrity.extractHost("https://tests.stockfishchess.org/api/nn/${NnueIntegrity.NET_NAME}")
        )

        val knownTotal = EngineStatus.DownloadingNetwork(
            downloadedBytes = 35 * mb,
            totalBytes = 70 * mb,
            sourceIndex = 1,
            totalSources = 2,
            sourceHost = "tests.stockfishchess.org"
        )
        assertNotNull(knownTotal.progressFraction)
        assertEquals(0.5f, knownTotal.progressFraction!!, 0.001f)
        assertEquals(50, knownTotal.progressPercent)
        assertEquals("35.0 МБ / 70.0 МБ", knownTotal.formattedTransferred())
        assertEquals("зеркало 1/2 · tests.stockfishchess.org", knownTotal.formattedSource())
        assertEquals("Загрузка NNUE 50%", knownTotal.shortBadgeLabel())
        assertTrue(knownTotal.isBusy && knownTotal.isDownloading && !knownTotal.isReady)

        // Chunked-ответ без Content-Length
        val unknownTotal = EngineStatus.DownloadingNetwork(
            downloadedBytes = 5 * mb,
            totalBytes = -1L,
            sourceIndex = 2,
            totalSources = 2,
            sourceHost = "github.com"
        )
        assertNull(unknownTotal.progressFraction)
        assertNull(unknownTotal.progressPercent)
        assertEquals("5.00 МБ", unknownTotal.formattedTransferred())

        val ready = EngineStatus.Ready(netSizeBytes = 68 * mb)
        assertTrue(ready.isReady && !ready.isBusy && !ready.isError)
        assertTrue(ready.formattedSummary().contains("68.0 МБ"))
        assertTrue(ready.formattedSummary().contains("SHA-256 ✓"))
    }

    @Test
    fun resolveSafeRedirectUrl_andCopyStreamWithLimit_enforceSecurityAndSizeBounds() {
        val origin = "https://tests.stockfishchess.org/api/nn/${NnueIntegrity.NET_NAME}"

        // Допустимые HTTPS-редиректы (абсолютный и относительный)
        assertEquals(
            "https://cdn.stockfishchess.org/nn/${NnueIntegrity.NET_NAME}",
            NnueIntegrity.resolveSafeRedirectUrl(
                origin,
                "https://cdn.stockfishchess.org/nn/${NnueIntegrity.NET_NAME}"
            )
        )
        assertEquals(
            "https://tests.stockfishchess.org/mirrors/${NnueIntegrity.NET_NAME}",
            NnueIntegrity.resolveSafeRedirectUrl(origin, "/mirrors/${NnueIntegrity.NET_NAME}")
        )

        // Запрещаем downgrade на HTTP, опасные схемы, учётные данные и циклы
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, "http://tests.stockfishchess.org/nn"))
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, "file:///etc/passwd"))
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, "https://user:pass@example.com/nn"))
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, origin))
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, "   "))
        assertNull(NnueIntegrity.resolveSafeRedirectUrl(origin, null))

        // Граница MAX_NET_BYTES и MAX_REDIRECTS
        assertEquals(256L * 1024L * 1024L, NnueIntegrity.MAX_NET_BYTES)
        assertEquals(5, NnueIntegrity.MAX_REDIRECTS)

        // Ровно на границе maxBytes — проходит без ошибок
        val exact = ByteArray(1024) { 0x2A }
        val exactOut = ByteArrayOutputStream()
        val copied = NnueIntegrity.copyStreamWithLimit(
            input = ByteArrayInputStream(exact),
            output = exactOut,
            maxBytes = 1024L
        )
        assertEquals(1024L, copied)
        assertEquals(1024, exactOut.size())

        // Превышение лимита на 1 байт прерывается исключением и классифицируется как HTTP_ERROR
        val overflow = ByteArray(1025) { 0x2A }
        val overflowOut = ByteArrayOutputStream()
        var thrown: IOException? = null
        try {
            NnueIntegrity.copyStreamWithLimit(
                input = ByteArrayInputStream(overflow),
                output = overflowOut,
                maxBytes = 1024L
            )
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(EngineErrorReason.HTTP_ERROR, NnueIntegrity.classifyError(thrown!!))
        assertEquals(
            EngineErrorReason.HTTP_ERROR,
            NnueIntegrity.classifyError(IOException("Unsafe or cyclic redirect from $origin"))
        )
    }
}
