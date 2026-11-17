package com.example.chessmentor.presentation.ui.components

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Менеджер звуковых эффектов для шахматной доски
 */
class SoundManager(@Suppress("unused") context: Context) {

    companion object {
        private const val TAG = "SoundManager"
    }

    private val lock = Any()
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isEnabled: Boolean = true

    init {
        try {
            toneGenerator = ToneGenerator(
                AudioManager.STREAM_MUSIC,
                ToneGenerator.MAX_VOLUME / 2
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize ToneGenerator", e)
        }
    }

    /**
     * Включить/выключить звуки
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    private fun startToneSafe(toneType: Int, durationMs: Int) {
        if (!isEnabled) return
        synchronized(lock) {
            try {
                toneGenerator?.startTone(toneType, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play tone", e)
            }
        }
    }

    /**
     * Звук обычного хода
     */
    suspend fun playMove() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_PROP_BEEP, 100)
    }

    /**
     * Звук взятия фигуры (более громкий)
     */
    suspend fun playCapture() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_PROP_ACK, 150)
    }

    /**
     * Звук шаха (предупреждающий)
     */
    suspend fun playCheck() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
    }

    /**
     * Звук мата (победный)
     */
    suspend fun playCheckmate() = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext
        startToneSafe(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
        delay(200)
        startToneSafe(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        synchronized(lock) {
            try {
                toneGenerator?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release ToneGenerator", e)
            } finally {
                toneGenerator = null
            }
        }
    }
}
