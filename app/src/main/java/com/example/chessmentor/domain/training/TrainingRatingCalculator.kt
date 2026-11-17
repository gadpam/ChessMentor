// domain/training/TrainingRatingCalculator.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalPattern
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Уровень использованной подсказки при расчёте изменения рейтинга.
 */
enum class HintUsage {
    NONE,
    PIECE_HIGHLIGHT,
    FULL_SOLUTION
}

/**
 * Уровень владения тактическим паттерном.
 */
enum class MasteryLevel {
    WEAK,
    LEARNING,
    STRONG
}

/**
 * Агрегированная статистика пользователя по конкретному тактическому паттерну.
 */
data class PatternMastery(
    val pattern: TacticalPattern,
    val totalAttempts: Int,
    val solvedAttempts: Int,
    val uniqueExercisesAttempted: Int,
    val uniqueExercisesSolved: Int,
    val unresolvedCount: Int,
    val accuracyPercent: Int,
    val level: MasteryLevel
)

/**
 * Расчёт изменения рейтинга Эло в тренировке, нормализация длины PV
 * и анализ владения тактическими паттернами.
 */
object TrainingRatingCalculator {

    const val MIN_RATING = 100
    const val MAX_RATING = 3500

    private const val SOLVE_K_FACTOR = 28.0
    private const val FAIL_K_FACTOR = 24.0

    /**
     * Переводит сложность упражнения (0..100) в ориентировочный рейтинг Эло (800..2400).
     */
    fun difficultyToElo(difficultyRating: Int): Int {
        val clamped = difficultyRating.coerceIn(0, 100)
        return (800 + clamped * 16).coerceIn(800, 2400)
    }

    /**
     * Переводит рейтинг Эло игрока в целевую сложность упражнения (0..100).
     */
    fun eloToDifficulty(elo: Int): Int {
        return ((elo - 800) / 16.0).roundToInt().coerceIn(10, 95)
    }

    /**
     * Эффективный рейтинг Эло упражнения с учётом накопленной статистики решаемости.
     */
    fun effectiveExerciseElo(exercise: Exercise): Int {
        val effectiveDiff = exercise.getEffectiveDifficulty().coerceIn(0, 100)
        return difficultyToElo(effectiveDiff)
    }

    /**
     * Математическое ожидание результата по классической формуле Эло.
     */
    fun expectedScore(playerElo: Int, exerciseElo: Int): Double {
        val exponent = (exerciseElo - playerElo) / 400.0
        return 1.0 / (1.0 + 10.0.pow(exponent))
    }

    /**
     * Изменение рейтинга при успешном решении задачи.
     *
     * Правила защиты от накрутки:
     * - если было показано полное решение (стрелка хода или кнопка «Показать решение») → 0;
     * - если использовалась лёгкая подсказка (подсветка фигуры) → множитель 0.55;
     * - если в этой же попытке ранее была допущена ошибка (штраф уже списан) → множитель 0.4,
     *   чтобы суммарный итог «ошибка + дорешивание» не давал положительного прироста на равной задаче.
     */
    fun calculateSolveDelta(
        playerElo: Int,
        exercise: Exercise,
        hadMistake: Boolean,
        maxHintUsed: HintUsage,
        solutionRevealed: Boolean = false
    ): Int {
        if (solutionRevealed || maxHintUsed == HintUsage.FULL_SOLUTION) {
            return 0
        }

        val puzzleElo = effectiveExerciseElo(exercise)
        val expected = expectedScore(playerElo, puzzleElo)
        val baseGain = (SOLVE_K_FACTOR * (1.0 - expected)).roundToInt().coerceIn(4, 28)

        var multiplier = 1.0
        if (hadMistake) {
            multiplier *= 0.35
        }
        if (maxHintUsed == HintUsage.PIECE_HIGHLIGHT) {
            multiplier *= 0.55
        }

        if (multiplier >= 0.99) return baseGain
        return (baseGain * multiplier).roundToInt().coerceAtLeast(1)
    }

    /**
     * Изменение рейтинга (отрицательное) при первой ошибке в задаче.
     * Ошибка на лёгкой задаче стоит дороже, на значительно более сложной — мягче.
     */
    fun calculateFailureDelta(
        playerElo: Int,
        exercise: Exercise
    ): Int {
        val puzzleElo = effectiveExerciseElo(exercise)
        val expected = expectedScore(playerElo, puzzleElo)
        val loss = (FAIL_K_FACTOR * expected).roundToInt().coerceIn(4, 22)
        return -loss
    }

    /**
     * Обрезает главную линию (PV) до нечётного числа полуходов (не более [maxPlies]),
     * чтобы задача ВСЕГДА заканчивалась решающим ходом игрока, а не ответом соперника.
     */
    fun trimPvToPlayerLastMove(moves: List<String>, maxPlies: Int = 5): List<String> {
        if (moves.isEmpty() || maxPlies <= 0) return emptyList()
        val capped = moves.take(maxPlies)
        if (capped.size == 1) return capped
        return if (capped.size % 2 == 0) capped.dropLast(1) else capped
    }

    /**
     * Агрегирует владение тактическими паттернами на основе попыток пользователя.
     * Сортирует от наиболее проблемных (низкая точность, есть нерешённые) к наиболее сильным.
     */
    fun computePatternMastery(
        exercisesById: Map<Long, Exercise>,
        attempts: List<ExerciseAttempt>
    ): List<PatternMastery> {
        if (attempts.isEmpty() || exercisesById.isEmpty()) return emptyList()

        val grouped = attempts.mapNotNull { attempt ->
            val exercise = exercisesById[attempt.exerciseId] ?: return@mapNotNull null
            exercise.tacticalPattern to attempt
        }.groupBy({ it.first }, { it.second })

        return grouped.map { (pattern, patternAttempts) ->
            val total = patternAttempts.size
            val solved = patternAttempts.count { it.solved }
            val byExercise = patternAttempts.groupBy { it.exerciseId }
            val uniqueAttempted = byExercise.size
            val uniqueSolved = byExercise.values.count { list -> list.any { it.solved } }
            val unresolved = uniqueAttempted - uniqueSolved
            val accuracy = if (total > 0) ((solved * 100.0) / total).roundToInt().coerceIn(0, 100) else 0

            val level = when {
                accuracy < 55 || (unresolved >= 2 && accuracy < 70) -> MasteryLevel.WEAK
                accuracy >= 75 && solved >= 2 -> MasteryLevel.STRONG
                else -> MasteryLevel.LEARNING
            }

            PatternMastery(
                pattern = pattern,
                totalAttempts = total,
                solvedAttempts = solved,
                uniqueExercisesAttempted = uniqueAttempted,
                uniqueExercisesSolved = uniqueSolved,
                unresolvedCount = unresolved,
                accuracyPercent = accuracy,
                level = level
            )
        }.sortedWith(
            compareBy<PatternMastery> { it.accuracyPercent }
                .thenByDescending { it.unresolvedCount }
                .thenByDescending { it.totalAttempts }
        )
    }
}
