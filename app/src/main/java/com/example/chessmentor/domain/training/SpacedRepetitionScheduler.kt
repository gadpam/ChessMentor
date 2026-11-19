// domain/training/SpacedRepetitionScheduler.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import kotlin.math.roundToLong

/**
 * Кодирование обучающих метаданных попытки внутри [ExerciseAttempt.movesMade]
 * без изменения схемы БД Room (полная обратная совместимость со старыми записями).
 */
object AttemptMetadata {
    private const val META_PREFIX = "@"
    private const val TAG_HINT_PIECE = "@HINT:PIECE"
    private const val TAG_HINT_FULL = "@HINT:FULL"
    private const val TAG_REVEALED = "@REVEALED"
    private const val PREFIX_RETRY = "@RETRY:"
    private const val PREFIX_FIRST_MS = "@FIRST_MS:"
    const val TAG_IMPULSE = "@IMPULSE"

    fun buildTaggedMoves(
        rawUciMoves: List<String>,
        hintUsage: HintUsage = HintUsage.NONE,
        retryCount: Int = 0,
        firstMoveThinkMs: Long? = null,
        solutionRevealed: Boolean = false,
        wasImpulsive: Boolean = false
    ): List<String> = buildList {
        rawUciMoves.filter { it.isNotBlank() && !it.startsWith(META_PREFIX) }.forEach(::add)
        when {
            solutionRevealed -> add(TAG_REVEALED)
            hintUsage == HintUsage.FULL_SOLUTION -> add(TAG_HINT_FULL)
            hintUsage == HintUsage.PIECE_HIGHLIGHT -> add(TAG_HINT_PIECE)
            else -> Unit
        }
        if (retryCount > 0) {
            add("$PREFIX_RETRY$retryCount")
        }
        if (firstMoveThinkMs != null && firstMoveThinkMs >= 0L) {
            add("$PREFIX_FIRST_MS${firstMoveThinkMs.coerceAtMost(3_600_000L)}")
        }
        if (wasImpulsive) {
            add(TAG_IMPULSE)
        }
    }

    fun extractActualMoves(attempt: ExerciseAttempt): List<String> =
        attempt.movesMade.filter { it.isNotBlank() && !it.startsWith(META_PREFIX) }

    fun extractHintUsage(attempt: ExerciseAttempt): HintUsage {
        val items = attempt.movesMade
        return when {
            items.any { it == TAG_HINT_FULL || it == TAG_REVEALED } -> HintUsage.FULL_SOLUTION
            items.any { it == TAG_HINT_PIECE } -> HintUsage.PIECE_HIGHLIGHT
            else -> HintUsage.NONE
        }
    }

    fun isSolutionRevealed(attempt: ExerciseAttempt): Boolean =
        attempt.movesMade.any { it == TAG_REVEALED }

    fun extractRetryCount(attempt: ExerciseAttempt): Int =
        attempt.movesMade
            .firstOrNull { it.startsWith(PREFIX_RETRY) }
            ?.removePrefix(PREFIX_RETRY)
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0

    fun extractFirstMoveThinkMs(attempt: ExerciseAttempt): Long? =
        attempt.movesMade
            .firstOrNull { it.startsWith(PREFIX_FIRST_MS) }
            ?.removePrefix(PREFIX_FIRST_MS)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    fun isImpulsive(attempt: ExerciseAttempt): Boolean =
        attempt.movesMade.any { it == TAG_IMPULSE }
}

/**
 * Когнитивное качество одного подхода к задаче (шкала 1..5 по аналогии с SM-2).
 */
enum class AttemptQuality(val sm2Score: Int) {
    /** Не решил, ошибся без дорешивания или посмотрел готовый ответ. */
    FAILED(1),

    /** Дорешал со 2-й+ попытки после показа опровержения (методом исключения). */
    RETRY_SOLVE(2),

    /** Решил с 1-й попытки, но использовал подсказку фигурой. */
    HINT_SOLVE(3),

