// domain/training/TrainingMode.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalTheme

/**
 * Режимы тактической тренировки.
 */
sealed class TrainingMode {
    /** Адаптивный подбор: повтор ошибок + задачи из партий + встроенный банк. */
    data object All : TrainingMode()

    /** Только задачи, сгенерированные из собственных партий пользователя. */
    data object MyMistakes : TrainingMode()

    /** Прицельная отработка паттернов с наименьшим процентом решений. */
    data object WeakPatterns : TrainingMode()

    /** Ежедневная задача (единая для текущей даты). */
    data object Daily : TrainingMode()

    /** Тренировка по выбранной тактической теме. */
    data class ByTheme(val theme: TacticalTheme) : TrainingMode()

    val displayName: String
        get() = when (this) {
            All -> "Все задачи"
            MyMistakes -> "Мои ошибки"
            WeakPatterns -> "Слабые темы"
            Daily -> "Задача дня"
            is ByTheme -> TrainingLabels.themeDisplayName(theme)
        }

    val routeKey: String
        get() = when (this) {
            All -> "all"
            MyMistakes -> "mistakes"
            WeakPatterns -> "weak"
            Daily -> "daily"
            is ByTheme -> "theme_${theme.name}"
        }

    companion object {
        fun fromRouteKey(key: String?): TrainingMode {
            if (key.isNullOrBlank()) return All
            return when (key) {
                "all" -> All
                "mistakes" -> MyMistakes
                "weak" -> WeakPatterns
                "daily" -> Daily
                else -> {
                    if (key.startsWith("theme_")) {
                        val themeName = key.removePrefix("theme_")
                        val theme = TacticalTheme.entries.firstOrNull { it.name == themeName }
                        if (theme != null && theme != TacticalTheme.UNKNOWN) ByTheme(theme) else All
                    } else {
                        All
                    }
                }
            }
        }

        val selectableThemes: List<TacticalTheme> = listOf(
            TacticalTheme.MATE,
            TacticalTheme.FORK,
            TacticalTheme.PIN,
            TacticalTheme.DISCOVERED_ATTACK,
            TacticalTheme.TACTIC,
            TacticalTheme.MATERIAL_LOSS,
            TacticalTheme.KING_SAFETY
        )
    }
}

/**
 * Человекочитаемые подписи для тем, сложности и источника задач.
 */
object TrainingLabels {
    fun themeDisplayName(theme: TacticalTheme): String = when (theme) {
        TacticalTheme.MATE -> "Мат"
        TacticalTheme.FORK -> "Двойной удар"
        TacticalTheme.PIN -> "Связка и рентген"
        TacticalTheme.DISCOVERED_ATTACK -> "Вскрытая атака"
        TacticalTheme.MATERIAL_LOSS -> "Выигрыш материала"
        TacticalTheme.KING_SAFETY -> "Защита короля"
        TacticalTheme.TACTIC -> "Комбинации"
        TacticalTheme.UNKNOWN -> "Тактика"
    }

    fun difficultyLabel(difficultyRating: Int): String = when {
        difficultyRating < 35 -> "Лёгкая"
        difficultyRating < 65 -> "Средняя"
        else -> "Сложная"
    }

    fun sourceLabel(exercise: Exercise, isDailyMode: Boolean = false): String {
        if (isDailyMode) return "Задача дня"
        return when {
            exercise.sourceGameId != null -> {
                val moveSuffix = exercise.sourceMoveNumber?.let { " • ход $it" }.orEmpty()
                "Из вашей партии$moveSuffix"
            }
            ExerciseMetadata.extractCuratedId(exercise) != null -> "Тактический практикум"
            exercise.createdFrom == ExerciseSource.MISTAKE -> "Разбор ошибки"
            else -> "Тактическая задача"
        }
    }
}

/**
 * Кодирование/декодирование вспомогательных метаданных упражнения в `patternFeatures`
 * (без изменения схемы Room).
 */
object ExerciseMetadata {
    const val PREFIX_CURATED_ID = "CURATED_ID:"
    const val PREFIX_TITLE = "TITLE:"
    const val PREFIX_PREV_MOVE = "PREV_MOVE:"
    const val PREFIX_PRE_FEN = "PRE_FEN:"
    const val PREFIX_GOAL = "GOAL:"

    const val GOAL_DEFENSE = "DEFENSE"

    fun curatedIdTag(id: String): String = "$PREFIX_CURATED_ID$id"
    fun titleTag(title: String): String = "$PREFIX_TITLE$title"
    fun prevMoveTag(uci: String): String = "$PREFIX_PREV_MOVE${uci.trim().lowercase()}"
    fun preFenTag(fen: String): String = "$PREFIX_PRE_FEN${fen.trim()}"
    fun defensiveGoalTag(): String = "$PREFIX_GOAL$GOAL_DEFENSE"

    fun extractCuratedId(exercise: Exercise): String? =
        extractValue(exercise.patternFeatures, PREFIX_CURATED_ID)

    fun extractTitle(exercise: Exercise): String? =
        extractValue(exercise.patternFeatures, PREFIX_TITLE)

    fun extractPrevMove(exercise: Exercise): String? =
        extractValue(exercise.patternFeatures, PREFIX_PREV_MOVE)

    fun extractPreFen(exercise: Exercise): String? =
        extractValue(exercise.patternFeatures, PREFIX_PRE_FEN)

    fun isDefensiveGoal(exercise: Exercise): Boolean =
        extractValue(exercise.patternFeatures, PREFIX_GOAL) == GOAL_DEFENSE ||
            exercise.tacticalPattern == com.example.chessmentor.domain.entity.TacticalPattern.DEFENSIVE_MOVE ||
            exercise.tacticalPattern == com.example.chessmentor.domain.entity.TacticalPattern.COUNTER_ATTACK ||
            exercise.tacticalPattern == com.example.chessmentor.domain.entity.TacticalPattern.PERPETUAL_CHECK ||
            exercise.tacticalPattern == com.example.chessmentor.domain.entity.TacticalPattern.STALEMATE_TRICK

    private fun extractValue(features: List<String>, prefix: String): String? =
        features.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotBlank() }
}
