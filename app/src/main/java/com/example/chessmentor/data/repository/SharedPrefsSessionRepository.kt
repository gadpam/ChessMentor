// data/repository/SharedPrefsSessionRepository.kt
package com.example.chessmentor.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.chessmentor.domain.repository.SessionRepository
import com.example.chessmentor.domain.session.AppThemeMode
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Реализация [SessionRepository] поверх локального приватного `SharedPreferences`.
 *
 * Пароли и хеши не сохраняются ни в каком виде. Файл `user_session.xml` исключён из
 * облачного автобэкапа (аналогично базе данных Room), чтобы на новом устройстве не
 * возникала ссылка на несуществующий `userId`.
 */
class SharedPrefsSessionRepository(context: Context) : SessionRepository {

    companion object {
        const val PREFS_NAME = "user_session"

        private const val KEY_ACTIVE_USER_ID = "active_user_id"
        private const val KEY_LAST_LOGIN_EMAIL = "last_login_email"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"
        private const val KEY_USERNAME_HINT_PREFIX = "chess_username_hint_"
        private const val NO_USER_ID = -1L
    }

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _themeMode = MutableStateFlow(
        AppThemeMode.fromStorageKey(prefs.getString(KEY_APP_THEME_MODE, null))
    )
    override val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    override fun getActiveUserId(): Long? {
        if (!prefs.contains(KEY_ACTIVE_USER_ID)) return null
        val id = prefs.getLong(KEY_ACTIVE_USER_ID, NO_USER_ID)
        return id.takeIf { it > 0L }
    }

    override fun saveActiveSession(userId: Long, email: String) {
        if (userId <= 0L) return
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        prefs.edit()
            .putLong(KEY_ACTIVE_USER_ID, userId)
            .apply {
                if (normalizedEmail.isNotEmpty()) {
                    putString(KEY_LAST_LOGIN_EMAIL, normalizedEmail)
                }
            }
            .apply()
    }

    override fun clearActiveSession() {
        prefs.edit()
            .remove(KEY_ACTIVE_USER_ID)
            .apply()
    }

    override fun getLastLoginEmail(): String =
        prefs.getString(KEY_LAST_LOGIN_EMAIL, "")?.trim().orEmpty()

    override fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit()
            .putString(KEY_APP_THEME_MODE, mode.storageKey)
            .apply()
    }

    override fun getChessUsernameHint(userId: Long): String? {
        if (userId <= 0L) return null
        return prefs.getString("$KEY_USERNAME_HINT_PREFIX$userId", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun saveChessUsernameHint(userId: Long, hint: String) {
        if (userId <= 0L) return
        val key = "$KEY_USERNAME_HINT_PREFIX$userId"
        val cleaned = hint.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(key) else putString(key, cleaned)
        }.apply()
    }
}