    /** Решил чисто с 1-й попытки без подсказок, но заметно дольше нормы беглости. */
    SLOW_CLEAN_SOLVE(4),

    /** Решил чисто с 1-й попытки, без подсказок и уверенно по времени. */
    CLEAN_FAST_SOLVE(5);

    val isCleanFirstTry: Boolean
        get() = this == CLEAN_FAST_SOLVE || this == SLOW_CLEAN_SOLVE
}

/**
 * Один целостный «подход» пользователя к задаче (объединяет ошибку и немедленное
 * дорешивание той же позиции в рамках нескольких минут).
 */
data class ExerciseSitting(
    val exerciseId: Long,
    val quality: AttemptQuality,
    val startedAt: Long,
    val finishedAt: Long,
    val timeSpentSeconds: Int,
    val firstMoveThinkMs: Long?,
    val mistakeCount: Int,
    val hintUsage: HintUsage,
    val wasImpulsive: Boolean
)

/**
 * Состояние карточки интервального повторения (гибрид коробок Лейтнера 0..5 и SM-2).
 */
data class ReviewCardState(
    val exerciseId: Long,
    /** Коробка Лейтнера: 0 — провалена, 1..3 — в изучении, 4..5 — закреплена в памяти. */
    val box: Int,
    /** Индивидуальный коэффициент лёгкости SM-2 (1.3..2.8). */
    val easeFactor: Double,
    /** Текущий интервал повторения в миллисекундах. */
    val intervalMs: Long,
    /** Время последней попытки. */
    val lastAttemptAt: Long,
    /** Расчётный момент следующего показа. */
    val nextReviewAt: Long,
    /** Качество последнего подхода. */
    val lastQuality: AttemptQuality,
    /** Число подряд идущих чистых решений с 1-й попытки без подсказок. */
    val consecutiveCleanSolves: Int,
    /** Сколько раз задача срывалась в ошибку. */
    val lapses: Int,
    /** Общее число подходов к задаче. */
    val totalSittings: Int,
    /** Число чистых решений с 1-й попытки без подсказок. */
    val cleanFirstTrySolves: Int
) {
    /** Подошёл ли срок повторения к моменту [nowMs]. */
    fun isDue(nowMs: Long): Boolean = nowMs >= nextReviewAt

    /** Находится ли задача в периоде охлаждения/ожидания (показывать рано). */
    fun isWaiting(nowMs: Long): Boolean = nowMs < nextReviewAt

    /** Насколько просрочено повторение (0, если срок ещё не наступил). */
    fun overdueMs(nowMs: Long): Long = (nowMs - nextReviewAt).coerceAtLeast(0L)

    /** Задача считается устойчиво освоенной начиная с 4-й коробки (интервал >= 21 дня). */
    val isMastered: Boolean
        get() = box >= SpacedRepetitionScheduler.MASTERED_MIN_BOX && lastQuality.isCleanFirstTry

    /** Проблемная задача («пиявка»), в которой игрок срывался многократно. */
    val isLeech: Boolean
        get() = lapses >= 3 && box <= 1

    /** Требует ли задача обязательного закрепления (провалена, решена со 2-й попытки или с подсказкой). */
    val needsConsolidation: Boolean
        get() = box <= 1 || !lastQuality.isCleanFirstTry
}

/**
 * Планировщик интервального повторения тактических задач (Лейтнер + SM-2).
 *
 * Ключевые педагогические принципы:
 * 1. Ошибка + немедленное дорешивание со 2-й попытки НЕ считается выученной задачей —
 *    она попадает в коробку 1 с коротким интервалом (4 часа), чтобы в следующей сессии
 *    игрок решил её самостоятельно с первого хода.
 * 2. Проваленная или подсмотренная задача уходит на 10-минутное охлаждение (коробка 0),
 *    а не выскакивает сразу же, пока ответ ещё держится в кратковременной памяти.
 * 3. Решение с подсказкой фигурой фиксируется в коробке 1 (повтор через 1 день без подсказки).
 * 4. Медленное чистое решение продвигает карточку осторожнее, тренируя скорость распознавания
 *    паттерна (принцип «метода дятла»).
 * 5. Чистые уверенные решения идут по растущей кривой забывания:
 *    1 дн. → 3 дн. → 7 дн. → 21 дн. → ~50–90 дн. (с поправкой на SM-2 easeFactor).
 */
