// domain/util/AccuracyCalculator.kt
package com.example.chessmentor.presentation.ui.components.util

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.MoveQuality
import kotlin.math.exp

/**
 * Калькулятор точности по методу CAPS (как в Chess.com)
 */
object AccuracyCalculator {

    /**
     * Конвертация сантипешек в вероятность победы (0-100%)
     * Формула из Lichess/Chess.com
     */
    fun centipawnsToWinProbability(cp: Int): Double {
        // Ограничиваем для избежания overflow
        val cappedCp = cp.coerceIn(-1000, 1000)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cappedCp)) - 1.0)
    }

    /**
     * Расчёт точности одного хода (0-100%)
     *
     * @param cpBefore Оценка ДО хода (с точки зрения белых)
     * @param cpAfter Оценка ПОСЛЕ хода (с точки зрения белых)
     * @param playerColor Цвет игрока
     */
    fun calculateMoveAccuracy(
        cpBefore: Int,
        cpAfter: Int,
        playerColor: ChessColor
    ): Double {
        // Конвертируем в Win% с точки зрения игрока
        val winBefore = if (playerColor == ChessColor.WHITE) {
            centipawnsToWinProbability(cpBefore)
        } else {
            centipawnsToWinProbability(-cpBefore)
        }

        val winAfter = if (playerColor == ChessColor.WHITE) {
            centipawnsToWinProbability(cpAfter)
        } else {
            centipawnsToWinProbability(-cpAfter)
        }

        // Если позиция улучшилась — 100%
        if (winAfter >= winBefore) return 100.0

        // Иначе считаем точность по формуле CAPS
        val winDrop = winBefore - winAfter
        val accuracy = 103.1668 * exp(-0.04354 * winDrop) - 3.1669

        return accuracy.coerceIn(0.0, 100.0)
    }

    /**
     * Расчёт общей точности партии
     */
    fun calculateGameAccuracy(
        evaluations: List<Int>,
        playerColor: ChessColor
    ): Double {
        if (evaluations.size < 2) return 100.0

        var totalAccuracy = 0.0
        var playerMoveCount = 0

        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhiteMove = moveIndex % 2 == 0
            val isPlayerMove = (playerColor == ChessColor.WHITE && isWhiteMove) ||
                    (playerColor == ChessColor.BLACK && !isWhiteMove)

            if (isPlayerMove) {
                val cpBefore = evaluations[i - 1]
                val cpAfter = evaluations[i]
                val moveAccuracy = calculateMoveAccuracy(cpBefore, cpAfter, playerColor)
                totalAccuracy += moveAccuracy
                playerMoveCount++
            }
        }

        return if (playerMoveCount > 0) {
            totalAccuracy / playerMoveCount
        } else {
            100.0
        }
    }

    /**
     * Классификация качества хода
     */
    fun classifyMove(
        cpBefore: Int,
        cpAfter: Int,
        bestMoveCp: Int?, // Оценка после лучшего хода (если известна)
        playerColor: ChessColor,
        isPlayerMove: Boolean,
        moveNumber: Int
    ): MoveQuality {
        // Изменение с точки зрения игрока
        val playerCpBefore = if (playerColor == ChessColor.WHITE) cpBefore else -cpBefore
        val playerCpAfter = if (playerColor == ChessColor.WHITE) cpAfter else -cpAfter
        val cpChange = playerCpAfter - playerCpBefore

        // Потеря в сантипешках (положительное = плохо)
        val cpLoss = -cpChange

        // Позиция игрока до хода
        val wasLosing = playerCpBefore < -100

        return when {
            // Зевок: потеря 300+ cp
            cpLoss >= 300 -> MoveQuality.BLUNDER

            // Ошибка: потеря 100-300 cp
            cpLoss >= 100 -> MoveQuality.MISTAKE

            // Неточность: потеря 30-100 cp
            cpLoss >= 30 -> MoveQuality.INACCURACY

            // Теория (первые 10 ходов без существенных потерь)
            moveNumber <= 10 && cpLoss <= 15 -> MoveQuality.BOOK

            // Блестящий ход: значительное улучшение в плохой позиции
            cpChange >= 200 && wasLosing -> MoveQuality.BRILLIANT

            // Отличный ход: хорошее улучшение
            cpChange >= 100 -> MoveQuality.GREAT_MOVE

            // Лучший ход: совпадает с движком (или очень близко)
            cpLoss <= 5 -> MoveQuality.BEST_MOVE

            // Превосходный ход: в пределах 15cp
            cpLoss <= 15 -> MoveQuality.EXCELLENT

            // Хороший ход: в пределах 29cp
            else -> MoveQuality.GOOD
        }
    }
}