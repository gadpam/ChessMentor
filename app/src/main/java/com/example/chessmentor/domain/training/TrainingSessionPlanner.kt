// domain/training/TrainingSessionPlanner.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import kotlin.math.roundToInt

/**
 * Фаза структурированной тренировки ([TrainingMode.SmartSession]), построенной по
 * принципам осознанной шахматной практики (разминка → интервальный повтор и свои ошибки →
 * чередование мотивов с проверкой защиты → глубокий расчёт).
 */
enum class SessionPhase(
    val displayName: String,
    /** Нейтральная подпись для UI до решения задачи (без спойлера защитного мотива). */
    val preSolveLabel: String,
    val difficultyOffset: Int
) {
    /** Лёгкий вход: быстрое включение тактического зрения на знакомых конструкциях. */
    WARMUP("Разминка", "Разминка", -14),

    /** Интервальное повторение карточек SM-2 и отработка ошибок из собственных партий. */
    REVIEW_AND_MISTAKES("Повтор и свои ошибки", "Повтор и ошибки", -2),

    /** Смешанный блок с чередованием разных тактических мотивов в зоне ближайшего развития. */
    CORE_INTERLEAVED("Чередование мотивов", "Основной блок", 0),

    /** Обязательная задача на защиту/контрудар внутри сессии — снимает привычку «всегда жертвовать». */
    DEFENSE_CHECK("Проверка защиты", "Основной блок", 2),

    /** Финальный блок: глубокий многоходовый расчёт чуть выше текущего уровня. */
    DEEP_CALCULATION("Глубокий расчёт", "Блок расчёта", 10)
}

/**
 * Итоговая тренерская сводка по завершении структурированной сессии из [totalExercises] задач.
 */
data class SessionSummary(
    val totalExercises: Int,
    val cleanFirstTryCount: Int,
    val solvedTotalCount: Int,
    val failedOrRevealedCount: Int,
    val impulsiveMistakeCount: Int,
    val firstTryAccuracyPercent: Int,
    val netRatingDelta: Int,
    val averageTimeSeconds: Int,
    val missedPatternNames: List<String>,
    val headline: String,
    val coachTakeaway: String,
    val skippedCount: Int = 0
)

/**
 * Планировщик структурированной сессии и ориентиров для режимов
 * «Зоркость» (Система 1 — распознавание паттернов) и «Расчёт» (Система 2 — дерево вариантов).
 */
object TrainingSessionPlanner {

    const val DEFAULT_SESSION_SIZE = 10

    /**
     * Определяет фазу тренировки для задачи с 1-базовым номером [stepNumber] из [totalSteps].
     *
     * Для стандартной сессии из 10 задач:
     * - 1..2: [SessionPhase.WARMUP]
     * - 3..5: [SessionPhase.REVIEW_AND_MISTAKES]
     * - 6, 8: [SessionPhase.CORE_INTERLEAVED]
     * - 7:    [SessionPhase.DEFENSE_CHECK]
     * - 9..10: [SessionPhase.DEEP_CALCULATION]
     */
    fun phaseForStep(stepNumber: Int, totalSteps: Int = DEFAULT_SESSION_SIZE): SessionPhase {
        val total = totalSteps.coerceAtLeast(4)
        val step = stepNumber.coerceIn(1, total)

        if (total == DEFAULT_SESSION_SIZE) {
            return when (step) {
                1, 2 -> SessionPhase.WARMUP
                3, 4, 5 -> SessionPhase.REVIEW_AND_MISTAKES
                7 -> SessionPhase.DEFENSE_CHECK
                6, 8 -> SessionPhase.CORE_INTERLEAVED
                else -> SessionPhase.DEEP_CALCULATION
            }
        }

        val ratio = step.toDouble() / total
        return when {
            ratio <= 0.22 -> SessionPhase.WARMUP
            ratio <= 0.52 -> SessionPhase.REVIEW_AND_MISTAKES
            step == (total * 0.7).roundToInt() -> SessionPhase.DEFENSE_CHECK
            ratio <= 0.80 -> SessionPhase.CORE_INTERLEAVED
            else -> SessionPhase.DEEP_CALCULATION
        }
    }

    /**
     * Целевое время быстрого распознавания паттерна (в секундах) для режима «Зоркость».
     * Не отображается как тикающий таймер во время хода — используется только в спокойной
     * пост-оценке беглости после решения.
     */
    fun visionTargetSeconds(exercise: Exercise): Int {
        val playerMoves = ((exercise.solutionMoves.size + 1) / 2).coerceAtLeast(1)
        val diff = exercise.getEffectiveDifficulty().coerceIn(0, 100)
        return (6 + playerMoves * 4 + diff / 12).coerceIn(8, 22)
    }

