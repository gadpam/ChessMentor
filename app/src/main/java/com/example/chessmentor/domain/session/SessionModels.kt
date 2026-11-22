// domain/session/SessionModels.kt
package com.example.chessmentor.domain.session

import com.example.chessmentor.domain.entity.User
import java.util.Locale

/**
 * Режим цветовой темы приложения.
 */
enum class AppThemeMode(
    val storageKey: String,
    val label: String
) {
    SYSTEM("system", "Системная"),
    LIGHT("light", "Светлая"),
    DARK("dark", "Тёмная");

    companion object {
        fun fromStorageKey(raw: String?): AppThemeMode {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT) ?: return DARK
            return entries.firstOrNull { it.storageKey == normalized } ?: DARK
        }
    }
}

/**
 * Состояние пользовательской сессии при запуске и работе приложения.
 */
sealed interface SessionState {
    /** Идёт фоновая проверка сохранённой сессии в Room при холодном старте. */
    data object Restoring : SessionState

    /** Активной сессии нет — показываем экран входа / регистрации. */
    data object Unauthenticated : SessionState

    /** Пользователь авторизован. */
    data class Authenticated(val user: User) : SessionState
}
