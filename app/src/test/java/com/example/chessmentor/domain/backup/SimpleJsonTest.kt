package com.example.chessmentor.domain.backup

import org.junit.Assert.*
import org.junit.Test

class SimpleJsonTest {
    private fun rejects(vararg inputs: String) = inputs.forEach { input ->
        assertThrows(input.take(60), SimpleJson.JsonFormatException::class.java) { SimpleJson.parse(input) }
    }

    @Test fun objectSeparatorsAndQuotedKeysAreRequired() = rejects(
        "{a:1}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "{\"a\":1,}", "{\"a\":1"
    )

    @Test fun incompleteArraysAndMultipleRootValuesAreRejected() = rejects(
        "[", "[null", "[1 2]", "[1,]", "{}[]", "true false", "nullx"
    )

    @Test fun numbersRequireJsonSyntaxAndFiniteValues() = rejects(
        "-", "+1", "01", "-.5", "1.", "1e", "1e-", "1e999", "NaN"
    )

    @Test fun invalidEscapesAndUnescapedControlsAreRejected() = rejects(
        "\"\\x41\"", "\"\\u0\"", "\"\\uZZZZ\"", "\"\\u+041\"", "\"line\nbreak\"", "\"unfinished"
    )

    @Test fun unicodePairAndEscapedControlsSurviveSerialization() {
        val input = """{"s":"\uD83D\uDE00\b\f\n\r\t\u0000\/"}"""
        val obj = SimpleJson.parseObject(input)
        assertEquals("😀\b\u000c\n\r\t\u0000/", obj.requireString("s"))
        assertEquals(obj, SimpleJson.parseObject(SimpleJson.stringify(obj, pretty = true)))
    }

    @Test fun depthLimitAcceptsBoundaryAndRejectsOneLevelMore() {
        fun nested(n: Int) = "[".repeat(n) + "true" + "]".repeat(n)
        val boundary = nested(SimpleJson.MAX_NESTING_DEPTH)
        assertEquals(boundary, SimpleJson.stringify(SimpleJson.parse(boundary)))
        rejects(nested(SimpleJson.MAX_NESTING_DEPTH + 1))
    }

    @Test fun exactIntegerFieldsDoNotRoundOrSaturate() {
        fun value(raw: String) = SimpleJson.parseObject("{\"id\":$raw}").longOrNull("id")
        assertEquals(Long.MAX_VALUE, value("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, value("-9223372036854775808"))
        assertEquals(9007199254740993L, value("9007199254740993.0"))
        assertEquals(12L, value("1.2e1"))
        for (raw in listOf("9223372036854775808", "-9223372036854775809", "1.0000000000000001", "1e-400")) {
            assertNull(raw, value(raw))
        }
    }

    @Test fun integerFieldsRejectWrongTypesAndIntOverflow() {
        val obj = SimpleJson.parseObject("""{"string":"12","large":2147483648,"ok":2147483647}""")
        assertEquals(Int.MAX_VALUE, obj.requireInt("ok"))
        for (key in listOf("large", "string", "missing")) {
            assertThrows(SimpleJson.JsonFormatException::class.java) { obj.requireInt(key) }
        }
    }
}
