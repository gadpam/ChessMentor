package com.example.chessmentor.presentation.ui.components

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Менеджер звуковых эффектов для шахматной доски и тренировки.
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

    fun isSoundEnabled(): Boolean = isEnabled

    /**
     * Включить/выключить звуки.
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

    /** Звук обычного хода. */
    suspend fun playMove() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_PROP_BEEP, 85)
    }

    /** Звук взятия фигуры. */
    suspend fun playCapture() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_PROP_ACK, 130)
    }

    /** Звук шаха. */
    suspend fun playCheck() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 170)
    }

    /** Звук мата / успешного решения задачи. */
    suspend fun playCheckmate() = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext
        startToneSafe(ToneGenerator.TONE_PROP_ACK, 110)
        delay(130)
        startToneSafe(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 160)
    }

    /** Звук успешного решения тактической задачи. */
    suspend fun playSuccess() = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext
        startToneSafe(ToneGenerator.TONE_PROP_BEEP2, 140)
    }

    /** Звук ошибки / неверного хода. */
    suspend fun playError() = withContext(Dispatchers.IO) {
        startToneSafe(ToneGenerator.TONE_PROP_NACK, 160)
    }

    /** Освободить ресурсы. */
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
