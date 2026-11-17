// data/repository/SharedPrefsEngineSettingsRepository.kt
package com.example.chessmentor.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.repository.EngineSettingsRepository

/**
 * Реализация репозитория настроек движка через SharedPreferences
 */
class SharedPrefsEngineSettingsRepository(
    context: Context
) : EngineSettingsRepository {

    companion object {
        private const val PREFS_NAME = "engine_settings"
        private const val KEY_DEPTH = "depth"
        private const val KEY_THREADS = "threads"
        private const val KEY_HASH_SIZE = "hash_size"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun getSettings(): EngineSettings {
        return EngineSettings(
            depth = prefs.getInt(KEY_DEPTH, EngineSettings.DEFAULT_DEPTH),
            threads = prefs.getInt(KEY_THREADS, EngineSettings.DEFAULT_THREADS),
            hashSizeMb = prefs.getInt(KEY_HASH_SIZE, EngineSettings.DEFAULT_HASH_SIZE)
        ).validate()
    }

    override fun saveSettings(settings: EngineSettings) {
        val validated = settings.validate()
        prefs.edit()
            .putInt(KEY_DEPTH, validated.depth)
            .putInt(KEY_THREADS, validated.threads)
            .putInt(KEY_HASH_SIZE, validated.hashSizeMb)
            .apply()
    }

    override fun resetToDefault() {
        saveSettings(EngineSettings.default())
    }

    override fun updateDepth(depth: Int) {
        val current = getSettings()
        saveSettings(current.copy(depth = depth))
    }

    override fun updateThreads(threads: Int) {
        val current = getSettings()
        saveSettings(current.copy(threads = threads))
    }

    override fun updateHashSize(hashSizeMb: Int) {
        val current = getSettings()
        saveSettings(current.copy(hashSizeMb = hashSizeMb))
    }
}