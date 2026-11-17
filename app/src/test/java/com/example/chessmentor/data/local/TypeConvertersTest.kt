package com.example.chessmentor.data.local

import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeConvertersTest {

    private val converters = TypeConverters()

    @Test
    fun stringList_roundTripsSpecialCharactersQuotesAndCommas() {
        val original = listOf("e2e4", "WITH_CHECK,FAMILY_FORK", "note \"quoted\" \\ line\n2")
        val encoded = converters.fromStringList(original)
        val decoded = converters.toStringList(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun stringList_handlesLegacyCommaSeparatedAndEmptyValues() {
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), converters.toStringList("e2e4,e7e5,g1f3"))
        assertEquals(emptyList<String>(), converters.toStringList(""))
        assertEquals(emptyList<String>(), converters.toStringList("[]"))
        assertNull(converters.fromStringList(null))
    }

    @Test
    fun enumConverters_fallBackGracefullyOnUnknownValues() {
        assertEquals(TacticalPattern.FORK_KNIGHT, converters.toTacticalPattern("FORK_KNIGHT"))
        assertEquals(TacticalPattern.MIXED, converters.toTacticalPattern("NON_EXISTENT_PATTERN"))
        assertEquals(TacticalTheme.MATE, converters.toTacticalTheme("MATE"))
        assertEquals(TacticalTheme.TACTIC, converters.toTacticalTheme(""))
    }
}
