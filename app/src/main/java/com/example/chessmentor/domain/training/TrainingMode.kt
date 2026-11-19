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

    /** Структурированная осознанная тренировка из 10 задач (разминка → повтор → чередование → защита → расчёт). */
    data object SmartSession : TrainingMode()

    /** Только задачи, сгенерированные из собственных партий пользователя. */
    data object MyMistakes : TrainingMode()

    /** Прицельная отработка паттернов с наименьшим процентом решений и интервальный повтор. */
    data object WeakPatterns : TrainingMode()

    /** Быстрое распознавание типовых тактических мотивов (Система 1 / метод «Дятла»). */
    data object PatternVision : TrainingMode()

    /** Глубокий расчёт многоходовых форсированных вариантов до первого хода (Система 2). */
    data object DeepCalculation : TrainingMode()

    /** Тренировка защиты, профилактики, встречных ударов и спасения трудных позиций. */
    data object Defense : TrainingMode()

    /** Ежедневная задача (единая для текущей даты). */
    data object Daily : TrainingMode()

    /** Тренировка по выбранной тактической теме. */
    data class ByTheme(val theme: TacticalTheme) : TrainingMode()

    /**
     * Прицельная отработка ошибок конкретной проанализированной партии
     * (мост «Разбор партии → Тренировка»). Если задан [focusMoveNumber], первой
     * открывается задача именно на этот ход, а далее — остальные ошибки той же партии.
     */
    data class FromGame(
        val gameId: Long,
        val focusMoveNumber: Int? = null
    ) : TrainingMode()

    val displayName: String
        get() = when (this) {
            All -> "Все задачи"
            SmartSession -> "Сессия 10"
            MyMistakes -> "Мои ошибки"
            WeakPatterns -> "Слабые темы"
            PatternVision -> "Зоркость"
            DeepCalculation -> "Расчёт"
            Defense -> "Защита"
            Daily -> "Задача дня"
            is ByTheme -> TrainingLabels.themeDisplayName(theme)
            is FromGame -> if (focusMoveNumber != null) {
                "Из партии (ход $focusMoveNumber)"
            } else {
                "Ошибки партии"
            }
        }

    val routeKey: String
        get() = when (this) {
            All -> "all"
            SmartSession -> "smart_session"
            MyMistakes -> "mistakes"
            WeakPatterns -> "weak"
            PatternVision -> "vision"
            DeepCalculation -> "calculation"
            Defense -> "defense"
            Daily -> "daily"
            is ByTheme -> "theme_${theme.name}"
            is FromGame -> if (focusMoveNumber != null && focusMoveNumber > 0) {
                "game_${gameId}_move_${focusMoveNumber}"
            } else {
                "game_${gameId}"
            }
        }

    companion object {
        private val FROM_GAME_REGEX = Regex("""^game_(\d+)(?:_move_(\d+))?$""")

        fun fromRouteKey(key: String?): TrainingMode {
            if (key.isNullOrBlank()) return All
            return when (key) {
                "all" -> All
                "smart_session" -> SmartSession
                "mistakes" -> MyMistakes
                "weak" -> WeakPatterns
                "vision" -> PatternVision
                "calculation" -> DeepCalculation
                "defense" -> Defense
                "daily" -> Daily
                else -> {
                    when {
                        key.startsWith("theme_") -> {
                            val themeName = key.removePrefix("theme_")
                            val theme = TacticalTheme.entries.firstOrNull { it.name == themeName }
                            if (theme != null && theme != TacticalTheme.UNKNOWN) ByTheme(theme) else All
                        }
                        key.startsWith("game_") -> {
                            val m = FROM_GAME_REGEX.matchEntire(key)
                            val gameId = m?.groupValues?.get(1)?.toLongOrNull()
                            val moveNo = m?.groupValues?.getOrNull(2)
                                ?.takeIf { it.isNotEmpty() }
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 }
                            if (gameId != null && gameId > 0L) {
                                FromGame(gameId = gameId, focusMoveNumber = moveNo)
                            } else {
                                All
                            }
                        }
                        else -> All
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
