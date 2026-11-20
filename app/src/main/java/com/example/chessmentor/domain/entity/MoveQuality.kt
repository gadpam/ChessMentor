package com.example.chessmentor.domain.entity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class MoveQuality {
    BRILLIANT,
    GREAT_MOVE,
    BEST_MOVE,
    EXCELLENT,
    GOOD,
    BOOK,
    INACCURACY,
    MISTAKE,
    BLUNDER,
    MISSED_WIN;

    fun getDisplayName(): String = when (this) {
        BRILLIANT -> "Блестящий ход"
        GREAT_MOVE -> "Отличный ход"
        BEST_MOVE -> "Лучший ход"
        EXCELLENT -> "Превосходный"
        GOOD -> "Хороший ход"
        BOOK -> "Теория"
        INACCURACY -> "Неточность"
        MISTAKE -> "Ошибка"
        BLUNDER -> "Зевок"
        MISSED_WIN -> "Упущенная победа"
    }

    fun getIcon(): ImageVector = when (this) {
        BRILLIANT -> Icons.Default.Diamond
        GREAT_MOVE -> Icons.Default.ThumbUp
        BEST_MOVE -> Icons.Default.Star
        EXCELLENT -> Icons.Default.CheckCircle
        GOOD -> Icons.Default.Check
        BOOK -> Icons.Default.MenuBook
        INACCURACY -> Icons.Default.Warning
        MISTAKE -> Icons.Default.Error
        BLUNDER -> Icons.Default.Cancel
        MISSED_WIN -> Icons.Default.HeartBroken
    }
    fun isGood(): Boolean = this in listOf(BRILLIANT, GREAT_MOVE, BEST_MOVE, EXCELLENT, GOOD, BOOK)

    fun isBad(): Boolean = this in listOf(INACCURACY, MISTAKE, BLUNDER, MISSED_WIN)
}