object SpacedRepetitionScheduler {

    const val MINUTE_MS: Long = 60_000L
    const val HOUR_MS: Long = 60L * MINUTE_MS
    const val DAY_MS: Long = 24L * HOUR_MS

    /** Окно объединения ошибки и немедленного дорешивания в один подход. */
    const val SAME_SITTING_WINDOW_MS: Long = 10L * MINUTE_MS

    /** Охлаждение после провала / показа ответа (коробка 0). */
    const val FAILURE_COOLDOWN_MS: Long = 10L * MINUTE_MS

    /** Интервал пересдачи после решения со 2-й+ попытки (коробка 1). */
    const val RETRY_CONSOLIDATION_MS: Long = 4L * HOUR_MS

    /** Базовые интервалы коробок Лейтнера. */
    const val BOX_1_INTERVAL_MS: Long = 1L * DAY_MS
    const val BOX_2_INTERVAL_MS: Long = 3L * DAY_MS
    const val BOX_3_INTERVAL_MS: Long = 7L * DAY_MS
    const val BOX_4_INTERVAL_MS: Long = 21L * DAY_MS
    const val MAX_INTERVAL_MS: Long = 90L * DAY_MS

    const val MAX_BOX: Int = 5
    const val MASTERED_MIN_BOX: Int = 4

    private const val INITIAL_EASE_FACTOR = 2.5
    private const val MIN_EASE_FACTOR = 1.3
    private const val MAX_EASE_FACTOR = 2.8

    /**
     * Ориентировочный порог «уверенного» времени решения (в секундах)
     * в зависимости от сложности задачи (0..100) и числа ходов игрока.
     * Если игрок решал дольше — паттерн найден расчётом, но ещё не автоматизирован.
     */
    fun fluencyThresholdSeconds(exercise: Exercise?): Int {
        if (exercise == null) return 45
        val diff = exercise.getEffectiveDifficulty().coerceIn(0, 100)
        val playerMoves = ((exercise.solutionMoves.size + 1) / 2).coerceAtLeast(1)
        val base = when {
            diff < 25 -> 25
            diff < 45 -> 40
            diff < 65 -> 60
            diff < 80 -> 85
            else -> 115
        }
        val multiMoveAllowance = (playerMoves - 1) * 18
        return base + multiMoveAllowance
    }