    /**
     * Пост-оценка беглости для режима [TrainingMode.PatternVision] или [TrainingMode.DeepCalculation].
     */
    fun formatCognitiveModeFeedback(
        mode: TrainingMode,
        exercise: Exercise,
        solveTimeSeconds: Int,
        isCleanFirstTry: Boolean
    ): String? {
        if (!isCleanFirstTry) return null
        return when (mode) {
            TrainingMode.PatternVision -> {
                val target = visionTargetSeconds(exercise)
                if (solveTimeSeconds <= target) {
                    "Мгновенное распознавание (${solveTimeSeconds} с, ориентир ≤ $target с)"
                } else {
                    "Решено чисто за ${solveTimeSeconds} с (ориентир беглости ≤ $target с)"
                }
            }
            TrainingMode.DeepCalculation -> {
                val playerMoves = ((exercise.solutionMoves.size + 1) / 2).coerceAtLeast(1)
                if (playerMoves >= 2) {
                    "Точный расчёт на $playerMoves хода с 1-й попытки (${solveTimeSeconds} с)"
                } else {
                    "Точный расчёт с 1-й попытки (${solveTimeSeconds} с)"
                }
            }
            else -> null
        }
    }

    /**
     * Формирует итоговый отчёт и персональный совет тренера после завершения сессии.
     */
    fun buildSessionSummary(
        totalExercises: Int,
        cleanFirstTryCount: Int,
        solvedTotalCount: Int,
        failedOrRevealedCount: Int,
        impulsiveMistakeCount: Int,
        netRatingDelta: Int,
        totalTimeSeconds: Int,
        missedPatternNames: List<String>,
        skippedCount: Int = 0
    ): SessionSummary {
        val safeTotal = totalExercises.coerceAtLeast(1)
        val safeSkipped = skippedCount.coerceIn(0, safeTotal)
        val attemptedCount = (safeTotal - safeSkipped).coerceAtLeast(0)
        val cleanClamped = cleanFirstTryCount.coerceIn(0, attemptedCount.coerceAtLeast(0))
        val firstTryPct = if (attemptedCount > 0) {
            ((cleanClamped * 100.0) / attemptedCount).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val avgSeconds = if (attemptedCount > 0) {
            (totalTimeSeconds.toDouble() / attemptedCount).roundToInt().coerceAtLeast(1)
        } else {
            0
        }
        val distinctMissed = missedPatternNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)

        val headline = when {
            attemptedCount == 0 -> "Все позиции пропущены"
            firstTryPct >= 90 -> "Блестящая сессия!"
            firstTryPct >= 70 -> "Отличная рабочая тренировка!"
            firstTryPct >= 50 -> "Хорошая нагрузка в зоне роста"
            else -> "Сложная сессия — точки роста найдены"
        }

        val baseTakeaway = when {
            attemptedCount == 0 -> {
                "Позиции были пропущены без попытки — выберите более комфортный темп или прицельную тему."
            }
            impulsiveMistakeCount >= 2 -> {
                "Замечено $impulsiveMistakeCount поспешных первых хода. В следующей сессии делайте короткую паузу и проверяйте все шахи и взятия соперника до хода."
            }
            firstTryPct >= 85 && avgSeconds <= 18 -> {
                "Высокая точность и отличная скорость распознавания! Попробуйте режим «Расчёт» для тренировки длинных форсированных вариантов."
            }
            distinctMissed.isNotEmpty() -> {
                val focus = distinctMissed.take(2).joinToString(", ")
                "Ошибочные позиции поставлены в расписание повторения. Обратите внимание на мотивы: $focus."
            }
            firstTryPct >= 70 -> {
                "Оптимальный баланс сложности (70–85% с 1-й попытки): именно в этой зоне тактический навык растёт быстрее всего."
            }
            else -> {
                "Не спешите с первым ходом: проваленные задачи вернутся по интервальному расписанию, когда мозг будет готов закрепить идею."
            }
        }
        val coachTakeaway = if (safeSkipped > 0 && attemptedCount > 0) {
            "$baseTakeaway Пропущенные без хода позиции ($safeSkipped) не засчитаны как ошибки и не отмечены слабыми темами."
        } else {
            baseTakeaway
        }

        return SessionSummary(
            totalExercises = safeTotal,
            cleanFirstTryCount = cleanClamped,
            solvedTotalCount = solvedTotalCount.coerceIn(0, attemptedCount),
            failedOrRevealedCount = failedOrRevealedCount.coerceAtLeast(0),
            impulsiveMistakeCount = impulsiveMistakeCount.coerceAtLeast(0),
            firstTryAccuracyPercent = firstTryPct,
            netRatingDelta = netRatingDelta,
            averageTimeSeconds = avgSeconds,
            missedPatternNames = distinctMissed,
            headline = headline,
            coachTakeaway = coachTakeaway,
            skippedCount = safeSkipped
        )
    }
}
