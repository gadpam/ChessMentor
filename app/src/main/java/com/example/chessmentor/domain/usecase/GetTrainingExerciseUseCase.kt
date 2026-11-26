// domain/usecase/GetTrainingExerciseUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.analysis.TacticalMotif
import com.example.chessmentor.domain.clustering.ExerciseClusterer
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.PuzzleKind
import com.example.chessmentor.domain.training.PuzzleQualityEvaluator
import com.example.chessmentor.domain.training.ReviewCardState
import com.example.chessmentor.domain.training.SessionPhase
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import com.example.chessmentor.domain.training.TrainingSessionPlanner
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
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
        private const val ZPD_HALF_WINDOW = 14
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
        if (input.mode !is TrainingMode.MyMistakes && input.mode !is TrainingMode.FromGame) {
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
                difficultyOffset = -8
            )
            TrainingMode.DeepCalculation -> selectDeepCalculationExercise(
                user = user,
                excludeId = input.excludeExerciseId,
                avoidPattern = input.avoidPattern,
                nowMs = input.nowMs,
                snapshot = snapshot,
                difficultyOffset = 12
            )
            TrainingMode.MyMistakes -> selectUserMistakeExercise(user, userId, input.excludeExerciseId, input.avoidPattern, input.nowMs, snapshot)
            is TrainingMode.FromGame -> selectGameMistakeExercise(
                gameId = mode.gameId,
                focusMoveNumber = mode.focusMoveNumber,
                excludeId = input.excludeExerciseId,
                nowMs = input.nowMs,
                snapshot = snapshot
            )
            is TrainingMode.SkillTransfer -> selectSkillTransferExercise(
                user = user,
                mode = mode,
                excludeId = input.excludeExerciseId,
                snapshot = snapshot
            )
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
        val targetDiff = TrainingRatingCalculator.zpdTargetDifficulty(user.rating)

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
        createFallbackExerciseFromMistake(userId)?.let { fallback ->
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
        val targetDiff = TrainingRatingCalculator.zpdTargetDifficulty(user.rating)

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
                snapshot = snapshot,
                difficultyOffset = phase.difficultyOffset
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
        val targetDiff = (TrainingRatingCalculator.zpdTargetDifficulty(user.rating) + difficultyOffset)
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
        val targetDiff = (TrainingRatingCalculator.zpdTargetDifficulty(user.rating) + difficultyOffset)
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
     * Прицельная отработка ошибок одной конкретной партии [gameId] (мост «Партия → Тренировка»).
     * При первичном входе (`excludeId <= 0`) и заданном [focusMoveNumber] первой подаётся задача
     * именно на выбранный ход, затем — остальные ошибки партии в хронологическом порядке.
     */
    private fun selectGameMistakeExercise(
        gameId: Long,
        focusMoveNumber: Int?,
        excludeId: Long,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        if (gameId <= 0L) return Result.NoSuitableExercises
        val gameFilter: (Exercise) -> Boolean = { ex ->
            ex.sourceGameId == gameId && ex.createdFrom == com.example.chessmentor.domain.entity.ExerciseSource.MISTAKE
        }
        val gamePool = snapshot.accessible.filter(gameFilter)
        if (gamePool.isEmpty()) return Result.NoSuitableExercises

        // 1. Явно выбранный ход партии (с карточки ошибки на доске или в сводке)
        if (focusMoveNumber != null && focusMoveNumber > 0 && excludeId <= 0L) {
            val focused = gamePool.firstOrNull { it.sourceMoveNumber == focusMoveNumber }
            if (focused != null) {
                val state = focused.id?.let { snapshot.schedule[it] }
                return if (state != null) {
                    buildReviewSuccess(focused, state, snapshot)
                } else {
                    buildNewSuccess(focused, snapshot)
                }
            }
        }

        // 2. Ещё не решённые ошибки этой партии (в порядке ходов партии)
        val unseen = gamePool
            .filter { ex ->
                val id = ex.id ?: return@filter false
                id != excludeId && snapshot.schedule[id] == null
            }
            .sortedBy { it.sourceMoveNumber ?: Int.MAX_VALUE }
            .firstOrNull()
        if (unseen != null) {
            return buildNewSuccess(unseen, snapshot)
        }

        // 3. Подошедшие к интервальному повтору ошибки этой партии
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = gameFilter
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot)
        }

        // 4. Внеочередное закрепление наименее усвоенной ошибки этой партии (кроме только что показанной)
        val nonExcluded = gamePool.filter { it.id != null && it.id != excludeId }
        if (nonExcluded.isNotEmpty()) {
            val candidate = nonExcluded.minWithOrNull(
                compareBy<Exercise> { ex -> snapshot.schedule[ex.id]?.box ?: 0 }
                    .thenBy { ex -> snapshot.schedule[ex.id]?.lastAttemptAt ?: 0L }
            )
            if (candidate != null) {
                val state = candidate.id?.let { snapshot.schedule[it] }
                return if (state != null) {
                    buildReviewSuccess(candidate, state, snapshot)
                } else {
                    buildNewSuccess(candidate, snapshot)
                }
            }
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
        snapshot: LearningSnapshot,
        difficultyOffset: Int = 0
    ): Result {
        val defenseFilter: (Exercise) -> Boolean = { ex ->
            ExerciseMetadata.isDefensiveGoal(ex) || ex.theme == TacticalTheme.KING_SAFETY
        }
        val targetDiff = (TrainingRatingCalculator.zpdTargetDifficulty(user.rating) + difficultyOffset)
            .coerceIn(10, 95)

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

    /**
     * Подбор **незнакомой** позиции того же навыка и той же роли («Проверить на другой позиции»)
     * после исправления ошибки: исключает уже встречавшиеся карточки (`id in snapshot.schedule`),
     * исходную позицию/партию и никогда не подменяет выборку общей очередью или чужой ролью.
     */
    private fun selectSkillTransferExercise(
        user: User,
        mode: TrainingMode.SkillTransfer,
        excludeId: Long,
        snapshot: LearningSnapshot
    ): Result {
        val targetDiff = TrainingRatingCalculator.zpdTargetDifficulty(user.rating)
        val unseenPool = snapshot.accessible.filter { ex ->
            val id = ex.id ?: return@filter false
            id != excludeId &&
                (mode.excludeExerciseId == null || id != mode.excludeExerciseId) &&
                (mode.excludeSourceGameId == null || ex.sourceGameId != mode.excludeSourceGameId) &&
                id !in snapshot.schedule
        }
        if (unseenPool.isEmpty()) return Result.NoSuitableExercises

        val rolePool = unseenPool.filter { ex ->
            val exIsDefensive = ExerciseMetadata.isDefensiveGoal(ex) || ex.theme == TacticalTheme.KING_SAFETY
            exIsDefensive == mode.isDefensive
        }
        if (rolePool.isEmpty()) return Result.NoSuitableExercises

        // 1. Точное совпадение паттерна и роли среди незнакомых позиций
        val exactPatternCandidates = rolePool.filter { it.tacticalPattern == mode.pattern }
        if (exactPatternCandidates.isNotEmpty()) {
            pickSkillTransferCandidate(exactPatternCandidates, targetDiff)?.let { ex ->
                return buildNewSuccess(ex, snapshot)
            }
        }

        // 2. Семейство того же навыка и той же роли (без выхода в общую очередь)
        val sameFamilyCandidates = rolePool.filter { ex ->
            matchesSkillTransferFamily(ex, mode)
        }
        if (sameFamilyCandidates.isNotEmpty()) {
            pickSkillTransferCandidate(sameFamilyCandidates, targetDiff)?.let { ex ->
                return buildNewSuccess(ex, snapshot)
            }
        }

        return Result.NoSuitableExercises
    }

    private fun matchesSkillTransferFamily(ex: Exercise, mode: TrainingMode.SkillTransfer): Boolean {
        if (ex.tacticalPattern == mode.pattern) return true
        if (mode.isDefensive) {
            return true // rolePool уже строго отфильтрован по защитной роли
        }
        return when {
            mode.pattern == TacticalPattern.ENDGAME_TECHNIQUE ||
                mode.pattern == TacticalPattern.PAWN_BREAKTHROUGH ->
                ex.tacticalPattern == TacticalPattern.ENDGAME_TECHNIQUE ||
                    ex.tacticalPattern == TacticalPattern.PAWN_BREAKTHROUGH
            mode.theme == TacticalTheme.TACTIC ->
                ex.theme == TacticalTheme.TACTIC && ex.tacticalPattern != TacticalPattern.ENDGAME_TECHNIQUE
            mode.theme != TacticalTheme.UNKNOWN ->
                ex.theme == mode.theme
            else -> false
        }
    }

    private fun pickSkillTransferCandidate(
        candidates: List<Exercise>,
        targetDifficulty: Int
    ): Exercise? {
        if (candidates.isEmpty()) return null
        val zpd = candidates.filter {
            abs(it.getEffectiveDifficulty() - targetDifficulty) <= ZPD_HALF_WINDOW
        }
        val pool = zpd.ifEmpty { candidates }
        val otherGameMistakes = pool.filter { it.sourceGameId != null }
        val preferred = otherGameMistakes.ifEmpty { pool }
        return pickNearDifficulty(preferred, targetDifficulty)
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
        val targetDiff = TrainingRatingCalculator.zpdTargetDifficulty(user.rating)

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
        val targetDiff = TrainingRatingCalculator.zpdTargetDifficulty(user.rating)

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
        createFallbackExerciseFromMistake(userId)?.let { fallback ->
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

    private suspend fun createFallbackExerciseFromMistake(userId: Long): Exercise? {
        val candidateMistakes = mistakeRepository.findByUserId(userId).asReversed()
        for (mistake in candidateMistakes) {
            val puzzleKind = PuzzleQualityEvaluator.classifyMistake(mistake, null)
            if (puzzleKind == PuzzleKind.REJECTED) {
                continue
            }
            val fen = mistake.fenBefore?.trim().takeIf { !it.isNullOrEmpty() } ?: continue
            if (exerciseRepository.findUserExerciseByFen(userId, fen) != null) continue
            val board = runCatching { Board().apply { loadFromFen(fen) } }.getOrNull() ?: continue
            val solutionUci = ChessMoveUtils.sanOrUciToUci(mistake.bestMove, fen) ?: continue
            val firstMove = ChessMoveUtils.parseUciMove(board, solutionUci) ?: continue
            val solutionMoves = listOf(solutionUci)

            val isDefensive = puzzleKind == PuzzleKind.DEFENSIVE_RESOURCE || mistake.themeId in setOf(16L, 17L)
            val pattern = classifyFallbackPattern(board, firstMove, solutionMoves, mistake, isDefensive)
            val theme = if (isDefensive &&
                pattern != TacticalPattern.BACK_RANK_MATE &&
                pattern != TacticalPattern.SMOTHERED_MATE
            ) {
                TacticalTheme.KING_SAFETY
            } else {
                themeForPattern(pattern)
            }

            val difficulty = ExerciseClusterer.calculateDifficulty(
                board = board,
                pattern = pattern,
                solutionMoves = solutionMoves,
                materialGain = mistake.evaluationLoss.coerceAtMost(1200)
            )
            val clusterId = ExerciseClusterer.generateClusterId(
                board = board,
                pattern = pattern,
                solutionMove = solutionUci,
                difficultyRating = difficulty
            )
            val baseFeatures = ExerciseClusterer.extractFeatures(
                board = board,
                pattern = pattern,
                solutionMove = solutionUci
            )
            val features = if (isDefensive) {
                baseFeatures + ExerciseMetadata.defensiveGoalTag()
            } else {
                baseFeatures
            }

            val newExercise = Exercise(
                fenPosition = fen,
                solutionMoves = solutionMoves,
                tacticalPattern = pattern,
                patternClusterId = clusterId,
                difficultyRating = difficulty,
                patternFeatures = features,
                theme = theme,
                rating = TrainingRatingCalculator.difficultyToElo(difficulty),
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = mistake.gameId,
                sourceMoveNumber = mistake.moveNumber
            )
            return exerciseRepository.saveExercise(newExercise)
        }
        return null
    }

    private fun classifyFallbackPattern(
        board: Board,
        firstMove: com.github.bhlangonijr.chesslib.move.Move,
        solutionMoves: List<String>,
        mistake: Mistake,
        isDefensive: Boolean
    ): TacticalPattern {
        val after = Board().apply { loadFromFen(board.fen) }
        val movingPiece = board.getPiece(firstMove.from)
        if (after.doMove(firstMove)) {
            if (after.isMated) {
                return if (movingPiece.pieceType == PieceType.KNIGHT) {
                    TacticalPattern.SMOTHERED_MATE
                } else {
                    TacticalPattern.BACK_RANK_MATE
                }
            }
            if (isDefensive && after.isStaleMate) {
                return TacticalPattern.STALEMATE_TRICK
            }
        }

        if (isDefensive) {
            return if (PuzzleQualityEvaluator.isCounterAttackingDefense(board, solutionMoves)) {
                TacticalPattern.COUNTER_ATTACK
            } else {
                TacticalPattern.DEFENSIVE_MOVE
            }
        }

        if (firstMove.promotion != Piece.NONE) {
            return TacticalPattern.PAWN_BREAKTHROUGH
        }

        val uci = ChessMoveUtils.moveToUci(firstMove)
        if (SemanticMistakeAnalyzer.isDoubleCheckMove(board, uci)) {
            return TacticalPattern.DOUBLE_CHECK
        }

        val motif = SemanticMistakeAnalyzer.detectStaticMotif(board, uci)
        if (motif != null) {
            if (motif == TacticalMotif.FORK) {
                return when (movingPiece.pieceType) {
                    PieceType.KNIGHT -> TacticalPattern.FORK_KNIGHT
                    PieceType.BISHOP -> TacticalPattern.FORK_BISHOP
                    PieceType.ROOK -> TacticalPattern.FORK_ROOK
                    PieceType.QUEEN -> TacticalPattern.FORK_QUEEN
                    PieceType.PAWN -> TacticalPattern.FORK_PAWN
                    else -> TacticalPattern.FORK_KNIGHT
                }
            }
            if (motif == TacticalMotif.PIN && SemanticMistakeAnalyzer.createsPinToKing(board, firstMove, movingPiece)) {
                return TacticalPattern.PIN_ABSOLUTE
            }
            return TacticalPattern.fromMotif(motif)
        }

        return when (mistake.themeId) {
            1L -> TacticalPattern.FORK_KNIGHT
            2L -> TacticalPattern.PIN_RELATIVE
            3L -> TacticalPattern.SKEWER
            4L -> TacticalPattern.DISCOVERED_ATTACK
            5L -> TacticalPattern.DOUBLE_CHECK
            6L -> TacticalPattern.REMOVING_DEFENDER
            7L -> TacticalPattern.BACK_RANK_MATE
            14L, 15L -> TacticalPattern.ENDGAME_TECHNIQUE
            16L, 17L -> TacticalPattern.DEFENSIVE_MOVE
            else -> when {
                ChessEngine.isMateScore(mistake.evaluationLoss) || mistake.evaluationLoss > 50_000 ->
                    TacticalPattern.BACK_RANK_MATE
                board.isKingAttacked -> TacticalPattern.DEFENSIVE_MOVE
                mistake.moveNumber >= 38 -> TacticalPattern.ENDGAME_TECHNIQUE
                mistake.mistakeType == MistakeType.BLUNDER -> TacticalPattern.CALCULATION
                mistake.mistakeType == MistakeType.INACCURACY -> TacticalPattern.POSITIONAL
                else -> TacticalPattern.MIXED
            }
        }
    }

    private fun themeForPattern(pattern: TacticalPattern): TacticalTheme = when (pattern) {
        TacticalPattern.FORK_KNIGHT,
        TacticalPattern.FORK_BISHOP,
        TacticalPattern.FORK_ROOK,
        TacticalPattern.FORK_QUEEN,
        TacticalPattern.FORK_PAWN,
        TacticalPattern.DECOY -> TacticalTheme.FORK

        TacticalPattern.PIN_ABSOLUTE,
        TacticalPattern.PIN_RELATIVE,
        TacticalPattern.SKEWER -> TacticalTheme.PIN

        TacticalPattern.DISCOVERED_ATTACK,
        TacticalPattern.DISCOVERED_CHECK,
        TacticalPattern.DOUBLE_CHECK -> TacticalTheme.DISCOVERED_ATTACK

        TacticalPattern.BACK_RANK_MATE,
        TacticalPattern.SMOTHERED_MATE,
        TacticalPattern.ANASTASIA_MATE,
        TacticalPattern.ARABIAN_MATE,
        TacticalPattern.BODEN_MATE -> TacticalTheme.MATE

        TacticalPattern.HANGING_PIECE,
        TacticalPattern.TRAPPED_PIECE,
        TacticalPattern.QUEEN_TRAP -> TacticalTheme.MATERIAL_LOSS

        TacticalPattern.DEFENSIVE_MOVE,
        TacticalPattern.COUNTER_ATTACK,
        TacticalPattern.STALEMATE_TRICK,
        TacticalPattern.PERPETUAL_CHECK -> TacticalTheme.KING_SAFETY

        else -> TacticalTheme.TACTIC
    }
}