    /**
     * Группирует сырые записи [ExerciseAttempt] по одной задаче в логические подходы.
     */
    fun groupIntoSittings(
        exerciseAttempts: List<ExerciseAttempt>,
        exercise: Exercise? = null
    ): List<ExerciseSitting> {
        if (exerciseAttempts.isEmpty()) return emptyList()
        val sorted = exerciseAttempts.sortedWith(
            compareBy<ExerciseAttempt> { it.attemptedAt }.thenBy { it.id ?: 0L }
        )
        val fluencySec = fluencyThresholdSeconds(exercise)
        val result = mutableListOf<ExerciseSitting>()

        var i = 0
        while (i < sorted.size) {
            val first = sorted[i]
            if (first.solved) {
                result.add(buildSingleSolvedSitting(first, extraFailures = 0, fluencySec = fluencySec))
                i++
                continue
            }

            // Накапливаем серию ошибок в рамках одного подхода (<= 10 минут между записями)
            var failures = 1
            var lastTime = first.attemptedAt
            var totalTimeSec = first.timeSpentSeconds.coerceAtLeast(0)
            var firstThinkMs = AttemptMetadata.extractFirstMoveThinkMs(first)
            var maxHint = AttemptMetadata.extractHintUsage(first)
            var anyImpulsive = AttemptMetadata.isImpulsive(first)
            var resolvedAttempt: ExerciseAttempt? = null

            var j = i + 1
            while (j < sorted.size) {
                val next = sorted[j]
                val gap = (next.attemptedAt - lastTime).coerceAtLeast(0L)
                if (gap > SAME_SITTING_WINDOW_MS) break

                val nextHint = AttemptMetadata.extractHintUsage(next)
                if (nextHint.ordinal > maxHint.ordinal) maxHint = nextHint
                if (firstThinkMs == null) firstThinkMs = AttemptMetadata.extractFirstMoveThinkMs(next)
                if (AttemptMetadata.isImpulsive(next)) anyImpulsive = true

                if (next.solved) {
                    resolvedAttempt = next
                    lastTime = next.attemptedAt
                    // В TrainingViewModel финальная запись содержит общее время с начала задачи
                    totalTimeSec = maxOf(totalTimeSec, next.timeSpentSeconds.coerceAtLeast(0))
                    j++
                    break
                } else {
                    failures++
                    lastTime = next.attemptedAt
                    totalTimeSec = maxOf(totalTimeSec, next.timeSpentSeconds.coerceAtLeast(0))
                    j++
                }
            }

            if (resolvedAttempt != null) {
                val taggedRetries = AttemptMetadata.extractRetryCount(resolvedAttempt)
                val mistakes = maxOf(failures, taggedRetries)
                val resolvedQuality = if (maxHint == HintUsage.FULL_SOLUTION ||
                    AttemptMetadata.isSolutionRevealed(resolvedAttempt)
                ) {
                    AttemptQuality.FAILED
                } else {
                    AttemptQuality.RETRY_SOLVE
                }
                result.add(
                    ExerciseSitting(
                        exerciseId = first.exerciseId,
                        quality = resolvedQuality,
                        startedAt = first.attemptedAt,
                        finishedAt = lastTime,
                        timeSpentSeconds = totalTimeSec,
                        firstMoveThinkMs = firstThinkMs,
                        mistakeCount = mistakes,
                        hintUsage = maxHint,
                        wasImpulsive = anyImpulsive
                    )
                )
            } else {
                result.add(
                    ExerciseSitting(
                        exerciseId = first.exerciseId,
                        quality = AttemptQuality.FAILED,
                        startedAt = first.attemptedAt,
                        finishedAt = lastTime,
                        timeSpentSeconds = totalTimeSec,
                        firstMoveThinkMs = firstThinkMs,
                        mistakeCount = failures,
                        hintUsage = maxHint,
                        wasImpulsive = anyImpulsive
                    )
                )
            }

            i = j
        }

        return result
    }

    private fun buildSingleSolvedSitting(
        attempt: ExerciseAttempt,
        extraFailures: Int,
        fluencySec: Int
    ): ExerciseSitting {
        val hint = AttemptMetadata.extractHintUsage(attempt)
        val revealed = AttemptMetadata.isSolutionRevealed(attempt)
        val retries = maxOf(extraFailures, AttemptMetadata.extractRetryCount(attempt))
        val spent = attempt.timeSpentSeconds.coerceAtLeast(0)
        // Игнорируем свёрнутое в фон приложение (> 10 минут) при проверке беглости
        val isSlow = spent in (fluencySec + 1)..600

        val quality = when {
            revealed || hint == HintUsage.FULL_SOLUTION -> AttemptQuality.FAILED
            retries > 0 -> AttemptQuality.RETRY_SOLVE
            hint == HintUsage.PIECE_HIGHLIGHT -> AttemptQuality.HINT_SOLVE
            isSlow -> AttemptQuality.SLOW_CLEAN_SOLVE
            else -> AttemptQuality.CLEAN_FAST_SOLVE
        }

        return ExerciseSitting(
            exerciseId = attempt.exerciseId,
            quality = quality,
            startedAt = attempt.attemptedAt,
            finishedAt = attempt.attemptedAt,
            timeSpentSeconds = spent,
            firstMoveThinkMs = AttemptMetadata.extractFirstMoveThinkMs(attempt),
            mistakeCount = retries,
            hintUsage = hint,
            wasImpulsive = AttemptMetadata.isImpulsive(attempt)
        )
    }

