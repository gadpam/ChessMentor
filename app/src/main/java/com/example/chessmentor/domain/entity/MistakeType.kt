// domain/entity/MistakeType.kt
package com.example.chessmentor.domain.entity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class MistakeType {
    BLUNDER,
    MISTAKE,
    INACCURACY;

    fun getDisplayName(): String = when (this) {
        BLUNDER -> "Зевок"
        MISTAKE -> "Ошибка"
        INACCURACY -> "Неточность"
    }

    fun getEmoji(): String = when (this) {
        BLUNDER -> "??"
        MISTAKE -> "?"
        INACCURACY -> "?!"
    }

    fun getIcon(): ImageVector = when (this) {
        BLUNDER -> Icons.Default.Cancel
        MISTAKE -> Icons.Default.Error
        INACCURACY -> Icons.Default.Warning
    }
}