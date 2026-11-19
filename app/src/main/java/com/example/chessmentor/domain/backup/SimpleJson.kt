// domain/backup/SimpleJson.kt
package com.example.chessmentor.domain.backup

import java.util.Locale

/**
 * Компактный, самодостаточный сериализатор и парсер RFC 8259 JSON на чистом JVM
 * без сторонних библиотек и без зависимости от `org.json` из Android SDK.
 *
 * Защищён ограничениями на размер входного документа и глубину вложенности,
 * корректно обрабатывает UTF-8, кириллицу, суррогатные пары и escape-последовательности `\uXXXX`.
 */
object SimpleJson {

    /** Максимальный размер разбираемого JSON-файла (16 МБ символов). */
    const val MAX_INPUT_CHARS = 16 * 1024 * 1024

    /** Максимальная глубина вложенности объектов и массивов. */
    const val MAX_NESTING_DEPTH = 32

    class JsonFormatException(message: String) : IllegalArgumentException(message)

    sealed class Value {
        data class Obj(val fields: LinkedHashMap<String, Value>) : Value() {
            operator fun get(key: String): Value? = fields[key]
            fun stringOrNull(key: String): String? = (fields[key] as? Str)?.value
            fun requireString(key: String): String =
                stringOrNull(key) ?: throw JsonFormatException("Отсутствует строковое поле '$key'")
            fun intOrNull(key: String): Int? = (fields[key] as? Num)?.toIntExact()
            fun requireInt(key: String): Int =
                intOrNull(key) ?: throw JsonFormatException("Отсутствует целочисленное поле '$key'")
            fun longOrNull(key: String): Long? = (fields[key] as? Num)?.toLongExact()
            fun requireLong(key: String): Long =
                longOrNull(key) ?: throw JsonFormatException("Отсутствует числовое поле '$key'")
            fun doubleOrNull(key: String): Double? = (fields[key] as? Num)?.toDoubleSafe()
            fun boolOrNull(key: String): Boolean? = (fields[key] as? Bool)?.value
            fun objOrNull(key: String): Obj? = fields[key] as? Obj
            fun arrOrEmpty(key: String): List<Value> = (fields[key] as? Arr)?.items ?: emptyList()
        }

        data class Arr(val items: List<Value>) : Value()
        data class Str(val value: String) : Value()
        data class Num(val raw: String) : Value() {
            fun toLongExact(): Long? = raw.toLongOrNull()
                ?: try {
                    raw.toBigDecimalOrNull()?.longValueExact()
                } catch (_: ArithmeticException) {
                    null // Non-integral or outside the Long range.
                }

            fun toIntExact(): Int? = toLongExact()
                ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                ?.toInt()

            fun toDoubleSafe(): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        data class Bool(val value: Boolean) : Value()
        data object Null : Value()
    }

    // ==================== ПОСТРОЕНИЕ ДЕРЕВА ====================

    fun obj(vararg pairs: Pair<String, Value>): Value.Obj {
        val map = LinkedHashMap<String, Value>(pairs.size)
        for ((k, v) in pairs) {
            map[k] = v
        }
        return Value.Obj(map)
    }

    fun arr(items: List<Value>): Value.Arr = Value.Arr(items)
    fun str(v: String?): Value = if (v == null) Value.Null else Value.Str(v)
    fun num(v: Int?): Value = if (v == null) Value.Null else Value.Num(v.toString())
    fun num(v: Long?): Value = if (v == null) Value.Null else Value.Num(v.toString())
    fun num(v: Double?): Value = when {
        v == null || !v.isFinite() -> Value.Null
        else -> Value.Num(String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" })
    }
    fun bool(v: Boolean?): Value = if (v == null) Value.Null else Value.Bool(v)

    // ==================== СЕРИАЛИЗАЦИЯ ====================

    /** Сериализует [value] в компактный детерминированный JSON (подходит и для расчёта SHA-256). */
    fun stringify(value: Value, pretty: Boolean = false): String {
        val sb = StringBuilder(1024)
        writeValue(sb, value, pretty = pretty, indent = 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Value, pretty: Boolean, indent: Int) {
        when (value) {
            is Value.Null -> sb.append("null")
            is Value.Bool -> sb.append(if (value.value) "true" else "false")
            is Value.Num -> sb.append(value.raw)
            is Value.Str -> writeEscapedString(sb, value.value)
            is Value.Arr -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                val childIndent = indent + 1
                value.items.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    if (pretty) appendNewlineIndent(sb, childIndent)
                    writeValue(sb, item, pretty, childIndent)
                }
                if (pretty) appendNewlineIndent(sb, indent)
                sb.append(']')
            }
            is Value.Obj -> {
                if (value.fields.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                val childIndent = indent + 1
                var idx = 0
                for ((k, v) in value.fields) {
                    if (idx++ > 0) sb.append(',')
                    if (pretty) appendNewlineIndent(sb, childIndent)
                    writeEscapedString(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, v, pretty, childIndent)
                }
                if (pretty) appendNewlineIndent(sb, indent)
                sb.append('}')
            }
        }
    }

    private fun appendNewlineIndent(sb: StringBuilder, level: Int) {
        sb.append('\n')
        repeat(level * 2) { sb.append(' ') }
    }

    private fun writeEscapedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    // ==================== ПАРСИНГ ====================

    fun parse(input: String): Value {
        val cleaned = input.removePrefix("\uFEFF")
        if (cleaned.length > MAX_INPUT_CHARS) {
            throw JsonFormatException("Файл резервной копии превышает допустимый размер (16 МБ)")
        }
        val parser = Parser(cleaned)
        val root = parser.readValue(depth = 0)
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw JsonFormatException("Лишние символы после конца JSON на позиции ${parser.pos}")
        }
        return root
    }

