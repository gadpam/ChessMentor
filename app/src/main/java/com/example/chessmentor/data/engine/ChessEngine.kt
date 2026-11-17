// data/engine/ChessEngine.kt
package com.example.chessmentor.data.engine

import java.util.Locale
import kotlin.math.abs

/**
 * Интерфейс шахматного движка
 */
interface ChessEngine {

    suspend fun init(): Boolean
    suspend fun evaluate(fen: String, depthLimit: Int = 15): Int
    suspend fun getBestMove(fen: String, depthLimit: Int = 15): String?
    suspend fun getBestMoveWithLine(fen: String, depthLimit: Int = 15): AnalysisLine?
    suspend fun setOption(name: String, value: String)
    fun stop()
    fun destroy()

    companion object {
        const val MATE_VALUE = 100000
        const val MATE_THRESHOLD = 90000

        /**
         * Проверяет, является ли оценка матовой
         */
        fun isMateScore(score: Int): Boolean = abs(score) >= MATE_THRESHOLD

        /**
         * Получить количество ходов до мата
         * @return положительное число = мат за сторону, отрицательное = мат против стороны (0 если мат уже на доске)
         */
        fun getMateInMoves(score: Int): Int {
            if (!isMateScore(score)) return 0
            val moves = (MATE_VALUE - abs(score)) / 100
            return if (score > 0) moves else -moves
        }

        /**
         * Форматирование оценки для UI
         */
        fun formatEvaluation(score: Int): String {
            return if (isMateScore(score)) {
                val moves = abs(getMateInMoves(score))
                val sign = if (score > 0) "+" else "-"
                if (moves == 0) "${sign}M" else "${sign}M$moves"
            } else {
                val pawns = score / 100.0
                if (pawns >= 0) {
                    String.format(Locale.US, "+%.1f", pawns)
                } else {
                    String.format(Locale.US, "%.1f", pawns)
                }
            }
        }
    }
}

/**
 * Результат анализа с линией продолжения (оценка со стороны ходящего, как в UCI)
 */
data class AnalysisLine(
    val bestMove: String,                    // Лучший ход (UCI; пусто для терминальной позиции мат/пат)
    val score: Int,                          // Оценка в сантипешках или мат-кодировке
    val mateIn: Int?,                        // Мат в N ходов (null если не мат)
    val principalVariation: List<String>     // Главная линия в UCI
)
