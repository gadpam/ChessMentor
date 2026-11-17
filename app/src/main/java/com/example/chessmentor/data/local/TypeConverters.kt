// data/local/TypeConverters.kt
package com.example.chessmentor.data.local

import androidx.room.TypeConverter
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme

class TypeConverters {

    // ============================================================
    // LIST CONVERTERS (JSON-массив с обратной совместимостью с CSV)
    // ============================================================

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        if (value == null) return null
        if (value.isEmpty()) return "[]"
        return value.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
            buildString(item.length + 2) {
                append('"')
                for (ch in item) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value == null) return emptyList()
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return parseJsonStringArray(trimmed)
        }

        // Обратная совместимость со старыми записями через запятую
        return trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseJsonStringArray(json: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false

        for (i in 1 until json.length - 1) {
            val ch = json[i]
            if (escaped) {
                when (ch) {
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    't' -> current.append('\t')
                    else -> current.append(ch)
                }
                escaped = false
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escaped = true
                    '"' -> {
                        result.add(current.toString())
                        current.setLength(0)
                        inString = false
                    }
                    else -> current.append(ch)
                }
            } else if (ch == '"') {
                inString = true
            }
        }
        return result
    }

    // ============================================================
    // SKILL LEVEL
    // ============================================================

    @TypeConverter
    fun fromSkillLevel(value: SkillLevel?): String? = value?.name

    @TypeConverter
    fun toSkillLevel(value: String?): SkillLevel? = value?.let {
        try { SkillLevel.valueOf(it) } catch (e: Exception) { null }
    }

    // ============================================================
    // CHESS COLOR
    // ============================================================

    @TypeConverter
    fun fromChessColor(value: ChessColor?): String? = value?.name

    @TypeConverter
    fun toChessColor(value: String?): ChessColor? = value?.let {
        try { ChessColor.valueOf(it) } catch (e: Exception) { null }
    }

    // ============================================================
    // ANALYSIS STATUS
    // ============================================================

    @TypeConverter
    fun fromAnalysisStatus(value: AnalysisStatus?): String? = value?.name

    @TypeConverter
    fun toAnalysisStatus(value: String?): AnalysisStatus? = value?.let {
        try { AnalysisStatus.valueOf(it) } catch (e: Exception) { null }
    }

    // ============================================================
    // MISTAKE TYPE
    // ============================================================

    @TypeConverter
    fun fromMistakeType(value: MistakeType?): String? = value?.name

    @TypeConverter
    fun toMistakeType(value: String?): MistakeType? = value?.let {
        try { MistakeType.valueOf(it) } catch (e: Exception) { null }
    }

    // ============================================================
    // MOVE QUALITY
    // ============================================================

    @TypeConverter
    fun fromMoveQuality(quality: MoveQuality): String = quality.name

    @TypeConverter
    fun toMoveQuality(value: String): MoveQuality {
        return try { MoveQuality.valueOf(value) } catch (e: Exception) { MoveQuality.GOOD }
    }

    // ============================================================
    // TACTICAL PATTERN
    // ============================================================

    @TypeConverter
    fun fromTacticalPattern(value: TacticalPattern?): String? = value?.name

    @TypeConverter
    fun toTacticalPattern(value: String?): TacticalPattern? {
        if (value == null) return null
        if (value.isBlank()) return TacticalPattern.MIXED
        return try {
            TacticalPattern.valueOf(value)
        } catch (e: Exception) {
            TacticalPattern.MIXED
        }
    }

    // ============================================================
    // TACTICAL THEME
    // ============================================================

    @TypeConverter
    fun fromTacticalTheme(value: TacticalTheme?): String? = value?.name

    @TypeConverter
    fun toTacticalTheme(value: String?): TacticalTheme? {
        if (value == null) return null
        if (value.isBlank()) return TacticalTheme.TACTIC
        return try {
            TacticalTheme.valueOf(value)
        } catch (e: Exception) {
            TacticalTheme.TACTIC
        }
    }

    // ============================================================
    // EXERCISE SOURCE
    // ============================================================

    @TypeConverter
    fun fromExerciseSource(value: ExerciseSource?): String? = value?.name

    @TypeConverter
    fun toExerciseSource(value: String?): ExerciseSource? {
        if (value == null) return null
        if (value.isBlank()) return ExerciseSource.MISTAKE
        return try {
            ExerciseSource.valueOf(value)
        } catch (e: Exception) {
            ExerciseSource.MISTAKE
        }
    }
}