    fun parseObject(input: String): Value.Obj =
        parse(input) as? Value.Obj
            ?: throw JsonFormatException("Корневой элемент JSON должен быть объектом")

    private class Parser(private val s: String) {
        var pos: Int = 0
        private val len: Int = s.length

        fun isAtEnd(): Boolean = pos >= len

        fun skipWhitespace() {
            while (pos < len) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Value {
            if (depth > MAX_NESTING_DEPTH) {
                throw JsonFormatException("Превышена допустимая глубина вложенности JSON ($MAX_NESTING_DEPTH)")
            }
            skipWhitespace()
            if (pos >= len) {
                throw JsonFormatException("Неожиданный конец JSON")
            }
            return when (val ch = s[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> Value.Str(readString())
                't' -> readLiteral("true", Value.Bool(true))
                'f' -> readLiteral("false", Value.Bool(false))
                'n' -> readLiteral("null", Value.Null)
                '-', in '0'..'9' -> readNumber()
                else -> throw JsonFormatException("Недопустимый символ '$ch' на позиции $pos")
            }
        }

        private fun readObject(depth: Int): Value.Obj {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Value>()
            if (pos < len && s[pos] == '}') {
                pos++
                return Value.Obj(map)
            }
            while (true) {
                skipWhitespace()
                if (pos >= len || s[pos] != '"') {
                    throw JsonFormatException("Ожидался ключ объекта в кавычках на позиции $pos")
                }
                val key = readString()
                skipWhitespace()
                if (pos >= len || s[pos] != ':') {
                    throw JsonFormatException("Ожидалось двоеточие после ключа '$key' на позиции $pos")
                }
                pos++ // ':'
                val value = readValue(depth)
                map[key] = value
                skipWhitespace()
                if (pos >= len) {
                    throw JsonFormatException("Незакрытый объект JSON")
                }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Value.Obj(map)
                    }
                    else -> throw JsonFormatException("Ожидалась ',' или '}' на позиции $pos")
                }
            }
        }

        private fun readArray(depth: Int): Value.Arr {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Value>()
            if (pos < len && s[pos] == ']') {
                pos++
                return Value.Arr(list)
            }
            while (true) {
                list.add(readValue(depth))
                skipWhitespace()
                if (pos >= len) {
                    throw JsonFormatException("Незакрытый массив JSON")
                }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Arr(list)
                    }
                    else -> throw JsonFormatException("Ожидалась ',' или ']' на позиции $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < len) {
                val ch = s[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= len) throw JsonFormatException("Незавершённая escape-последовательность")
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) {
                                    throw JsonFormatException("Неполная \\uXXXX-последовательность на позиции $pos")
                                }
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.takeIf { digits -> digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
                                    ?.toInt(16)
                                    ?: throw JsonFormatException("Некорректный код \\u$hex")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw JsonFormatException("Неизвестная escape-последовательность '\\$esc'")
                        }
                    }
                    ch.code < 0x20 -> throw JsonFormatException("Неэкранированный управляющий символ в строке JSON")
                    else -> sb.append(ch)
                }
            }
            throw JsonFormatException("Незакрытая строковая константа JSON")
        }

        private fun readLiteral(expected: String, result: Value): Value {
            if (!s.startsWith(expected, pos)) {
                throw JsonFormatException("Ожидался литерал '$expected' на позиции $pos")
            }
            pos += expected.length
            return result
        }

        private fun readNumber(): Value.Num {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= len || s[pos] !in '0'..'9') {
                throw JsonFormatException("Некорректное число на позиции $start")
            }
            if (s[pos] == '0') {
                pos++
            } else {
                while (pos < len && s[pos] in '0'..'9') pos++
            }
            if (pos < len && s[pos] == '.') {
                pos++
                if (pos >= len || s[pos] !in '0'..'9') {
                    throw JsonFormatException("Некорректная дробная часть числа на позиции $start")
                }
                while (pos < len && s[pos] in '0'..'9') pos++
            }
            if (pos < len && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < len && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= len || s[pos] !in '0'..'9') {
                    throw JsonFormatException("Некорректная экспонента числа на позиции $start")
                }
                while (pos < len && s[pos] in '0'..'9') pos++
            }
            val raw = s.substring(start, pos)
            val d = raw.toDoubleOrNull()
            if (d == null || !d.isFinite()) {
                throw JsonFormatException("Число выходит за допустимые пределы: $raw")
            }
            return Value.Num(raw)
        }
    }
}
