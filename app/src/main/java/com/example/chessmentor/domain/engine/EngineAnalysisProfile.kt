// domain/engine/EngineAnalysisProfile.kt
package com.example.chessmentor.domain.engine

import com.example.chessmentor.domain.entity.EngineSettings

/**
 * Единые согласованные профили глубины поиска Stockfish по всей системе:
 * разбор партии, свободный анализ в тренажёре, проверка ходов и генерация задач.
 */
object EngineAnalysisProfile {
    /** Глубина интерактивного анализа на доске (разбор партии и свободный анализ в тренажёре). */
    const val INTERACTIVE_ANALYSIS_DEPTH = 13

    /** Верхняя граница для интерактивного анализа, чтобы UI оставался отзывчивым при глубоких настройках. */
    const val MAX_INTERACTIVE_DEPTH = 14

    /** Глубина быстрой проверки альтернативных/допустимых ходов игрока (Retry / Тренажёр). */
    const val MOVE_VERIFICATION_DEPTH = 10

    /** Глубина достройки длинной линии решения (генерация и автопоиск). */
    const val PUZZLE_GENERATION_DEPTH = 12

    /** Глубина оценки однозначности лучшего хода (1-й vs 2-й ход). */
    const val CLARITY_CHECK_DEPTH = 8

    /** Максимальное число полуходов главной линии (PV) для показа на доске. */
    const val MAX_PV_PLIES = 7

    /**
     * Подбирает глубину интерактивного анализа с учётом пользовательских настроек:
     * если пользователь выбрал быстрый профиль (12), уважает 12; если глубокий (15–25),
     * ограничивает разумным порогом отзывчивости [MAX_INTERACTIVE_DEPTH].
     */
    fun interactiveDepth(settings: EngineSettings? = null): Int {
        val configured = settings?.depth ?: INTERACTIVE_ANALYSIS_DEPTH
        return configured.coerceIn(MOVE_VERIFICATION_DEPTH, MAX_INTERACTIVE_DEPTH)
    }
}
