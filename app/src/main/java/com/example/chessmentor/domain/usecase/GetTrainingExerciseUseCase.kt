// domain/usecase/GetTrainingExerciseUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.ReviewCardState
import com.example.chessmentor.domain.training.SessionPhase
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import com.example.chessmentor.domain.training.TrainingSessionPlanner
import com.example.chessmentor.domain.util.ChessMoveUtils
import kotlin.math.abs
import kotlin.random.Random

/**
 * Use Case: Адаптивный подбор тактического упражнения с настоящим интервальным
 * повторением (Лейтнер + SM-2):
 * - автоматическое наполнение встроенного банка задач;
 * - строгая изоляция пользовательских задач по `userId`;
 * - интервальное повторение по кривой забывания (10 мин → 4 ч → 1 дн. → 3 дн. → 7 дн. → 21+ дн.)
 *   с соблюдением охлаждения после свежей ошибки и возвратом задач, решённых с подсказкой
 *   или со 2-й попытки;
 * - подбор новых задач в «зоне ближайшего развития» игрока с приоритетом его собственных партий;
 * - поддержка всех режимов [TrainingMode].
 */
class GetTrainingExerciseUseCase(
    private val userRepository: UserRepository,
    private val mistakeRepository: MistakeRepository,
    private val exerciseRepository: ExerciseRepository
) {
    companion object {
        private const val TAG = "GetTrainingExercise"
        private const val ZPD_HALF_WINDOW = 18
    }

    data class Input(
        val userId: Long,
        val excludeExerciseId: Long = -1,
        /** Набор уже сыгранных в текущей сессии ID задач — исключает повторы внутри сессии. */
        val excludeExerciseIds: Set<Long> = emptySet(),
        val preferredPattern: String? = null,
        /** Последний сыгранный мотив — по возможности не выдаём два одинаковых подряд (interleaving). */
        val avoidPattern: String? = null,
        /** Фаза структурированной сессии (для [TrainingMode.SmartSession]). */
        val sessionPhase: SessionPhase? = null,
        val mode: TrainingMode = TrainingMode.All,
        val epochDay: Long = CuratedExerciseBank.currentEpochDay(),
        val nowMs: Long = System.currentTimeMillis()
    )

    sealed class Result {
        data class Success(
            val exercise: Exercise,
            val isAlreadySolvedDaily: Boolean = false,
            val isReviewOfFailed: Boolean = false,
            val reviewState: ReviewCardState? = null,
            val dueReviewCount: Int = 0,
            val completedRatedSittings: Int = 0
        ) : Result()

        data class Error(val message: String) : Result()
        object NoSuitableExercises : Result()
    }

    private data class LearningSnapshot(
        val accessible: List<Exercise>,
        val exercisesById: Map<Long, Exercise>,
        val schedule: Map<Long, ReviewCardState>,
        val dueReviewCount: Int,
        val completedRatedSittings: Int
    )

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val userId = user.id ?: return Result.Error("Некорректный идентификатор пользователя")

        // Гарантируем, что встроенный банк тактических задач доступен
        if (input.mode !is TrainingMode.MyMistakes) {
            CuratedExerciseBank.ensureSeeded(exerciseRepository)
        }

        val baseSnapshot = loadLearningSnapshot(userId, input.nowMs)
        // Если передан список исключённых задач сессии — фильтруем пул заранее
        val snapshot = if (input.excludeExerciseIds.isNotEmpty()) {
            baseSnapshot.copy(
                accessible = baseSnapshot.accessible.filter { ex ->
                    val id = ex.id
                    id == null || id !in input.excludeExerciseIds
                }
            )
        } else {
            baseSnapshot
        }

        Log.d(
            TAG,
            "Selecting exercise user=$userId mode=${input.mode.routeKey} due=${snapshot.dueReviewCount} exclude=${input.excludeExerciseId}"
        )

        return when (val mode = input.mode) {
            TrainingMode.Daily -> selectDailyExercise(userId, input.epochDay, baseSnapshot)
            TrainingMode.SmartSession -> selectSmartSessionExercise(
                user = user,
                userId = userId,
                phase = input.sessionPhase ?: TrainingSessionPlanner.phaseForStep(1),
                excludeId = input.excludeExerciseId,
                preferredPattern = input.preferredPattern,
                avoidPattern = input.avoidPattern,
                nowMs = input.nowMs,
                snapshot = snapshot
            )
            TrainingMode.PatternVision -> selectPatternVisionExercise(
                user = user,
                excludeId = input.excludeExerciseId,
                avoidPattern = input.avoidPattern,
                nowMs = input.nowMs,
                snapshot = snapshot,
                difficultyOffset = -10
            )
            TrainingMode.DeepCalculation -> selectDeepCalculationExercise(
                user = user,
                excludeId = input.excludeExerciseId,
                avoidPattern = input.avoidPattern,
                nowMs = input.nowMs,
                snapshot = snapshot,
                difficultyOffset = 10
            )
            TrainingMode.MyMistakes -> selectUserMistakeExercise(user, userId, input.excludeExerciseId, input.avoidPattern, input.nowMs, snapshot)
            TrainingMode.WeakPatterns -> selectWeakPatternExercise(user, userId, input.excludeExerciseId, input.avoidPattern, input.nowMs, snapshot)
            TrainingMode.Defense -> selectDefenseExercise(user, input.excludeExerciseId, input.avoidPattern, input.nowMs, snapshot)
            is TrainingMode.ByTheme -> selectThemeExercise(user, input.excludeExerciseId, mode.theme, input.avoidPattern, input.nowMs, snapshot)
            TrainingMode.All -> selectAdaptiveExercise(
                user = user,
                userId = userId,
                excludeId = input.excludeExerciseId,
                preferredPattern = input.preferredPattern,
                avoidPattern = input.avoidPattern,
                nowMs = input.nowMs,
                snapshot = snapshot
            )
        }
    }

    private suspend fun loadLearningSnapshot(userId: Long, nowMs: Long): LearningSnapshot {
        val accessible = exerciseRepository.findAccessibleByUserId(userId)
        val byId = accessible.mapNotNull { ex -> ex.id?.let { it to ex } }.toMap()
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
        val schedule = SpacedRepetitionScheduler.buildSchedule(attempts, byId)
        val dueCount = accessible.count { ex ->
            val id = ex.id ?: return@count false
            schedule[id]?.isDue(nowMs) == true
        }
        val completedSittings = schedule.values.sumOf { it.totalSittings }
        return LearningSnapshot(
            accessible = accessible,
            exercisesById = byId,
            schedule = schedule,
            dueReviewCount = dueCount,
            completedRatedSittings = completedSittings
        )
    }

    private suspend fun selectDailyExercise(
        userId: Long,
        epochDay: Long,
        snapshot: LearningSnapshot
    ): Result {
        val (_, dailyExercise) = CuratedExerciseBank.getOrCreateDailyExercise(
            exerciseRepository = exerciseRepository,
            epochDay = epochDay
        )
        val alreadySolved = dailyExercise.id?.let { id ->
            val card = snapshot.schedule[id]
            // Считаем задачу дня решённой, если есть хотя бы одно самостоятельное решение
            if (card != null) {
                card.lastQuality != com.example.chessmentor.domain.training.AttemptQuality.FAILED ||
                    card.cleanFirstTrySolves > 0
            } else {
                exerciseRepository.hasUserSolvedExercise(userId, id)
            }
        } ?: false

        return Result.Success(
            exercise = dailyExercise,
            isAlreadySolvedDaily = alreadySolved,
            dueReviewCount = snapshot.dueReviewCount,
            completedRatedSittings = snapshot.completedRatedSittings
        )
    }

    private suspend fun selectUserMistakeExercise(
        user: User,
        userId: Long,
        excludeId: Long,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val isOwn: (Exercise) -> Boolean = { it.sourceGameId != null }
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Подошедшие к повторению задачи из своих партий (ошибки, 2-я попытка, с подсказкой, плановый SM-2)
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = isOwn
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 2. Ещё ни разу не решённые задачи из своих партий (не находящиеся в охлаждении)
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern,
            filter = isOwn
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        // 3. Резерв: создать из ещё не обработанной ошибки партии
        createFallbackExerciseFromMistake(user, userId)?.let { fallback ->
            return buildNewSuccess(fallback, snapshot)
        }

        // 4. Внеочередное повторение собственных задач (начиная с наименьшей коробки Лейтнера)
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = isOwn
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    private suspend fun selectWeakPatternExercise(
        user: User,
        userId: Long,
        excludeId: Long,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Все карточки, у которых наступил срок повторения (в первую очередь коробки 0..1)
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 2. Новые задачи по наиболее слабым тактическим мотивам (с чередованием, чтобы не давать один мотив подряд)
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
        if (attempts.isNotEmpty()) {
            val mastery = TrainingRatingCalculator.computePatternMastery(
                exercisesById = snapshot.exercisesById,
                attempts = attempts,
                playerElo = user.rating
            )
            val rawCandidates = mastery
                .filter { it.level == MasteryLevel.WEAK || it.level == MasteryLevel.LEARNING }
                .ifEmpty { mastery.take(3) }
            // Если слабых тем несколько, чередуем их (не повторяя только что сыгранную подряд)
            val candidatePatterns = if (!avoidPattern.isNullOrBlank() && rawCandidates.size >= 2) {
                val different = rawCandidates.filter { it.pattern.name != avoidPattern }
                val same = rawCandidates.filter { it.pattern.name == avoidPattern }
                different + same
            } else {
                rawCandidates
            }

            for (pm in candidatePatterns) {
                val patternFilter: (Exercise) -> Boolean = { it.tacticalPattern == pm.pattern }

                pickUnseenExercise(
                    accessible = snapshot.accessible,
                    schedule = snapshot.schedule,
                    targetDifficulty = targetDiff,
                    excludeId = excludeId,
                    prioritizeOwnMistakes = true,
                    filter = patternFilter
                )?.let { ex ->
                    return buildNewSuccess(ex, snapshot)
                }

                // Если новых по слабому мотиву нет — берём наименее закреплённую (не только что сыгранную)
                SpacedRepetitionScheduler.pickFallbackReview(
                    accessibleExercises = snapshot.accessible,
                    schedule = snapshot.schedule,
                    nowMs = nowMs,
                    excludeId = excludeId,
                    filter = { ex ->
                        ex.tacticalPattern == pm.pattern &&
                            (snapshot.schedule[ex.id]?.needsConsolidation == true ||
                                nowMs - (snapshot.schedule[ex.id]?.lastAttemptAt ?: 0L) >= SpacedRepetitionScheduler.FAILURE_COOLDOWN_MS)
                    }
                )?.let { (ex, state) ->
                    return buildReviewSuccess(ex, state, snapshot)
                }
            }
        }

        // 3. Если статистики пока мало — адаптивный подбор
        return selectAdaptiveExercise(
            user = user,
            userId = userId,
            excludeId = excludeId,
            preferredPattern = null,
            avoidPattern = avoidPattern,
            nowMs = nowMs,
            snapshot = snapshot
        )
    }

    /**
     * Структурированная осознанная тренировка из 10 задач:
     * 1–2 разминка («Зоркость») → 3–5 интервальный повтор и свои партии →
     * 6, 8 чередование мотивов → 7 проверка защиты → 9–10 глубокий расчёт.
     */
    private suspend fun selectSmartSessionExercise(
        user: User,
        userId: Long,
        phase: SessionPhase,
        excludeId: Long,
        preferredPattern: String?,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val primary = when (phase) {
            SessionPhase.WARMUP -> selectPatternVisionExercise(
                user = user,
                excludeId = excludeId,
                avoidPattern = avoidPattern,
                nowMs = nowMs,
                snapshot = snapshot,
                difficultyOffset = phase.difficultyOffset
            )
            SessionPhase.REVIEW_AND_MISTAKES -> {
                // Приоритет 1: карточки SM-2 по сроку; Приоритет 2: новые свои ошибки; Приоритет 3: слабые мотивы
                val ownOrDue = selectUserMistakeExercise(user, userId, excludeId, avoidPattern, nowMs, snapshot)
                if (ownOrDue is Result.Success) {
                    ownOrDue
                } else {
                    selectWeakPatternExercise(user, userId, excludeId, avoidPattern, nowMs, snapshot)
                }
            }
            SessionPhase.DEFENSE_CHECK -> selectDefenseExercise(
                user = user,
                excludeId = excludeId,
                avoidPattern = avoidPattern,
                nowMs = nowMs,
                snapshot = snapshot
            )
            SessionPhase.CORE_INTERLEAVED -> selectAdaptiveExercise(
                user = user,
                userId = userId,
                excludeId = excludeId,
                preferredPattern = preferredPattern,
                avoidPattern = avoidPattern,
                nowMs = nowMs,
                snapshot = snapshot
            )
            SessionPhase.DEEP_CALCULATION -> selectDeepCalculationExercise(
                user = user,
                excludeId = excludeId,
                avoidPattern = avoidPattern,
                nowMs = nowMs,
                snapshot = snapshot,
                difficultyOffset = phase.difficultyOffset
            )
        }

        if (primary is Result.Success) return primary
        return selectAdaptiveExercise(user, userId, excludeId, preferredPattern, avoidPattern, nowMs, snapshot)
    }

    /**
     * Режим «Зоркость» (Система 1 / метод «Дятла»): короткие (1–2 хода игрока),
     * кристально чёткие тактические мотивы чуть ниже/около текущего уровня для доведения
     * распознавания паттернов до автоматизма.
     */
    private fun selectPatternVisionExercise(
        user: User,
        excludeId: Long,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot,
        difficultyOffset: Int
    ): Result {
        val targetDiff = (TrainingRatingCalculator.eloToDifficulty(user.rating) + difficultyOffset)
            .coerceIn(10, 80)
        val maxVisionDiff = (targetDiff + 18).coerceAtMost(85)
        val visionFilter: (Exercise) -> Boolean = { ex ->
            ex.solutionMoves.size <= 3 && ex.getEffectiveDifficulty() <= maxVisionDiff
        }

        // 1. Подошедшие к повтору короткие паттерны (идеально для метода «Дятла»)
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = visionFilter
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 2. Свежие короткие паттерны с чередованием тем
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern,
            filter = visionFilter
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        // 3. Повтор изученных коротких паттернов (не находящихся в свежем 10-мин охлаждении) для скорости
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = { ex ->
                visionFilter(ex) && (avoidPattern == null || ex.tacticalPattern.name != avoidPattern)
            }
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = visionFilter
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    /**
     * Режим «Расчёт» (Система 2): многоходовые форсированные комбинации и защиты
     * (приоритет 3+ ходов игрока = 5+ полуходов, затем 2 хода = 3 полухода) чуть выше
     * текущего уровня для тренировки полноценного расчёта дерева вариантов до первого хода.
     */
    private fun selectDeepCalculationExercise(
        user: User,
        excludeId: Long,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot,
        difficultyOffset: Int
    ): Result {
        val targetDiff = (TrainingRatingCalculator.eloToDifficulty(user.rating) + difficultyOffset)
            .coerceIn(30, 95)
        val deepFilter: (Exercise) -> Boolean = { ex -> ex.solutionMoves.size >= 5 }
        val multiMoveFilter: (Exercise) -> Boolean = { ex -> ex.solutionMoves.size >= 3 }

        // 1. Подошедшие к повтору многоходовые задачи
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = multiMoveFilter
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 2. Свежие трёхходовые+ комбинации (5+ полуходов), если игрок уже готов (targetDiff >= 48)
        if (targetDiff >= 48) {
            pickUnseenExercise(
                accessible = snapshot.accessible,
                schedule = snapshot.schedule,
                targetDifficulty = targetDiff,
                excludeId = excludeId,
                prioritizeOwnMistakes = true,
                avoidPattern = avoidPattern,
                filter = deepFilter
            )?.let { ex ->
                return buildNewSuccess(ex, snapshot)
            }
        }

        // 3. Свежие многоходовые комбинации (3+ полуходов)
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern,
            filter = multiMoveFilter
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        // 4. Повтор наименее закреплённой многоходовой задачи
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = multiMoveFilter
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    /**
     * Подбор задач на защиту, профилактику, контрудар (цвишенцуг) и спасение тяжёлых позиций.
     */
    private fun selectDefenseExercise(
        user: User,
        excludeId: Long,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val defenseFilter: (Exercise) -> Boolean = { ex ->
            ExerciseMetadata.isDefensiveGoal(ex) || ex.theme == TacticalTheme.KING_SAFETY
        }
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = defenseFilter
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern,
            filter = defenseFilter
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = defenseFilter
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    private fun selectThemeExercise(
        user: User,
        excludeId: Long,
        theme: TacticalTheme,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val themeFilter: (Exercise) -> Boolean = { it.theme == theme }
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Подошедшие к повтору задачи выбранной темы
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = themeFilter
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 2. Новые (ещё не открывавшиеся) задачи выбранной темы в зоне ближайшего развития
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern,
            filter = themeFilter
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        // 3. Внеочередное повторение наименее закреплённой задачи темы
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = themeFilter
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    private suspend fun selectAdaptiveExercise(
        user: User,
        userId: Long,
        excludeId: Long,
        preferredPattern: String?,
        avoidPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Отложенное чередование мотива (Interleaved Practice): только среди новых или подошедших по сроку
        if (!preferredPattern.isNullOrBlank()) {
            val patternFilter: (Exercise) -> Boolean = { it.tacticalPattern.name == preferredPattern }

            SpacedRepetitionScheduler.rankDueExercises(
                accessibleExercises = snapshot.accessible,
                schedule = snapshot.schedule,
                nowMs = nowMs,
                excludeId = excludeId,
                filter = patternFilter
            ).firstOrNull()?.let { (ex, state) ->
                return buildReviewSuccess(ex, state, snapshot)
            }

            pickUnseenExercise(
                accessible = snapshot.accessible,
                schedule = snapshot.schedule,
                targetDifficulty = targetDiff,
                excludeId = excludeId,
                prioritizeOwnMistakes = true,
                filter = patternFilter
            )?.let { ex ->
                return buildNewSuccess(ex, snapshot)
            }
        }

        // 2. Интервальное повторение (SM-2 / Лейтнер): подошедшие к сроку карточки
        val dueList = SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId
        )
        val pickedDue = if (!avoidPattern.isNullOrBlank() && dueList.size >= 2) {
            dueList.firstOrNull { (ex, _) -> ex.tacticalPattern.name != avoidPattern } ?: dueList.first()
        } else {
            dueList.firstOrNull()
        }
        if (pickedDue != null) {
            return buildReviewSuccess(pickedDue.first, pickedDue.second, snapshot)
        }

        // 3. Новая задача подходящей сложности с чередованием мотивов (interleaving)
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            avoidPattern = avoidPattern
        )?.let { ex ->
            return buildNewSuccess(ex, snapshot)
        }

        // 4. Резерв: если есть ещё не сконвертированные ошибки партий
        createFallbackExerciseFromMistake(user, userId)?.let { fallback ->
            return buildNewSuccess(fallback, snapshot)
        }

        // 5. Если просмотрены все задачи банка — наименее закреплённая карточка
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        return Result.NoSuitableExercises
    }

    /**
     * Подбирает ещё не встречавшуюся пользователю задачу в зоне ближайшего развития
     * ([targetDifficulty] ± [ZPD_HALF_WINDOW]), с приоритетом задач из собственных партий,
     * чередованием мотивов ([avoidPattern]) и лёгкой рандомизацией среди лучших кандидатов.
     */
    private fun pickUnseenExercise(
        accessible: List<Exercise>,
        schedule: Map<Long, ReviewCardState>,
        targetDifficulty: Int,
        excludeId: Long,
        prioritizeOwnMistakes: Boolean,
        avoidPattern: String? = null,
        filter: (Exercise) -> Boolean = { true }
    ): Exercise? {
        val unseen = accessible.filter { ex ->
            val id = ex.id ?: return@filter false
            id != excludeId && id !in schedule && filter(ex)
        }
        if (unseen.isEmpty()) return null

        val inZpd = unseen.filter { ex ->
            abs(ex.getEffectiveDifficulty() - targetDifficulty) <= ZPD_HALF_WINDOW
        }
        val pool = inZpd.ifEmpty { unseen }

        if (prioritizeOwnMistakes) {
            val ownPool = pool.filter { it.sourceGameId != null }
            if (ownPool.isNotEmpty()) {
                return pickNearDifficulty(preferVariedPattern(ownPool, avoidPattern), targetDifficulty)
            }
            val anyOwn = unseen.filter { it.sourceGameId != null }
            if (anyOwn.isNotEmpty()) {
                return pickNearDifficulty(preferVariedPattern(anyOwn, avoidPattern), targetDifficulty)
            }
        }

        return pickNearDifficulty(preferVariedPattern(pool, avoidPattern), targetDifficulty)
    }

    private fun preferVariedPattern(candidates: List<Exercise>, avoidPattern: String?): List<Exercise> {
        if (avoidPattern.isNullOrBlank() || candidates.size <= 1) return candidates
        val varied = candidates.filter { it.tacticalPattern.name != avoidPattern }
        return varied.ifEmpty { candidates }
    }

    private fun pickNearDifficulty(candidates: List<Exercise>, targetDifficulty: Int): Exercise? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val sorted = candidates.sortedBy { abs(it.getEffectiveDifficulty() - targetDifficulty) }
        val topCount = minOf(3, sorted.size)
        return sorted[Random.nextInt(topCount)]
    }

    private fun buildNewSuccess(
        exercise: Exercise,
        snapshot: LearningSnapshot
    ): Result.Success = Result.Success(
        exercise = exercise,
        dueReviewCount = snapshot.dueReviewCount,
        completedRatedSittings = snapshot.completedRatedSittings
    )

    private fun buildReviewSuccess(
        exercise: Exercise,
        state: ReviewCardState,
        snapshot: LearningSnapshot
    ): Result.Success = Result.Success(
        exercise = exercise,
        isReviewOfFailed = state.needsConsolidation,
        reviewState = state,
        dueReviewCount = snapshot.dueReviewCount,
        completedRatedSittings = snapshot.completedRatedSittings
    )

    private suspend fun createFallbackExerciseFromMistake(user: User, userId: Long): Exercise? {
        val candidateMistakes = mistakeRepository.findByUserId(userId).asReversed()
        for (mistake in candidateMistakes) {
            if (!GenerateExercisesFromMistakesUseCase.isMistakeSuitableForExercise(mistake, null)) {
                continue
            }
            val fen = mistake.fenBefore?.trim().takeIf { !it.isNullOrEmpty() } ?: continue
            if (exerciseRepository.findUserExerciseByFen(userId, fen) != null) continue
            val solutionUci = ChessMoveUtils.sanOrUciToUci(mistake.bestMove, fen) ?: continue

            val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)
            val newExercise = Exercise(
                fenPosition = fen,
                solutionMoves = listOf(solutionUci),
                tacticalPattern = TacticalPattern.MIXED,
                patternClusterId = "MIXED_MEDIUM",
                difficultyRating = targetDiff,
                patternFeatures = emptyList(),
                theme = TacticalTheme.TACTIC,
                rating = TrainingRatingCalculator.difficultyToElo(targetDiff),
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = mistake.gameId,
                sourceMoveNumber = mistake.moveNumber
            )
            return exerciseRepository.saveExercise(newExercise)
        }
        return null
    }
}
