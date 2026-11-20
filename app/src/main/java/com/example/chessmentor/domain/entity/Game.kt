// domain/entity/Game.kt
package com.example.chessmentor.domain.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["userId"])]
)
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    val userId: Long,
    val pgnData: String,
    val playerColor: ChessColor,
    val timeControl: String? = null,
    val playedAt: Long,

    val analysisStatus: AnalysisStatus = AnalysisStatus.PENDING,
    val analyzedAt: Long? = null,

    val accuracy: Double? = null,
    val averageEvaluationLoss: Int? = null,
    val totalMistakes: Int = 0,
    val blundersCount: Int = 0,
    val mistakesCount: Int = 0,
    val inaccuraciesCount: Int = 0,

    // ✅ НОВОЕ: Линия оценок в JSON формате
    val evaluationsJson: String? = null
) {

    init {
        require(pgnData.isNotBlank()) {
            "PGN данные не могут быть пустыми"
        }
        require(accuracy == null || accuracy in 0.0..100.0) {
            "Точность должна быть от 0 до 100"
        }
    }

    /**
     * Завершён ли анализ
     */
    fun isAnalyzed(): Boolean = analysisStatus == AnalysisStatus.COMPLETED

    /**
     * Можно ли просматривать результаты
     */
    fun canViewResults(): Boolean = analysisStatus.isViewable()

    /**
     * Обновить статус анализа на "В процессе"
     */
    fun startAnalysis(): Game {
        return copy(analysisStatus = AnalysisStatus.IN_PROGRESS)
    }

    /**
     * Завершить анализ с результатами
     * ✅ ОБНОВЛЕНО: Принимает evaluations
     */
    fun completeAnalysis(
        accuracy: Double,
        avgLoss: Int,
        blunders: Int,
        mistakes: Int,
        inaccuracies: Int,
        evaluations: List<Int>? = null
    ): Game {
        return copy(
            analysisStatus = AnalysisStatus.COMPLETED,
            analyzedAt = System.currentTimeMillis(),
            accuracy = accuracy,
            averageEvaluationLoss = avgLoss,
            totalMistakes = blunders + mistakes + inaccuracies,
            blundersCount = blunders,
            mistakesCount = mistakes,
            inaccuraciesCount = inaccuracies,
            evaluationsJson = evaluations?.let { encodeEvaluations(it) }
        )
    }

    /**
     * Отметить анализ как проваленный
     */
    fun failAnalysis(): Game {
        return copy(
            analysisStatus = AnalysisStatus.FAILED,
            analyzedAt = System.currentTimeMillis(),
        )
    }

    /**
     * ✅ НОВОЕ: Получить список оценок из JSON
     */
    fun getEvaluations(): List<Int> {
        return evaluationsJson?.let { decodeEvaluations(it) } ?: emptyList()
    }

    /**
     * ✅ НОВОЕ: Проверить, сохранены ли оценки
     */
    fun hasEvaluations(): Boolean {
        return !evaluationsJson.isNullOrBlank() && evaluationsJson != "[]"
    }

    /**
     * Получить краткое описание партии
     */
    fun getShortDescription(): String {
        val color = playerColor.getDisplayName()
        val time = timeControl ?: "неизвестно"
        return "$color · $time"
    }

    override fun toString(): String {
        return "Game(id=$id, userId=$userId, color=$playerColor, status=$analysisStatus)"
    }

    companion object {
        /**
         * ✅ НОВОЕ: Кодирование списка оценок в компактный JSON
         * Формат: "[0,25,-150,300]"
         */
        fun encodeEvaluations(evaluations: List<Int>): String {
            if (evaluations.isEmpty()) return "[]"
            return evaluations.joinToString(",", "[", "]")
        }

        /**
         * ✅ НОВОЕ: Декодирование JSON обратно в список
         */
        fun decodeEvaluations(json: String): List<Int> {
            return try {
                val trimmed = json.trim()
                if (trimmed.isEmpty() || trimmed == "[]") {
                    emptyList()
                } else {
                    trimmed
                        .removePrefix("[")
                        .removeSuffix("]")
                        .split(",")
                        .filter { it.isNotBlank() }
                        .map { it.trim().toInt() }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}