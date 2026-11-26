// domain/entity/Exercise.kt
package com.example.chessmentor.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.chessmentor.data.local.TypeConverters as MyConverters
import kotlin.math.ln
import kotlin.math.roundToInt

@Entity(
    tableName = "exercises",
    indices = [
        Index(value = ["tacticalPattern"]),
        Index(value = ["difficultyRating"]),
        Index(value = ["patternClusterId"])
    ]
)
@TypeConverters(MyConverters::class)
data class Exercise(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    val fenPosition: String,

    val solutionMoves: List<String>,

    /**
     * Основной тактический паттерн задачи
     */
    val tacticalPattern: TacticalPattern,

    /**
     * Идентификатор кластера похожих позиций
     * Формат: "{pattern}_{subtype}_{difficulty}"
     * Пример: "FORK_KNIGHT_MEDIUM"
     */
    val patternClusterId: String,

    /**
     * Сложность задачи (0-100)
     */
    val difficultyRating: Int,

    /**
     * Дополнительные характеристики паттерна
     */
    val patternFeatures: List<String> = emptyList(),

    /**
     * Тема (deprecated, используйте tacticalPattern)
     */
    val theme: TacticalTheme = TacticalTheme.TACTIC,

    /**
     * Рейтинг (deprecated, используйте difficultyRating)
     */
    val rating: Int = 1500,

    val createdFrom: ExerciseSource = ExerciseSource.MISTAKE,
    val sourceGameId: Long? = null,
    val sourceMoveNumber: Int? = null,

    val createdAt: Long = System.currentTimeMillis(),

    val timesShown: Int = 0,
    val timesSolved: Int = 0,
    val successRate: Int = 0
) {
    fun getEffectiveDifficulty(): Int {
        val nominal = difficultyRating.coerceIn(0, 100)
        if (timesShown <= 0) return nominal

        val observedSolved = if (timesSolved > 0 || successRate == 0) {
            timesSolved.coerceIn(0, timesShown)
        } else {
            ((successRate / 100.0) * timesShown).roundToInt().coerceIn(0, timesShown)
        }

        // Байесовская калибровка по Бета-биномиальной модели:
        // априор Beta(4.8, 3.2) со средним p0 = 0.60 и весом 8 попыток плавно сдвигает
        // номинальную сложность по логит-шкале Эло (16 Эло = 1 пункт сложности).
        val priorMean = 0.60
        val priorWeight = 8.0
        val alpha0 = priorMean * priorWeight
        val postMean = (observedSolved + alpha0) / (timesShown + priorWeight)
        val logitShift = ln(postMean / (1.0 - postMean)) - ln(priorMean / (1.0 - priorMean))
        val deltaDiff = (-(25.0 / ln(10.0)) * logitShift).roundToInt().coerceIn(-30, 30)
        return (nominal + deltaDiff).coerceIn(0, 100)
    }
}