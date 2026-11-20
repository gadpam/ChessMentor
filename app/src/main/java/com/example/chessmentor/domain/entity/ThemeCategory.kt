package com.example.chessmentor.domain.entity

/**
 * Категория шахматной темы
 */
enum class ThemeCategory {
    TACTICS,        // Тактика
    STRATEGY,       // Стратегия
    OPENING,        // Дебют
    ENDGAME,        // Эндшпиль
    CALCULATION,    // Расчёт вариантов
    KING_SAFETY;    // Безопасность короля

    /**
     * Название на русском
     */
    fun getDisplayName(): String = when (this) {
        TACTICS -> "Тактика"
        STRATEGY -> "Стратегия"
        OPENING -> "Дебют"
        ENDGAME -> "Эндшпиль"
        CALCULATION -> "Расчёт"
        KING_SAFETY -> "Безопасность короля"
    }

    /**
     * Описание категории
     */
    fun getDescription(): String = when (this) {
        TACTICS -> "Комбинации и тактические приёмы"
        STRATEGY -> "Стратегическое планирование и позиционная игра"
        OPENING -> "Дебютные принципы и теория"
        ENDGAME -> "Техника эндшпиля"
        CALCULATION -> "Расчёт вариантов и оценка позиции"
        KING_SAFETY -> "Безопасность короля и атака на короля"
    }
}