    /**
     * Вычисляет текущее состояние карточки интервального повторения для одной задачи.
     */
    fun computeCardState(
        exerciseId: Long,
        exerciseAttempts: List<ExerciseAttempt>,
        exercise: Exercise? = null
    ): ReviewCardState? {
        val sittings = groupIntoSittings(exerciseAttempts, exercise)
        if (sittings.isEmpty()) return null

        var box = 0
        var ef = INITIAL_EASE_FACTOR
        var intervalMs = 0L
        var consecutiveClean = 0
        var lapses = 0
        var cleanFirstTry = 0
        var hadPriorAttempt = false

        for (sitting in sittings) {
            val q = sitting.quality
            ef = updateEaseFactor(ef, q.sm2Score)

            when (q) {
                AttemptQuality.FAILED -> {
                    box = 0
                    consecutiveClean = 0
                    lapses++
                    intervalMs = FAILURE_COOLDOWN_MS
                }

                AttemptQuality.RETRY_SOLVE -> {
                    // Дорешал со 2-й попытки: фиксируем срыв и ставим короткий интервал (4 ч)
                    box = 1
                    consecutiveClean = 0
                    lapses++
                    intervalMs = RETRY_CONSOLIDATION_MS
                }

                AttemptQuality.HINT_SOLVE -> {
                    // Решил с подсказкой фигурой: повторить через 1 день самостоятельно
                    box = 1
                    consecutiveClean = 0
                    intervalMs = BOX_1_INTERVAL_MS
                }

                AttemptQuality.SLOW_CLEAN_SOLVE -> {
                    cleanFirstTry++
                    consecutiveClean++
                    val nextBox = if (!hadPriorAttempt) {
                        // Новая задача решена чисто, но долго -> коробка 1 (повтор через 1 день)
                        1
                    } else {
                        (box + 1).coerceAtMost(MAX_BOX)
                    }
                    box = nextBox
                    intervalMs = computeCleanIntervalMs(
                        newBox = box,
                        prevIntervalMs = intervalMs,
                        easeFactor = ef,
                        isSlow = true,
                        lapses = lapses
                    )
                }

                AttemptQuality.CLEAN_FAST_SOLVE -> {
                    cleanFirstTry++
                    consecutiveClean++
                    val nextBox = if (!hadPriorAttempt) {
                        // С первой попытки и быстро: сразу коробка 2 (проверка через 3 дня)
                        2
                    } else {
                        (box + 1).coerceAtMost(MAX_BOX)
                    }
                    box = nextBox
                    intervalMs = computeCleanIntervalMs(
                        newBox = box,
                        prevIntervalMs = intervalMs,
                        easeFactor = ef,
                        isSlow = false,
                        lapses = lapses
                    )
                }
            }

            hadPriorAttempt = true
        }

        val last = sittings.last()
        return ReviewCardState(
            exerciseId = exerciseId,
            box = box,
            easeFactor = ef,
            intervalMs = intervalMs,
            lastAttemptAt = last.finishedAt,
            nextReviewAt = last.finishedAt + intervalMs,
            lastQuality = last.quality,
            consecutiveCleanSolves = consecutiveClean,
            lapses = lapses,
            totalSittings = sittings.size,
            cleanFirstTrySolves = cleanFirstTry
        )
    }

