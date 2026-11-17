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
import com.example.chessmentor.domain.training.MasteryLevel
import com.example.chessmentor.domain.training.ReviewCardState
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
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
        val preferredPattern: String? = null,
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
            val dueReviewCount: Int = 0
        ) : Result()

        data class Error(val message: String) : Result()
        object NoSuitableExercises : Result()
    }

    private data class LearningSnapshot(
        val accessible: List<Exercise>,
        val exercisesById: Map<Long, Exercise>,
        val schedule: Map<Long, ReviewCardState>,
        val dueReviewCount: Int
    )

    suspend fun execute(input: Input): Result {
        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val userId = user.id ?: return Result.Error("Некорректный идентификатор пользователя")

        // Гарантируем, что встроенный банк тактических задач доступен
        if (input.mode !is TrainingMode.MyMistakes) {
            CuratedExerciseBank.ensureSeeded(exerciseRepository)
        }

        val snapshot = loadLearningSnapshot(userId, input.nowMs)

        Log.d(
            TAG,
            "Selecting exercise user=$userId mode=${input.mode.routeKey} due=${snapshot.dueReviewCount} exclude=${input.excludeExerciseId}"
        )

        return when (val mode = input.mode) {
            TrainingMode.Daily -> selectDailyExercise(userId, input.epochDay, snapshot)
            TrainingMode.MyMistakes -> selectUserMistakeExercise(user, userId, input.excludeExerciseId, input.nowMs, snapshot)
            TrainingMode.WeakPatterns -> selectWeakPatternExercise(user, userId, input.excludeExerciseId, input.nowMs, snapshot)
            is TrainingMode.ByTheme -> selectThemeExercise(user, input.excludeExerciseId, mode.theme, input.nowMs, snapshot)
            TrainingMode.All -> selectAdaptiveExercise(user, userId, input.excludeExerciseId, input.preferredPattern, input.nowMs, snapshot)
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
        return LearningSnapshot(
            accessible = accessible,
            exercisesById = byId,
            schedule = schedule,
            dueReviewCount = dueCount
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
            dueReviewCount = snapshot.dueReviewCount
        )
    }

    private suspend fun selectUserMistakeExercise(
        user: User,
        userId: Long,
        excludeId: Long,
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
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        // 2. Ещё ни разу не решённые задачи из своих партий (не находящиеся в охлаждении)
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            filter = isOwn
        )?.let { ex ->
            return Result.Success(exercise = ex, dueReviewCount = snapshot.dueReviewCount)
        }

        // 3. Резерв: создать из ещё не обработанной ошибки партии
        createFallbackExerciseFromMistake(user, userId)?.let { fallback ->
            return Result.Success(exercise = fallback, dueReviewCount = snapshot.dueReviewCount)
        }

        // 4. Внеочередное повторение собственных задач (начиная с наименьшей коробки Лейтнера)
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = isOwn
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        return Result.NoSuitableExercises
    }

    private suspend fun selectWeakPatternExercise(
        user: User,
        userId: Long,
        excludeId: Long,
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
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        // 2. Новые задачи по наиболее слабым тактическим мотивам пользователя
        val attempts = exerciseRepository.getAttemptsByUserId(userId)
        if (attempts.isNotEmpty()) {
            val mastery = TrainingRatingCalculator.computePatternMastery(snapshot.exercisesById, attempts)
            val candidatePatterns = mastery
                .filter { it.level == MasteryLevel.WEAK || it.level == MasteryLevel.LEARNING }
                .ifEmpty { mastery.take(3) }

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
                    return Result.Success(exercise = ex, dueReviewCount = snapshot.dueReviewCount)
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
                    return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
                }
            }
        }

        // 3. Если статистики пока мало — адаптивный подбор
        return selectAdaptiveExercise(user, userId, excludeId, preferredPattern = null, nowMs = nowMs, snapshot = snapshot)
    }

    private fun selectThemeExercise(
        user: User,
        excludeId: Long,
        theme: TacticalTheme,
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
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        // 2. Новые (ещё не открывавшиеся) задачи выбранной темы в зоне ближайшего развития
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true,
            filter = themeFilter
        )?.let { ex ->
            return Result.Success(exercise = ex, dueReviewCount = snapshot.dueReviewCount)
        }

        // 3. Внеочередное повторение наименее закреплённой задачи темы
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId,
            filter = themeFilter
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        return Result.NoSuitableExercises
    }

    private suspend fun selectAdaptiveExercise(
        user: User,
        userId: Long,
        excludeId: Long,
        preferredPattern: String?,
        nowMs: Long,
        snapshot: LearningSnapshot
    ): Result {
        val targetDiff = TrainingRatingCalculator.eloToDifficulty(user.rating)

        // 1. Целевое закрепление запрошенного паттерна (только среди новых или уже подошедших по сроку!)
        if (!preferredPattern.isNullOrBlank()) {
            val patternFilter: (Exercise) -> Boolean = { it.tacticalPattern.name == preferredPattern }

            SpacedRepetitionScheduler.rankDueExercises(
                accessibleExercises = snapshot.accessible,
                schedule = snapshot.schedule,
                nowMs = nowMs,
                excludeId = excludeId,
                filter = patternFilter
            ).firstOrNull()?.let { (ex, state) ->
                return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
            }

            pickUnseenExercise(
                accessible = snapshot.accessible,
                schedule = snapshot.schedule,
                targetDifficulty = targetDiff,
                excludeId = excludeId,
                prioritizeOwnMistakes = true,
                filter = patternFilter
            )?.let { ex ->
                return Result.Success(exercise = ex, dueReviewCount = snapshot.dueReviewCount)
            }
        }

        // 2. Интервальное повторение (SM-2 / Лейтнер): подошедшие к сроку карточки
        // (проваленные после 10-мин охлаждения, решённые со 2-й попытки через 4 ч,
        // с подсказкой через 1 дн., медленные и плановые повторы через 3/7/21+ дн.)
        SpacedRepetitionScheduler.rankDueExercises(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId
        ).firstOrNull()?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        // 3. Новая (ранее не решавшаяся) задача подходящей сложности.
        // Задачи в 10-минутном охлаждении после ошибки уже находятся в schedule и сюда не попадают!
        pickUnseenExercise(
            accessible = snapshot.accessible,
            schedule = snapshot.schedule,
            targetDifficulty = targetDiff,
            excludeId = excludeId,
            prioritizeOwnMistakes = true
        )?.let { ex ->
            return Result.Success(exercise = ex, dueReviewCount = snapshot.dueReviewCount)
        }

        // 4. Резерв: если есть ещё не сконвертированные ошибки партий
        createFallbackExerciseFromMistake(user, userId)?.let { fallback ->
            return Result.Success(exercise = fallback, dueReviewCount = snapshot.dueReviewCount)
        }

        // 5. Если просмотрены все задачи банка и ни одна ещё не подошла по таймеру —
        // выдаём наименее закреплённую и наиболее отдохнувшую карточку
        SpacedRepetitionScheduler.pickFallbackReview(
            accessibleExercises = snapshot.accessible,
            schedule = snapshot.schedule,
            nowMs = nowMs,
            excludeId = excludeId
        )?.let { (ex, state) ->
            return buildReviewSuccess(ex, state, snapshot.dueReviewCount)
        }

        return Result.NoSuitableExercises
    }

    /**
     * Подбирает ещё не встречавшуюся пользователю задачу в зоне ближайшего развития
     * ([targetDifficulty] ± [ZPD_HALF_WINDOW]), с приоритетом задач из собственных партий
     * и лёгкой рандомизацией среди лучших кандидатов, чтобы порядок не был механическим.
     */
    private fun pickUnseenExercise(
        accessible: List<Exercise>,
        schedule: Map<Long, ReviewCardState>,
        targetDifficulty: Int,
        excludeId: Long,
        prioritizeOwnMistakes: Boolean,
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
                return pickNearDifficulty(ownPool, targetDifficulty)
            }
            // Если в ZPD нет своих задач, но есть неразобранные свои ошибки чуть вне окна — всё равно берём их
            val anyOwn = unseen.filter { it.sourceGameId != null }
            if (anyOwn.isNotEmpty()) {
                return pickNearDifficulty(anyOwn, targetDifficulty)
            }
        }

        return pickNearDifficulty(pool, targetDifficulty)
    }

    private fun pickNearDifficulty(candidates: List<Exercise>, targetDifficulty: Int): Exercise? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val sorted = candidates.sortedBy { abs(it.getEffectiveDifficulty() - targetDifficulty) }
        val topCount = minOf(3, sorted.size)
        return sorted[Random.nextInt(topCount)]
    }

    private fun buildReviewSuccess(
        exercise: Exercise,
        state: ReviewCardState,
        dueReviewCount: Int
    ): Result.Success = Result.Success(
        exercise = exercise,
        isReviewOfFailed = state.needsConsolidation,
        reviewState = state,
        dueReviewCount = dueReviewCount
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
