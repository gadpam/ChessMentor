// domain/security/PasswordHasher.kt
package com.example.chessmentor.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хеширование паролей через PBKDF2-HMAC-SHA256 (стандартный JCE, без внешних зависимостей).
 *
 * Формат хранения: `pbkdf2_sha256$<итерации>$<соль base64>$<хеш base64>`.
 *
 * Старые записи вида `hashed_<пароль>` (до перехода на PBKDF2) распознаются через
 * [isLegacyHash] и [verifyLegacy]. Use case входа переписывает их в новом формате.
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PREFIX = "pbkdf2_sha256"
    private const val LEGACY_PREFIX = "hashed_"

    /** Рекомендация OWASP для PBKDF2-HMAC-SHA256. */
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val derived = derive(password, salt, ITERATIONS, KEY_BITS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            encode(salt),
            encode(derived)
        ).joinToString("\$")
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false

        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = decode(parts[2]) ?: return false
        val expected = decode(parts[3]) ?: return false
        if (expected.isEmpty()) return false

        val actual = derive(password, salt, iterations, expected.size * 8)
        // Сравнение за постоянное время
        return MessageDigest.isEqual(actual, expected)
    }

    fun isLegacyHash(stored: String): Boolean = stored.startsWith(LEGACY_PREFIX)

    /** Проверка старого формата. Использовать только для миграции существующих записей. */
    fun verifyLegacy(password: String, stored: String): Boolean =
        isLegacyHash(stored) && stored == LEGACY_PREFIX + password

    private fun derive(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? =
        try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
}