    /**
     * Строит карту состояний повторения по всем задачам, с которыми сталкивался пользователь.
     */
    fun buildSchedule(
        attempts: List<ExerciseAttempt>,
        exercisesById: Map<Long, Exercise> = emptyMap()
    ): Map<Long, ReviewCardState> {
        if (attempts.isEmpty()) return emptyMap()
        return attempts
            .groupBy { it.exerciseId }
            .mapNotNull { (exerciseId, list) ->
                computeCardState(exerciseId, list, exercisesById[exerciseId])
                    ?.let { exerciseId to it }
            }
            .toMap()
    }

    /**
     * Отбирает задачи, срок повторения которых наступил к моменту [nowMs],
     * в порядке педагогического приоритета:
     * 1) более низкая коробка Лейтнера (проваленные и нестабильные первыми);
     * 2) собственные ошибки игрока при равной коробке;
     * 3) наибольшая относительная просрочка.
     */
    fun rankDueExercises(
        accessibleExercises: List<Exercise>,
        schedule: Map<Long, ReviewCardState>,
        nowMs: Long,
        excludeId: Long = -1L,
        filter: (Exercise) -> Boolean = { true }
    ): List<Pair<Exercise, ReviewCardState>> {
        if (accessibleExercises.isEmpty() || schedule.isEmpty()) return emptyList()

        return accessibleExercises
            .asSequence()
            .filter { ex ->
                val id = ex.id
                id != null && id != excludeId && filter(ex)
            }
            .mapNotNull { ex ->
                val state = schedule[ex.id!!] ?: return@mapNotNull null
                if (state.isDue(nowMs)) ex to state else null
            }
            .sortedWith(
                compareBy<Pair<Exercise, ReviewCardState>> { (_, st) -> st.box }
                    .thenBy { (ex, _) -> if (ex.sourceGameId != null) 0 else 1 }
                    .thenByDescending { (_, st) ->
                        val denom = st.intervalMs.coerceAtLeast(MINUTE_MS).toDouble()
                        st.overdueMs(nowMs) / denom
                    }
                    .thenBy { (_, st) -> st.nextReviewAt }
            )
            .toList()
    }

    /**
     * Выбирает лучшую задачу для внеочередного повторения, когда все новые задачи исчерпаны
     * и ни у одной карточки ещё не истёк интервал (начиная с наименее закреплённых и
     * максимально близких к сроку показа, но не показывая задачу повторно в первые 2 минуты,
     * если есть альтернативы).
     */
    fun pickFallbackReview(
        accessibleExercises: List<Exercise>,
        schedule: Map<Long, ReviewCardState>,
        nowMs: Long,
        excludeId: Long = -1L,
        filter: (Exercise) -> Boolean = { true }
    ): Pair<Exercise, ReviewCardState>? {
        val candidates = accessibleExercises.mapNotNull { ex ->
            val id = ex.id ?: return@mapNotNull null
            if (id == excludeId || !filter(ex)) return@mapNotNull null
            val st = schedule[id] ?: return@mapNotNull null
            ex to st
        }
        if (candidates.isEmpty()) return null

        val minFreshGap = 2L * MINUTE_MS
        val rested = candidates.filter { (_, st) -> nowMs - st.lastAttemptAt >= minFreshGap }
        val pool = rested.ifEmpty { candidates }

        return pool.minWithOrNull(
            compareBy<Pair<Exercise, ReviewCardState>> { (_, st) -> st.box }
                .thenBy { (_, st) -> st.nextReviewAt }
        )
    }

    /**
     * Краткий баннер при открытии задачи, пришедшей по расписанию интервального повторения.
     */
    fun formatReviewBanner(state: ReviewCardState): String = when (state.lastQuality) {
        AttemptQuality.FAILED ->
            if (state.isLeech) {
                "🔁 Сложный для вас мотив (срывов: ${state.lapses}) — посчитайте до конца"
            } else {
                "🔁 Повтор задачи после ошибки — найдите решение с 1-й попытки"
            }

        AttemptQuality.RETRY_SOLVE ->
            "🔁 Закрепление: в прошлый раз решено со 2-й попытки — теперь с первого хода"

        AttemptQuality.HINT_SOLVE ->
            "🔁 Закрепление: решите эту позицию самостоятельно без подсказки"

        AttemptQuality.SLOW_CLEAN_SOLVE ->
            "📅 Повтор на скорость распознавания (уровень ${state.box}/$MAX_BOX)"

        AttemptQuality.CLEAN_FAST_SOLVE ->
            "📅 Интервальное повторение (уровень ${state.box}/$MAX_BOX)"
    }

