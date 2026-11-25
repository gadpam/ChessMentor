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
 * Агрегированная и взвешенная статистика пользователя по тактическому паттерну.
 * Учитывает чистоту решения с 1-й попытки, подсказки, давность попыток (экспоненциальное
 * затухание старых ошибок) и относительную сложность задач.
 */
data class PatternMastery(
    val pattern: TacticalPattern,
    val totalAttempts: Int,
    val solvedAttempts: Int,
    val uniqueExercisesAttempted: Int,
    val uniqueExercisesSolved: Int,
    val unresolvedCount: Int,
    /** Сырая доля успешных попыток (0..100) для обратной совместимости. */
    val accuracyPercent: Int,
    val level: MasteryLevel,
    /** Взвешенный индекс реального владения мотивом (0..100): давность + чистота + сложность. */
    val masteryPercent: Int = accuracyPercent,
    /** Честная доля чистых решений с 1-й попытки без подсказок (0..100). */
    val firstTryAccuracyPercent: Int = accuracyPercent,
    /** Число задач этого мотива, закреплённых в долговременной памяти (коробка >= 4). */
    val masteredCount: Int = 0
)

/**
 * Расчёт калиброванного изменения рейтинга Эло (без искусственной инфляции),
 * детектор импульсивных ошибок («игры руками»), нормализация PV
 * и взвешенная диагностика владения тактическими паттернами.
 */
object TrainingRatingCalculator {

    const val MIN_RATING = 100
    const val MAX_RATING = 3500

    /** Симметричные коэффициенты K (нулевой дрейф при 50% успеха на равных задачах). */
    private const val PROVISIONAL_K = 40.0   // Первые 10 подходов — быстрая калибровка (аналог RD в Glicko-2)
    private const val CALIBRATING_K = 28.0   // 10..29 подходов — плавная доводка до реальной силы
    private const val ESTABLISHED_K = 22.0   // 30+ подходов — стабильный рейтинг без скачков

    /** Коэффициент экспоненциального веса давности попыток (свежие попытки важнее старых). */
    private const val RECENCY_DECAY = 0.85
    private const val MIN_RECENCY_WEIGHT = 0.20

    /**
     * Симметричный K-фактор в зависимости от накопленного числа рейтинговых подходов.
     * В отличие от несимметричных K, при 50% побед на равных задачах суммарный сдвиг равен 0.
     */
    fun kFactorForExperience(completedRatedSittings: Int = 15): Double = when {
        completedRatedSittings < 10 -> PROVISIONAL_K
        completedRatedSittings < 30 -> CALIBRATING_K
        else -> ESTABLISHED_K
    }

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
     * Изменение рейтинга при успешном решении задачи (симметричный K без инфляции + калибровка).
     *
     * Правила честной оценки:
     * - показ полного решения / стрелки хода → 0;
     * - подсказка фигурой → множитель 0.50;
     * - дорешивание после 1 ошибки → множитель 0.30, после 2+ ошибок → 0.15
     *   (ошибка + дорешивание на равной задаче всегда даёт отрицательный баланс).
     */
    fun calculateSolveDelta(
        playerElo: Int,
        exercise: Exercise,
        hadMistake: Boolean,
        maxHintUsed: HintUsage,
        solutionRevealed: Boolean = false,
        completedRatedSittings: Int = 15,
        mistakeCount: Int = if (hadMistake) 1 else 0
    ): Int {
        if (solutionRevealed || maxHintUsed == HintUsage.FULL_SOLUTION) {
            return 0
        }

        val k = kFactorForExperience(completedRatedSittings)
        val maxStep = k.roundToInt().coerceAtLeast(16)
        val puzzleElo = effectiveExerciseElo(exercise)
        val expected = expectedScore(playerElo, puzzleElo)
        val baseGain = (k * (1.0 - expected)).roundToInt().coerceIn(2, maxStep)

        val effectiveMistakes = if (hadMistake) mistakeCount.coerceAtLeast(1) else 0
        var multiplier = 1.0
        when {
            effectiveMistakes >= 2 -> multiplier *= 0.15
            effectiveMistakes == 1 -> multiplier *= 0.30
        }
        if (maxHintUsed == HintUsage.PIECE_HIGHLIGHT) {
            multiplier *= 0.50
        }

        if (multiplier >= 0.99) return baseGain
        return (baseGain * multiplier).roundToInt().coerceAtLeast(1)
    }

