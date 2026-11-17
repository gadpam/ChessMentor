package com.example.chessmentor.domain.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "mistakes",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["gameId"])]
)
data class Mistake(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
    val gameId: Long,
    val themeId: Long,
    val moveNumber: Int,
    val color: ChessColor,
    val evaluationLoss: Int,
    val mistakeType: MistakeType,
    val bestMove: String,
    val userMove: String,
    val comment: String? = null,
    val fenBefore: String? = null
) {
    init {
        require(moveNumber > 0) { "Номер хода должен быть положительным" }
        require(evaluationLoss >= 0) { "Потеря оценки не может быть отрицательной" }
        require(bestMove.isNotBlank()) { "Лучший ход не может быть пустым" }
        require(userMove.isNotBlank()) { "Ход игрока не может быть пустым" }
    }

    fun getDescription(): String = mistakeType.getDisplayName()  // ИСПРАВЛЕНО

    fun getEmoji(): String = mistakeType.getEmoji()

    fun getEvaluationLossInPawns(): Double = evaluationLoss / 100.0

    fun getFormattedDescription(): String {
        val emoji = getEmoji()
        val loss = getEvaluationLossInPawns()
        val colorName = if (color == ChessColor.WHITE) "Белые" else "Чёрные"
        val lossStr = if (evaluationLoss > 50_000) {
            "Мат"
        } else {
            "-${String.format(java.util.Locale.US, "%.2f", loss)}"
        }
        return "Ход $moveNumber ($colorName). $userMove $emoji (Лучше: $bestMove) $lossStr"
    }

    fun isCritical(): Boolean = mistakeType == MistakeType.BLUNDER

    fun getSeverity(): Int = when (mistakeType) {
        MistakeType.BLUNDER -> 10
        MistakeType.MISTAKE -> 6
        MistakeType.INACCURACY -> 3
    }

    override fun toString(): String {
        return "Mistake(id=$id, move=$moveNumber, color=$color, type=$mistakeType, loss=$evaluationLoss)"
    }
}