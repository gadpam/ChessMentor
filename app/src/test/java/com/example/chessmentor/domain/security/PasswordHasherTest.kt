package com.example.chessmentor.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun `hash then verify with correct password succeeds`() {
        val stored = PasswordHasher.hash("secret123")
        assertTrue(PasswordHasher.verify("secret123", stored))
    }

    @Test
    fun `verify with wrong password fails`() {
        val stored = PasswordHasher.hash("secret123")
        assertFalse(PasswordHasher.verify("secret124", stored))
    }

    @Test
    fun `same password produces different hashes because of random salt`() {
        val first = PasswordHasher.hash("secret123")
        val second = PasswordHasher.hash("secret123")
        assertNotEquals(first, second)
    }

    @Test
    fun `hash has expected format`() {
        val parts = PasswordHasher.hash("secret123").split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2_sha256", parts[0])
        assertEquals("310000", parts[1])
    }

    @Test
    fun `malformed stored value is rejected`() {
        assertFalse(PasswordHasher.verify("secret123", "garbage"))
        assertFalse(PasswordHasher.verify("secret123", "pbkdf2_sha256\$abc\$xx\$yy"))
    }

    @Test
    fun `legacy hash is recognized and verified only for the matching password`() {
        val legacy = "hashed_secret123"
        assertTrue(PasswordHasher.isLegacyHash(legacy))
        assertTrue(PasswordHasher.verifyLegacy("secret123", legacy))
        assertFalse(PasswordHasher.verifyLegacy("other", legacy))
        assertFalse(PasswordHasher.isLegacyHash(PasswordHasher.hash("secret123")))
    }
}