    /**
     * Изменение рейтинга (отрицательное) при первой ошибке в задаче.
     * Использует тот же симметричный K-фактор, что и [calculateSolveDelta], исключая инфляцию.
     */
    fun calculateFailureDelta(
        playerElo: Int,
        exercise: Exercise,
        completedRatedSittings: Int = 15
    ): Int {
        val k = kFactorForExperience(completedRatedSittings)
        val maxStep = k.roundToInt().coerceAtLeast(16)
        val puzzleElo = effectiveExerciseElo(exercise)
        val expected = expectedScore(playerElo, puzzleElo)
        val loss = (k * expected).roundToInt().coerceIn(2, maxStep)
        return -loss
    }

    /**
     * Небольшой дисциплинирующий штраф за повторное «угадывание ходом» (2-я и 3-я ошибка подряд
     * в одной и той же задаче), чтобы отучать от бездумного перебора клеток.
     */
    fun calculateRepeatedGuessPenalty(mistakeNumber: Int): Int = when {
        mistakeNumber == 2 -> -3
        mistakeNumber >= 3 -> -2
        else -> 0
    }

    /**
     * Минимальное разумное время первичной оценки позиции (в мс) перед первым ходом.
     * В режиме «Расчёт» порог строже (требуется просчёт варианта), в режиме «Зоркость» — мягче.
     */
    fun impulsiveThresholdMs(exercise: Exercise?, mode: TrainingMode? = null): Long {
        if (exercise == null) return 4_000L
        val diff = exercise.getEffectiveDifficulty().coerceIn(0, 100)
        val multiMove = exercise.solutionMoves.size >= 3
        val base = when {
            diff < 30 -> 3_000L
            diff < 65 -> 4_500L
            else -> 6_000L
        }
        val modeAdj = when (mode) {
            TrainingMode.DeepCalculation -> 2_000L
            TrainingMode.PatternVision -> -1_000L
            else -> 0L
        }
        return (base + (if (multiMove) 500L else 0L) + modeAdj).coerceAtLeast(1_800L)
    }

