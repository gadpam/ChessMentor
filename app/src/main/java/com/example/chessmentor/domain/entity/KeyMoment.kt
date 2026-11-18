// domain/entity/KeyMoment.kt
package com.example.chessmentor.domain.entity

/**
 * Ключевой момент партии для сводки анализа, графика оценки и интерактивного разбора.
 */
data class KeyMoment(
    val moveIndex: Int,
    val san: String,
    val quality: MoveQuality,
    val evaluationBefore: Int,
    val evaluationAfter: Int,
    /** Изменение оценки со стороны игрока, сделавшего ход (> 0 — улучшение, < 0 — потеря). */
    val evaluationChange: Int,
    val isPlayerMove: Boolean,
    val comment: String? = null,
    val moveNumber: Int = (moveIndex / 2) + 1,
    val color: ChessColor = if (moveIndex % 2 == 0) ChessColor.WHITE else ChessColor.BLACK,
    val bestMove: String? = null,
    val evalLoss: Int = 0
) {
    /** Номер и ход в стандартной записи: `"14. Nxd5"` для белых, `"14... Qxd5"` для чёрных. */
    fun formattedMove(): String =
        if (color == ChessColor.WHITE) "$moveNumber. $san" else "$moveNumber... $san"

    /**
     * Изменение оценки с точки зрения пользователя:
     * положительное — хороший ход пользователя или ошибка соперника;
     * отрицательное — ошибка пользователя.
     */
    fun userPerspectiveDelta(): Int = if (isPlayerMove) evaluationChange else -evaluationChange
}
