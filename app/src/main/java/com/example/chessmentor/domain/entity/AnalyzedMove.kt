// domain/entity/AnalyzedMove.kt
package com.example.chessmentor.domain.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Результат анализа одного хода.
 * Хранит качество хода (от BRILLIANT до BLUNDER) для отображения в UI.
 */
@Entity(
    tableName = "analyzed_moves",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["gameId"])]
)
data class AnalyzedMove(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    val gameId: Long,

    /** Индекс полухода (0-based). Ход 1 белых = 0, ход 1 чёрных = 1, и т.д. */
    val moveIndex: Int,

    /** Номер хода (1-based) */
    val moveNumber: Int,

    /** Цвет игрока, сделавшего ход */
    val color: ChessColor,

    /** Качество хода */
    val quality: MoveQuality,

    /** Ход в SAN нотации (e.g., "Nxe5") */
    val san: String,

    /** Лучший ход (если отличается от сделанного) */
    val bestMove: String? = null,

    /** Оценка до хода (в сантипешках, с точки зрения белых) */
    val evalBefore: Int,

    /** Оценка после хода */
    val evalAfter: Int,

    /** Потеря оценки (положительное = плохо) */
    val evalLoss: Int,

    /** Комментарий к ходу */
    val comment: String? = null
) {
    /**
     * Изменение оценки с точки зрения белых
     */
    val evalChange: Int get() = evalAfter - evalBefore

    /**
     * Изменение оценки с точки зрения игрока, сделавшего ход (> 0 — улучшение, < 0 — потеря)
     */
    val playerEvalChange: Int
        get() = if (color == ChessColor.WHITE) evalAfter - evalBefore else evalBefore - evalAfter

    /**
     * Это ход с ошибкой?
     */
    fun isMistake(): Boolean = quality in listOf(
        MoveQuality.BLUNDER,
        MoveQuality.MISTAKE,
        MoveQuality.INACCURACY,
        MoveQuality.MISSED_WIN
    )

    /**
     * Это хороший ход?
     */
    fun isGoodMove(): Boolean = quality in listOf(
        MoveQuality.BRILLIANT,
        MoveQuality.GREAT_MOVE,
        MoveQuality.BEST_MOVE,
        MoveQuality.EXCELLENT,
        MoveQuality.GOOD
    )

    /**
     * Конвертация в KeyMoment для UI
     */
    fun toKeyMoment(): KeyMoment = KeyMoment(
        moveIndex = moveIndex,
        san = san,
        quality = quality,
        evaluationBefore = evalBefore,
        evaluationAfter = evalAfter,
        evaluationChange = playerEvalChange,
        isPlayerMove = true,
        comment = comment
    )

    /**
     * Конвертация в Mistake (для обратной совместимости).
     * Если лучший ход отсутствует или совпадает с ходом игрока — ошибку не создаём.
     */
    fun toMistake(themeId: Long = 10L, fenBefore: String = ""): Mistake? {
        if (!isMistake()) return null
        val nonBlankBestMove = bestMove?.takeIf { it.isNotBlank() && it != san } ?: return null

        val mistakeType = when (quality) {
            MoveQuality.BLUNDER -> MistakeType.BLUNDER
            MoveQuality.MISTAKE, MoveQuality.MISSED_WIN -> MistakeType.MISTAKE
            MoveQuality.INACCURACY -> MistakeType.INACCURACY
            else -> return null
        }

        return Mistake(
            gameId = gameId,
            themeId = themeId,
            moveNumber = moveNumber,
            color = color,
            evaluationLoss = evalLoss.coerceAtLeast(0),
            mistakeType = mistakeType,
            bestMove = nonBlankBestMove,
            userMove = san,
            comment = comment,
            fenBefore = fenBefore
        )
    }
}