    /**
     * Возвращает `true`, если игрок допустил ошибку первым же ходом без расчёта позиции.
     */
    fun isImpulsiveMistake(
        firstMoveThinkMs: Long?,
        exercise: Exercise?,
        isFirstMoveOfPuzzle: Boolean,
        mode: TrainingMode? = null
    ): Boolean {
        if (!isFirstMoveOfPuzzle || firstMoveThinkMs == null) return false
        if (firstMoveThinkMs < 150L) return false
        return firstMoveThinkMs < impulsiveThresholdMs(exercise, mode)
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
     * Взвешенная оценка владения тактическими паттернами:
     * - группирует попытки в реальные подходы ([SpacedRepetitionScheduler.groupIntoSittings]),
     *   отличая чистое решение с 1-й попытки (1.0), медленное (0.88), с подсказкой (0.45),
     *   со 2-й попытки (0.30) и провал (0.0);
     * - применяет экспоненциальный вес давности ([RECENCY_DECAY]), чтобы недавний прогресс
     *   реально поднимал оценку темы, а свежая серия ошибок сразу сигнализировала о спаде;
     * - учитывает сложность задачи относительно [playerElo] (провал на задаче +500 Эло
     *   штрафует тему слабее, чем зевок базовой задачи своего уровня).
     */
    fun computePatternMastery(
        exercisesById: Map<Long, Exercise>,
        attempts: List<ExerciseAttempt>,
        playerElo: Int? = null
    ): List<PatternMastery> {
        if (attempts.isEmpty() || exercisesById.isEmpty()) return emptyList()

        val groupedRaw = attempts.mapNotNull { attempt ->
            val exercise = exercisesById[attempt.exerciseId] ?: return@mapNotNull null
            exercise.tacticalPattern to attempt
        }.groupBy({ it.first }, { it.second })

        return groupedRaw.map { (pattern, patternAttempts) ->
            val total = patternAttempts.size
            val solved = patternAttempts.count(AttemptMetadata::isIndependentSolve)
            val byExercise = patternAttempts.groupBy { it.exerciseId }
            val uniqueAttempted = byExercise.size
            val uniqueSolved = byExercise.values.count { list -> list.any(AttemptMetadata::isIndependentSolve) }
            val unresolved = uniqueAttempted - uniqueSolved
            val rawAccuracy = if (total > 0) ((solved * 100.0) / total).roundToInt().coerceIn(0, 100) else 0

            // Собираем все подходы по данному мотиву в хронологическом порядке
            val sittings = byExercise.flatMap { (exId, exAttempts) ->
                SpacedRepetitionScheduler.groupIntoSittings(exAttempts, exercisesById[exId])
            }.sortedBy { it.finishedAt }

            val cleanFirstTry = sittings.count { it.quality.isCleanFirstTry }
            val firstTryAcc = if (sittings.isNotEmpty()) {
                ((cleanFirstTry * 100.0) / sittings.size).roundToInt().coerceIn(0, 100)
            } else {
                rawAccuracy
            }

            val masteredCount = byExercise.count { (exId, exAttempts) ->
                SpacedRepetitionScheduler.computeCardState(exId, exAttempts, exercisesById[exId])?.isMastered == true
            }

            val weightedMastery = computeWeightedMasteryPercent(
                sittings = sittings,
                exercisesById = exercisesById,
                playerElo = playerElo,
                fallbackPercent = rawAccuracy
            )

            val level = when {
                weightedMastery < 55 || (unresolved >= 2 && weightedMastery < 68) -> MasteryLevel.WEAK
                weightedMastery >= 76 && (cleanFirstTry >= 2 || (cleanFirstTry >= 1 && solved >= 2)) -> MasteryLevel.STRONG
                else -> MasteryLevel.LEARNING
            }

            PatternMastery(
                pattern = pattern,
                totalAttempts = total,
                solvedAttempts = solved,
                uniqueExercisesAttempted = uniqueAttempted,
                uniqueExercisesSolved = uniqueSolved,
                unresolvedCount = unresolved,
                accuracyPercent = rawAccuracy,
                level = level,
                masteryPercent = weightedMastery,
                firstTryAccuracyPercent = firstTryAcc,
                masteredCount = masteredCount
            )
        }.sortedWith(
            compareBy<PatternMastery> { it.masteryPercent }
                .thenBy { it.firstTryAccuracyPercent }
                .thenByDescending { it.unresolvedCount }
                .thenByDescending { it.totalAttempts }
        )
    }

    private fun computeWeightedMasteryPercent(
        sittings: List<ExerciseSitting>,
        exercisesById: Map<Long, Exercise>,
        playerElo: Int?,
        fallbackPercent: Int
    ): Int {
        if (sittings.isEmpty()) return fallbackPercent

        var weightedScoreSum = 0.0
        var totalWeight = 0.0
        val lastIdx = sittings.lastIndex

        for ((idx, sitting) in sittings.withIndex()) {
            val ageSteps = lastIdx - idx
            val recencyWeight = RECENCY_DECAY.pow(ageSteps.toDouble()).coerceAtLeast(MIN_RECENCY_WEIGHT)

            val baseScore = when (sitting.quality) {
                AttemptQuality.CLEAN_FAST_SOLVE -> 1.00
                AttemptQuality.SLOW_CLEAN_SOLVE -> 0.88
                AttemptQuality.HINT_SOLVE -> 0.45
                AttemptQuality.RETRY_SOLVE -> 0.30
                AttemptQuality.FAILED -> 0.00
            }

            val diffFactor = if (playerElo != null) {
                val ex = exercisesById[sitting.exerciseId]
                val exElo = ex?.let { effectiveExerciseElo(it) } ?: playerElo
                val exp = expectedScore(playerElo, exElo) // 0..1 (выше для лёгких задач)
                if (sitting.quality.isCleanFirstTry) {
                    // Решение сложной задачи (низкий exp) весит больше
                    (0.75 + 0.70 * (1.0 - exp)).coerceIn(0.65, 1.40)
                } else {
                    // Ошибка на лёгкой задаче (высокий exp) весит больше, на сверхсложной — мягче
                    (0.55 + 0.80 * exp).coerceIn(0.50, 1.35)
                }
            } else {
                1.0
            }

            val weight = recencyWeight * diffFactor
            weightedScoreSum += baseScore * weight
            totalWeight += weight
        }

        if (totalWeight <= 1e-6) return fallbackPercent
        return ((weightedScoreSum / totalWeight) * 100.0).roundToInt().coerceIn(0, 100)
    }
}