    /**
     * Краткая подпись следующего интервала после завершения задачи.
     */
    fun formatNextIntervalNote(state: ReviewCardState): String {
        val intervalText = formatDurationShort(state.intervalMs)
        return when (state.lastQuality) {
            AttemptQuality.FAILED ->
                "📅 Вернёмся к задаче через $intervalText"

            AttemptQuality.RETRY_SOLVE ->
                "📅 Повтор через $intervalText (закрепить с 1-й попытки)"

            AttemptQuality.HINT_SOLVE ->
                "📅 Повтор через $intervalText (без подсказки)"

            AttemptQuality.SLOW_CLEAN_SOLVE ->
                "📅 Уровень ${state.box}/$MAX_BOX • повтор через $intervalText (тренируем беглость)"

            AttemptQuality.CLEAN_FAST_SOLVE ->
                if (state.isMastered) {
                    "🏅 Закреплено (уровень ${state.box}/$MAX_BOX) • повтор через $intervalText"
                } else {
                    "📅 Уровень ${state.box}/$MAX_BOX • повтор через $intervalText"
                }
        }
    }

    fun formatDurationShort(ms: Long): String {
        val clamped = ms.coerceAtLeast(MINUTE_MS)
        val days = (clamped.toDouble() / DAY_MS).roundToLong()
        if (clamped >= 20L * HOUR_MS && days >= 1L) {
            return when {
                days == 1L -> "1 дн."
                else -> "$days дн."
            }
        }
        val hours = (clamped.toDouble() / HOUR_MS).roundToLong()
        if (clamped >= 60L * MINUTE_MS && hours >= 1L) {
            return "$hours ч"
        }
        val minutes = (clamped.toDouble() / MINUTE_MS).roundToLong().coerceAtLeast(1L)
        return "$minutes мин"
    }

    private fun updateEaseFactor(currentEf: Double, q: Int): Double {
        val score = q.coerceIn(0, 5)
        val delta = 0.1 - (5 - score) * (0.08 + (5 - score) * 0.02)
        return (currentEf + delta).coerceIn(MIN_EASE_FACTOR, MAX_EASE_FACTOR)
    }

    private fun computeCleanIntervalMs(
        newBox: Int,
        prevIntervalMs: Long,
        easeFactor: Double,
        isSlow: Boolean,
        lapses: Int
    ): Long {
        val base = when (newBox) {
            1 -> BOX_1_INTERVAL_MS
            2 -> BOX_2_INTERVAL_MS
            3 -> BOX_3_INTERVAL_MS
            4 -> {
                val scaled = if (prevIntervalMs >= BOX_3_INTERVAL_MS) {
                    (prevIntervalMs * easeFactor).roundToLong()
                } else {
                    BOX_4_INTERVAL_MS
                }
                scaled.coerceIn(14L * DAY_MS, 30L * DAY_MS)
            }
            else -> {
                val anchor = prevIntervalMs.coerceAtLeast(BOX_4_INTERVAL_MS)
                (anchor * easeFactor).roundToLong().coerceIn(30L * DAY_MS, MAX_INTERVAL_MS)
            }
        }

        // Медленное решение и частые срывы укорачивают интервал, чтобы прочнее закрепить мотив
        var adjusted = base.toDouble()
        if (isSlow && newBox >= 2) {
            adjusted *= 0.72
        }
        if (lapses >= 2 && newBox >= 2) {
            adjusted *= 0.85
        }
        return adjusted.roundToLong().coerceIn(BOX_1_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
