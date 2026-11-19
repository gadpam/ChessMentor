// domain/repository/SessionRepository.kt
package com.example.chessmentor.domain.repository

import com.example.chessmentor.domain.session.AppThemeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Хранилище локальной сессии пользователя и общих параметров приложения.
 * Никогда не сохраняет пароли или хеши — только идентификатор авторизованного пользователя,
 * последний использованный email для быстрого повторного входа, тему оформления
 * и никнейм на шахматных платформах для автоопределения цвета в PGN.
 */
interface SessionRepository {

    /** Реактивный режим цветовой темы приложения (Системная / Светлая / Тёмная). */
    val themeMode: StateFlow<AppThemeMode>

    /** ID активного авторизованного пользователя или `null`, если выполнен выход. */
    fun getActiveUserId(): Long?

    /** Сохранить активную сессию пользователя после входа или регистрации. */
    fun saveActiveSession(userId: Long, email: String)

    /** Завершить текущую сессию (сохраняя последний email для удобства повторного входа). */
    fun clearActiveSession()

    /** Последний email, с которого выполнялся вход на этом устройстве. */
    fun getLastLoginEmail(): String

    /** Обновить сохранённый режим темы приложения. */
    fun setThemeMode(mode: AppThemeMode)

    /** Получить сохранённый ник игрока на Lichess / Chess.com для автоопределения цвета. */
    fun getChessUsernameHint(userId: Long): String?

    /** Сохранить ник игрока на Lichess / Chess.com для данного пользователя. */
    fun saveChessUsernameHint(userId: Long, hint: String)
}
