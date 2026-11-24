// presentation/viewmodel/TrainingViewModel.kt
package com.example.chessmentor.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.analysis.MistakeAnalysis
import com.example.chessmentor.domain.engine.EngineAnalysisProfile
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.HintUsage
import com.example.chessmentor.domain.training.InterleavedPatternQueue
import com.example.chessmentor.domain.training.PuzzleQualityEvaluator
import com.example.chessmentor.domain.training.ReviewCardState
import com.example.chessmentor.domain.training.SessionPhase
import com.example.chessmentor.domain.training.SessionSummary
import com.example.chessmentor.domain.training.SolutionReviewHelper
import com.example.chessmentor.domain.training.SolutionReviewStep
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.domain.training.verifyTrainingMove
import com.example.chessmentor.domain.training.TrainingMoveQuality
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import com.example.chessmentor.domain.training.TrainingSessionPlanner
import com.example.chessmentor.domain.usecase.GenerateExercisesFromMistakesUseCase
import com.example.chessmentor.domain.usecase.GetTrainingExerciseUseCase
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardArrow
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Звуковые события доски и результата задачи.
 */
enum class TrainingSoundEvent {
    MOVE,
    CAPTURE,
    CHECK,
    SOLVED,
    ERROR
}

/**
 * Единое состояние экрана тренировки.
 */
data class TrainingUiState(
    val board: Board = Board(),
    val mode: TrainingMode = TrainingMode.All,
    val arrows: List<BoardArrow> = emptyList(),
    val highlights: List<String> = emptyList(),
    val lastMove: Pair<Square, Square>? = null,
    val feedbackMessage: String? = null,
    val playerSide: Side = Side.WHITE,
    val isInputLocked: Boolean = false,
    val checkUnavailable: Boolean = false,
    val isSolved: Boolean = false,
    val isSolutionRevealed: Boolean = false,
    val isLoading: Boolean = false,
    val isInitializationError: Boolean = false,
    val hasActiveExercise: Boolean = false,
    val hasNoUserMistakes: Boolean = false,
    val hintLevel: HintLevel = HintLevel.NONE,
    val sessionStats: SessionStats = SessionStats(),
    val lastRatingDelta: Int? = null,

    // Метаданные текущей задачи
    val exerciseTitle: String? = null,
    val exerciseSubtitle: String? = null,
    val currentPattern: String? = null,
    val patternDisplayName: String? = null,
    val theme: TacticalTheme? = null,
    val difficultyRating: Int = 50,
    val difficultyLabel: String = "Средняя",
    val exerciseElo: Int = 1600,
    val sourceLabel: String = "",
    val isDailyAlreadySolved: Boolean = false,
    val moveRevealedInExercise: Boolean = false,
    val isReviewOfFailed: Boolean = false,
    val nextReviewNote: String? = null,
    val dueReviewCount: Int = 0,
    val currentStepNumber: Int = 1,
    val totalPlayerSteps: Int = 1,
    val isAnimatingIntro: Boolean = false,

    // Структурированная сессия (TrainingMode.SmartSession)
    val sessionStepNumber: Int = 0,
    val sessionTotalSteps: Int = TrainingSessionPlanner.DEFAULT_SESSION_SIZE,
    val sessionPhase: SessionPhase? = null,
    val sessionSummary: SessionSummary? = null,

    // Пост-разбор решения и свободный анализ со Stockfish
    val reviewSteps: List<SolutionReviewStep> = emptyList(),
    val reviewStepIndex: Int = 0,
    val solutionSanLine: String? = null,
    val postSolveExplanation: String? = null,
    val isFreeAnalysisMode: Boolean = false,
    val isAnalyzingPosition: Boolean = false,
    val analysisEvalText: String? = null,
    val analysisBestMoveSan: String? = null,
    val analysisBestMoveUci: String? = null,
    val canUndoAnalysisMove: Boolean = false,

    // Ошибки и анимации
    val currentMistakeAnalysis: MistakeAnalysis? = null,
    val showBetterMoveHint: Boolean = false,
    val betterMoveShown: Boolean = false,
    val punishmentAnimationStep: Int = 0,
    val isAnimatingPunishment: Boolean = false,
    val isErrorShake: Boolean = false,

    // Звуковые события (soundEventId монотонно растёт для надёжной обработки в LaunchedEffect)
    val soundEvent: TrainingSoundEvent? = null,
    val soundEventId: Long = 0L
)

data class SessionStats(
    val solved: Int = 0,
    val cleanFirstTrySolved: Int = 0,
    val failed: Int = 0,
    val impulsiveMistakes: Int = 0,
    val skipped: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val netRatingDelta: Int = 0
)

enum class HintLevel { NONE, PIECE_HIGHLIGHT, FULL_SOLUTION }
private data class PendingMoveCheck(
    val before: TrainingUiState,
    val move: Move,
    val previousThinkMs: Long?,
    val startedAt: Long
)

private data class AttemptSnapshot(
    val userId: Long,
    val exerciseId: Long,
    val rawUciMoves: List<String>,
    val hintUsage: HintUsage,
    val retryCount: Int,
    val firstMoveThinkMs: Long?,
    val timeSpentSeconds: Int
)

class TrainingViewModel(
    private val container: AppContainer,
    private val verificationEngine: ChessEngine = container.chessEngine
) : ViewModel() {

    companion object {
        private const val TAG = "TrainingVM"
        private const val INTRO_PRE_MOVE_DELAY_MS = 450L
        private const val HUMAN_REACTION_DELAY_MS = 650L
        private const val ANIMATION_DELAY_MS = 600L
        private const val PUNISHMENT_DISPLAY_MS = 2200L
        private const val MAX_SOLUTION_PLIES = PuzzleQualityEvaluator.MAX_QUIESCENCE_PLIES
        private const val FREE_ANALYSIS_DEPTH = EngineAnalysisProfile.INTERACTIVE_ANALYSIS_DEPTH
        private const val VERIFICATION_DEPTH = EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH
        private const val PUZZLE_GEN_DEPTH = EngineAnalysisProfile.PUZZLE_GENERATION_DEPTH
    }

    private val semanticAnalyzer = container.semanticMistakeAnalyzer

    private val _uiState = mutableStateOf(TrainingUiState(isLoading = true))
    val uiState: State<TrainingUiState> = _uiState

    private var currentUser: User? = null
    private var onUserUpdatedCallback: ((User) -> Unit)? = null
    private var currentExercise: Exercise? = null
    private var targetSequence = mutableListOf<String>()
    private var pendingCheck: PendingMoveCheck? = null
    private val recordedMoves = mutableListOf<String>()
    private var currentMoveIndex = 0
    private var startTime: Long = 0
    private var firstMoveThinkTimeMs: Long? = null
    private var attemptMistakeCount: Int = 0
    private var attemptFailed = false
    private var failedAttemptSaved = false
    private var maxHintUsed: HintUsage = HintUsage.NONE
    private var isCurrentDailyAlreadySolved = false
    private var soundCounter = 0L
    private var currentJob: Job? = null
    private var generationJob: Job? = null
    private var analysisJob: Job? = null
    private var positionBeforePlayerMove: String = ""
    private var lastMoveBeforePlayerMove: Pair<Square, Square>? = null
    private var initialTriggerMoveUci: String? = null
    private var currentCuratedExplanation: String? = null
    private var lastMistakeCoachingNote: String? = null
    private var completedRatedSittings: Int = 0
    private val analysisHistory = mutableListOf<Pair<String, Pair<Square, Square>?>>()

    /** Очередь отложенного чередования мотивов (interleaved practice вместо предсказуемых блоков подряд). */
    private val interleavedQueue = InterleavedPatternQueue()

    // Состояние активной структурированной сессии (10 задач)
    private var smartSessionStep: Int = 0
    private var smartSessionCleanCount: Int = 0
    private var smartSessionSolvedCount: Int = 0
    private var smartSessionFailedCount: Int = 0
    private var smartSessionImpulsiveCount: Int = 0
    private var smartSessionNetElo: Int = 0
    private var smartSessionTotalSeconds: Int = 0
    private var smartSessionOutcomeRecorded: Boolean = false
    private val smartSessionSeenIds = mutableSetOf<Long>()
    private val smartSessionMissedPatterns = mutableListOf<String>()

    private fun resetSmartSessionTrackers() {
        smartSessionStep = 0
        smartSessionCleanCount = 0
        smartSessionSolvedCount = 0
        smartSessionFailedCount = 0
        smartSessionImpulsiveCount = 0
        smartSessionNetElo = 0
        smartSessionTotalSeconds = 0
        smartSessionOutcomeRecorded = false
        smartSessionSeenIds.clear()
        smartSessionMissedPatterns.clear()
    }

    private fun resetTransientUiState(state: TrainingUiState): TrainingUiState = state.copy(
        isSolved = false,
        isSolutionRevealed = false,
        moveRevealedInExercise = false,
        isAnimatingPunishment = false,
        punishmentAnimationStep = 0,
        isAnimatingIntro = false,
        isErrorShake = false,
        checkUnavailable = false,
        isFreeAnalysisMode = false,
        isAnalyzingPosition = false,
        analysisEvalText = null,
        analysisBestMoveSan = null,
        analysisBestMoveUci = null,
        canUndoAnalysisMove = false,
        currentMistakeAnalysis = null,
        showBetterMoveHint = false,
        betterMoveShown = false,
        isReviewOfFailed = false,
        reviewSteps = emptyList(),
        reviewStepIndex = 0,
        solutionSanLine = null,
        postSolveExplanation = null,
        nextReviewNote = null,
        isDailyAlreadySolved = false,
        hintLevel = HintLevel.NONE,
        hasActiveExercise = false,
        arrows = emptyList(),
        highlights = emptyList(),
        lastRatingDelta = null
    )

    // ================================================================
    // ИНИЦИАЛИЗАЦИЯ И ПЕРЕКЛЮЧЕНИЕ РЕЖИМОВ
    // ================================================================

    fun showInitializationError(message: String) {
        currentJob?.cancel()
        currentExercise = null
        resetStateForNewExercise()
        _uiState.value = resetTransientUiState(_uiState.value).copy(
            isLoading = false,
            isInitializationError = true,
            isInputLocked = true,
            sessionSummary = null,
            feedbackMessage = message
        )
    }

    fun init(
        user: User,
        initialMode: TrainingMode? = null,
        onUserUpdated: ((User) -> Unit)? = null
    ) {
        onUserUpdatedCallback = onUserUpdated
        val isNewUser = currentUser?.id != user.id
        currentUser = user

        val modeChangedByNavigation = initialMode != null &&
            (initialMode != _uiState.value.mode ||
                initialMode is TrainingMode.FromGame ||
                _uiState.value.sessionSummary != null)

        if (isNewUser) {
            interleavedQueue.clear()
            resetSmartSessionTrackers()
            completedRatedSittings = 0
            val targetMode = initialMode ?: TrainingMode.All
            _uiState.value = TrainingUiState(mode = targetMode, isLoading = true)
            initializeExercisesAndLoad(targetMode)
        } else if (modeChangedByNavigation && initialMode != null) {
            selectMode(initialMode)
        } else if (currentExercise == null && !_uiState.value.isLoading && _uiState.value.sessionSummary == null) {
            initializeExercisesAndLoad(_uiState.value.mode)
        }
    }

    /**
     * Запускает новую структурированную сессию из 10 задач с первой разминочной позиции.
     */
    fun restartSmartSession() {
        val userId = currentUser?.id ?: return
        currentJob?.cancel()
        currentExercise = null
        resetStateForNewExercise()
        interleavedQueue.clear()
        resetSmartSessionTrackers()
        smartSessionStep = 1
        val firstPhase = TrainingSessionPlanner.phaseForStep(1)

        _uiState.value = resetTransientUiState(_uiState.value).copy(
            mode = TrainingMode.SmartSession,
            isLoading = true,
            isInitializationError = false,
            isInputLocked = true,
            hasNoUserMistakes = false,
            sessionSummary = null,
            sessionStepNumber = 1,
            sessionTotalSteps = TrainingSessionPlanner.DEFAULT_SESSION_SIZE,
            sessionPhase = firstPhase,
            feedbackMessage = "Готовим сессию из ${TrainingSessionPlanner.DEFAULT_SESSION_SIZE} задач..."
        )

        currentJob = viewModelScope.launch {
            runCatching { CuratedExerciseBank.ensureSeeded(container.exerciseRepository) }
            loadNextExerciseInternal(userId, excludeId = -1L, mode = TrainingMode.SmartSession)
        }
    }

    /**
     * Переключение режима тренировки пользователем.
     */
    fun selectMode(mode: TrainingMode) {
        val userId = currentUser?.id ?: return
        if (mode is TrainingMode.SmartSession && _uiState.value.sessionSummary != null) {
            restartSmartSession()
            return
        }
        if (_uiState.value.mode == mode &&
            mode !is TrainingMode.FromGame &&
            currentExercise != null &&
            !_uiState.value.isLoading
        ) {
            return
        }

        currentJob?.cancel()
        currentExercise = null
        resetStateForNewExercise()
        if (mode !is TrainingMode.All && mode !is TrainingMode.SmartSession) {
            interleavedQueue.clear()
        }
        resetSmartSessionTrackers()
        val initialPhase = if (mode is TrainingMode.SmartSession) {
            smartSessionStep = 1
            TrainingSessionPlanner.phaseForStep(1)
        } else {
            null
        }

        _uiState.value = resetTransientUiState(_uiState.value).copy(
            mode = mode,
            isLoading = true,
            isInitializationError = false,
            isInputLocked = true,
            hasNoUserMistakes = false,
            sessionSummary = null,
            sessionStepNumber = smartSessionStep,
            sessionTotalSteps = TrainingSessionPlanner.DEFAULT_SESSION_SIZE,
            sessionPhase = initialPhase,
            feedbackMessage = "Подбираем задачу (${mode.displayName})..."
        )

        currentJob = viewModelScope.launch {
            if (mode is TrainingMode.FromGame) {
                runCatching {
                    container.generateExercisesFromMistakesUseCase.executeForGame(
                        userId = userId,
                        gameId = mode.gameId,
                        focusMoveNumber = mode.focusMoveNumber
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed generating exercises for game ${mode.gameId}", e)
                }
            } else if (mode is TrainingMode.MyMistakes) {
                // Если пользователь открыл «Мои ошибки», дожидаемся первичной генерации при пустом списке
                val existing = runCatching { container.exerciseRepository.findByUserId(userId) }
                    .getOrDefault(emptyList())
                if (existing.isEmpty()) {
                    runCatching {
                        container.generateExercisesFromMistakesUseCase.execute(
                            GenerateExercisesFromMistakesUseCase.Input(userId, maxExercises = 15)
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed generating mistake exercises", e)
                    }
                }
            }
            loadNextExerciseInternal(userId, excludeId = -1L, mode = mode)
        }
    }

    private fun initializeExercisesAndLoad(mode: TrainingMode) {
        val userId = currentUser?.id ?: return
        currentJob?.cancel()
        currentExercise = null
        resetStateForNewExercise()

        resetSmartSessionTrackers()
        val initialPhase = if (mode is TrainingMode.SmartSession) {
            smartSessionStep = 1
            TrainingSessionPlanner.phaseForStep(1)
        } else {
            null
        }

        _uiState.value = resetTransientUiState(_uiState.value).copy(
            mode = mode,
            isLoading = true,
            isInitializationError = false,
            isInputLocked = true,
            hasNoUserMistakes = false,
            sessionSummary = null,
            sessionStepNumber = smartSessionStep,
            sessionTotalSteps = TrainingSessionPlanner.DEFAULT_SESSION_SIZE,
            sessionPhase = initialPhase,
            feedbackMessage = "Подготавливаем задачи..."
        )

        currentJob = viewModelScope.launch {
            try {
                // Идемпотентно наполняем встроенный тактический банк
                CuratedExerciseBank.ensureSeeded(container.exerciseRepository)

                // Подтягиваем историческую серию решённых задач пользователя
                val historicalStreak = loadCurrentStreak(userId)
                if (historicalStreak > 0) {
                    val stats = _uiState.value.sessionStats
                    _uiState.value = _uiState.value.copy(
                        sessionStats = stats.copy(
                            currentStreak = historicalStreak,
                            bestStreak = maxOf(stats.bestStreak, historicalStreak)
                        )
                    )
                }

                if (mode is TrainingMode.FromGame) {
                    runCatching {
                        container.generateExercisesFromMistakesUseCase.executeForGame(
                            userId = userId,
                            gameId = mode.gameId,
                            focusMoveNumber = mode.focusMoveNumber
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Error generating exercises for game ${mode.gameId}", e)
                    }
                    scheduleBackgroundGeneration(userId)
                } else {
                    val existingUserExercises = container.exerciseRepository.findByUserId(userId)
                    if (existingUserExercises.isEmpty()) {
                        if (mode is TrainingMode.MyMistakes || mode is TrainingMode.All) {
                            runCatching {
                                container.generateExercisesFromMistakesUseCase.execute(
                                    GenerateExercisesFromMistakesUseCase.Input(userId, maxExercises = 12)
                                )
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Error generating initial exercises", e)
                            }
                        }
                        scheduleBackgroundGeneration(userId)
                    } else {
                        scheduleBackgroundGeneration(userId)
                    }
                }

                loadNextExerciseInternal(userId, excludeId = -1L, mode = mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing training", e)
                currentExercise = null
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitializationError = true,
                    isInputLocked = true,
                    hasActiveExercise = false,
                    feedbackMessage = "Сбой: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadCurrentStreak(userId: Long): Int {
        return runCatching {
            val attempts = container.exerciseRepository.getAttemptsByUserId(userId)
            val sittings = attempts.groupBy { it.exerciseId }
                .flatMap { (_, list) -> SpacedRepetitionScheduler.groupIntoSittings(list) }
                .sortedBy { it.finishedAt }
            var streak = 0
            for (sitting in sittings.asReversed()) {
                if (sitting.quality == com.example.chessmentor.domain.training.AttemptQuality.FAILED ||
                    sitting.quality == com.example.chessmentor.domain.training.AttemptQuality.RETRY_SOLVE
                ) {
                    break
                }
                streak++
            }
            streak
        }.getOrDefault(0)
    }

    private fun scheduleBackgroundGeneration(userId: Long) {
        if (generationJob?.isActive == true) return
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                container.generateExercisesFromMistakesUseCase.execute(
                    GenerateExercisesFromMistakesUseCase.Input(userId, maxExercises = 50)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error generating background exercises", e)
            }
        }
    }

    // ================================================================
    // ЗАГРУЗКА СЛЕДУЮЩЕЙ ЗАДАЧИ И ПРОПУСК
    // ================================================================

    fun loadNextExercise() {
        val userId = currentUser?.id ?: return
        val state = _uiState.value
        val currentMode = state.mode
        val excludeId = currentExercise?.id ?: -1L

        // Структурированная сессия из 10 задач: после 10-й позиции открываем тренерский отчёт
        if (currentMode is TrainingMode.SmartSession) {
            finalizeCurrentSessionExerciseIfNeeded()
            val total = TrainingSessionPlanner.DEFAULT_SESSION_SIZE
            if (smartSessionStep >= total) {
                currentJob?.cancel()
                analysisJob?.cancel()
                currentExercise = null
                val summary = TrainingSessionPlanner.buildSessionSummary(
                    totalExercises = total,
                    cleanFirstTryCount = smartSessionCleanCount,
                    solvedTotalCount = smartSessionSolvedCount,
                    failedOrRevealedCount = smartSessionFailedCount,
                    impulsiveMistakeCount = smartSessionImpulsiveCount,
                    netRatingDelta = smartSessionNetElo,
                    totalTimeSeconds = smartSessionTotalSeconds,
                    missedPatternNames = smartSessionMissedPatterns
                )
                _uiState.value = resetTransientUiState(state).copy(
                    isLoading = false,
                    isInputLocked = true,
                    sessionSummary = summary,
                    feedbackMessage = null
                )
                return
            }
            smartSessionStep = (smartSessionStep + 1).coerceIn(1, total)
        }

        // После завершения «Задачи дня» кнопка «Следующая» плавно переводит в общий поток задач
        val effectiveMode = if (currentMode is TrainingMode.Daily &&
            (state.isSolved || state.isSolutionRevealed)
        ) {
            TrainingMode.All
        } else {
            currentMode
        }

        val nextPhase = if (effectiveMode is TrainingMode.SmartSession) {
            TrainingSessionPlanner.phaseForStep(smartSessionStep)
        } else {
            null
        }

        currentJob?.cancel()

        _uiState.value = resetTransientUiState(_uiState.value).copy(
            mode = effectiveMode,
            isLoading = true,
            isInputLocked = true,
            sessionSummary = null,
            sessionStepNumber = if (effectiveMode is TrainingMode.SmartSession) smartSessionStep else 0,
            sessionPhase = nextPhase,
            feedbackMessage = null
        )

        resetStateForNewExercise()

        currentJob = viewModelScope.launch {
            loadNextExerciseInternal(userId, excludeId, effectiveMode)
        }
    }

    private fun finalizeCurrentSessionExerciseIfNeeded() {
        if (_uiState.value.mode !is TrainingMode.SmartSession || smartSessionOutcomeRecorded) return
        val ex = currentExercise ?: return
        smartSessionOutcomeRecorded = true
        val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceIn(1, 600)
        smartSessionTotalSeconds += elapsed
        if (attemptFailed || _uiState.value.isSolutionRevealed) {
            smartSessionFailedCount++
            smartSessionMissedPatterns.add(ex.tacticalPattern.displayName)
        }
    }

    /**
     * Пропустить текущую задачу без списания рейтинга (но со сбросом серии, если уже была подсказка).
     */
    fun onSkipExercise() {
        val state = _uiState.value
        if (state.isLoading || state.isAnimatingPunishment) return
        if (state.isSolved || state.isSolutionRevealed) {
            loadNextExercise()
            return
        }

        val stats = state.sessionStats
        val updatedStreak = if (maxHintUsed == HintUsage.FULL_SOLUTION) 0 else stats.currentStreak
        _uiState.value = state.copy(
            sessionStats = stats.copy(
                skipped = stats.skipped + 1,
                currentStreak = updatedStreak
            )
        )

        // Если пропускается задача дня — переходим в общую тренировку
        if (state.mode is TrainingMode.Daily) {
            selectMode(TrainingMode.All)
        } else {
            loadNextExercise()
        }
    }

    /**
     * Показать полное решение текущей задачи с пошаговой демонстрацией на доске.
     * Не начисляет рейтинг и фиксирует нерешённую попытку (для последующего повторения).
     */
    fun onRevealSolution() {
        val state = _uiState.value
        if (state.isLoading || state.isSolved || state.isSolutionRevealed || state.isAnimatingPunishment) return
        if (!state.hasActiveExercise || targetSequence.isEmpty()) return

        currentJob?.cancel()
        pendingCheck = null
        maxHintUsed = HintUsage.FULL_SOLUTION

        val remainingMoves = targetSequence.drop(currentMoveIndex)
        if (remainingMoves.isEmpty()) return

        val stats = state.sessionStats
        val shouldRecordFail = !failedAttemptSaved && !isCurrentDailyAlreadySolved
        if (shouldRecordFail) {
            failedAttemptSaved = true
            attemptFailed = true
        }

        currentJob = viewModelScope.launch {
            val startFen = positionBeforePlayerMove.ifBlank { state.board.fen }
            val simBoard = Board().apply { loadFromFen(startFen) }

            // Формируем читаемую запись решения в SAN
            val sanNotation = buildSanLine(startFen, remainingMoves)

            _uiState.value = _uiState.value.copy(
                board = Board().apply { loadFromFen(startFen) },
                isInputLocked = true,
                checkUnavailable = false,
                isErrorShake = false,
                hintLevel = HintLevel.FULL_SOLUTION,
                highlights = emptyList(),
                currentMistakeAnalysis = null,
                showBetterMoveHint = false,
                lastRatingDelta = 0,
                sessionStats = stats.copy(
                    failed = if (shouldRecordFail) stats.failed + 1 else stats.failed,
                    currentStreak = 0
                ),
                feedbackMessage = "Показываем решение"
            )

            if (shouldRecordFail) {
                runCatching {
                    saveAttempt(solved = false, solutionRevealed = true)
                    completedRatedSittings++
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed to save revealed attempt", e)
                }
                scheduleInterleavedReinforcement(null)
            }

            // Пошагово разыгрываем оставшуюся линию решения
            var lastPlayedMove = _uiState.value.lastMove
            for ((idx, uci) in remainingMoves.withIndex()) {
                val isPlayerPly = idx % 2 == 0
                val arrowColor = if (isPlayerPly) Color(0xCC4CAF50) else Color(0xAA2196F3)
                createArrowFromUci(uci, arrowColor)?.let { arrow ->
                    _uiState.value = _uiState.value.copy(arrows = listOf(arrow))
                }
                delay(ANIMATION_DELAY_MS)

                val parsed = ChessMoveUtils.parseUciMove(simBoard, uci) ?: break
                val wasCapture = isCaptureMove(simBoard, parsed)
                if (!simBoard.doMove(parsed)) break
                lastPlayedMove = parsed.from to parsed.to

                emitBoardWithSound(
                    newBoard = Board().apply { loadFromFen(simBoard.fen) },
                    movePair = lastPlayedMove,
                    wasCapture = wasCapture
                )
                delay(ANIMATION_DELAY_MS / 2)
            }

            val card = refreshCardAndDueCount()

            val postReview = buildPostSolveReviewState()
            val fullSan = postReview.second.ifBlank { sanNotation }

            _uiState.value = _uiState.value.copy(
                isSolutionRevealed = true,
                isInputLocked = true,
                isFreeAnalysisMode = false,
                arrows = emptyList(),
                reviewSteps = postReview.first,
                reviewStepIndex = (postReview.first.size - 1).coerceAtLeast(0),
                solutionSanLine = fullSan,
                postSolveExplanation = postReview.third,
                feedbackMessage = "Решение показано",
                nextReviewNote = card?.takeUnless { isCurrentDailyAlreadySolved }?.let(SpacedRepetitionScheduler::formatNextIntervalNote)
            )
        }
    }

    private fun buildSanLine(startFen: String, uciMoves: List<String>): String {
        val sim = Board().apply { loadFromFen(startFen) }
        val parts = mutableListOf<String>()
        for (uci in uciMoves) {
            val san = ChessMoveUtils.uciToSan(uci, sim.fen)
            parts.add(san)
            if (!ChessMoveUtils.applyUciMove(sim, uci)) break
        }
        return parts.joinToString(" → ")
    }

    private suspend fun loadNextExerciseInternal(
        userId: Long,
        excludeId: Long,
        mode: TrainingMode
    ) {
        resetStateForNewExercise()
        try {
            // Отложенное закрепление (interleaved practice): если подошёл срок мотива после 2+ других задач —
            // незаметно подмешиваем его без предварительной подсказки.
            val isSmart = mode is TrainingMode.SmartSession
            val activePhase = if (isSmart) {
                if (smartSessionStep <= 0) smartSessionStep = 1
                TrainingSessionPlanner.phaseForStep(smartSessionStep)
            } else {
                null
            }
            val dueInterleavedPattern = if (mode is TrainingMode.All || activePhase == SessionPhase.CORE_INTERLEAVED) {
                interleavedQueue.pollDuePattern()
            } else {
                null
            }
            val avoidRepeatedPattern = interleavedQueue.lastServedPattern
            val sessionExclusions = if (isSmart) smartSessionSeenIds.toSet() else emptySet()

            val result = container.getTrainingExerciseUseCase.execute(
                GetTrainingExerciseUseCase.Input(
                    userId = userId,
                    excludeExerciseId = excludeId,
                    excludeExerciseIds = sessionExclusions,
                    preferredPattern = dueInterleavedPattern,
                    avoidPattern = avoidRepeatedPattern,
                    sessionPhase = activePhase,
                    mode = mode
                )
            )

            // Если кроме только что решённых задач других в этом режиме нет — мягкий откат
            val finalResult = if (result is GetTrainingExerciseUseCase.Result.NoSuitableExercises &&
                (excludeId != -1L || sessionExclusions.isNotEmpty())
            ) {
                container.getTrainingExerciseUseCase.execute(
                    GetTrainingExerciseUseCase.Input(
                        userId = userId,
                        excludeExerciseId = excludeId,
                        excludeExerciseIds = emptySet(),
                        preferredPattern = null,
                        avoidPattern = null,
                        sessionPhase = activePhase,
                        mode = mode
                    )
                ).let { second ->
                    if (second is GetTrainingExerciseUseCase.Result.NoSuitableExercises && excludeId != -1L) {
                        container.getTrainingExerciseUseCase.execute(
                            GetTrainingExerciseUseCase.Input(
                                userId = userId,
                                excludeExerciseId = -1L,
                                mode = mode
                            )
                        )
                    } else {
                        second
                    }
                }
            } else {
                result
            }

            when (finalResult) {
                is GetTrainingExerciseUseCase.Result.Success -> {
                    completedRatedSittings = finalResult.completedRatedSittings
                    interleavedQueue.onExerciseServed(finalResult.exercise.tacticalPattern.name)
                    if (isSmart) {
                        finalResult.exercise.id?.let { smartSessionSeenIds.add(it) }
                        _uiState.value = _uiState.value.copy(
                            sessionStepNumber = smartSessionStep,
                            sessionTotalSteps = TrainingSessionPlanner.DEFAULT_SESSION_SIZE,
                            sessionPhase = activePhase,
                            sessionSummary = null
                        )
                    }
                    setupExercise(
                        exercise = finalResult.exercise,
                        mode = mode,
                        isAlreadySolvedDaily = finalResult.isAlreadySolvedDaily,
                        isReviewOfFailed = finalResult.isReviewOfFailed,
                        dueReviewCount = finalResult.dueReviewCount
                    )
                }
                is GetTrainingExerciseUseCase.Result.NoSuitableExercises -> {
                    currentExercise = null
                    val isMistakesMode = mode is TrainingMode.MyMistakes
                    val emptyMessage = when {
                        mode is TrainingMode.FromGame && excludeId > 0L ->
                            "Отлично! Все ключевые ошибки этой партии отработаны. Можно вернуться к разбору партии или продолжить тренировку в общем режиме."
                        mode is TrainingMode.FromGame ->
                            "В этой партии не найдено подходящих тактических ошибок для тренажёра. Вернитесь к разбору партии или выберите общий режим!"
                        isMistakesMode ->
                            "У вас пока нет задач из собственных партий. Загрузите PGN и запустите анализ — или выберите общий режим!"
                        else ->
                            "В выбранной теме пока нет задач. Попробуйте другую тему или общий режим."
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isInputLocked = true,
                        hasActiveExercise = false,
                        hasNoUserMistakes = isMistakesMode,
                        feedbackMessage = emptyMessage
                    )
                }
                is GetTrainingExerciseUseCase.Result.Error -> {
                    currentExercise = null
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isInitializationError = true,
                        isInputLocked = true,
                        hasActiveExercise = false,
                        feedbackMessage = finalResult.message
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading exercise", e)
            currentExercise = null
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isInitializationError = true,
                isInputLocked = true,
                hasActiveExercise = false,
                feedbackMessage = "Сбой: ${e.message}"
            )
        }
    }

    private fun resetStateForNewExercise() {
        analysisJob?.cancel()
        analysisHistory.clear()
        targetSequence.clear()
        pendingCheck = null
        recordedMoves.clear()
        currentMoveIndex = 0
        attemptMistakeCount = 0
        attemptFailed = false
        failedAttemptSaved = false
        maxHintUsed = HintUsage.NONE
        isCurrentDailyAlreadySolved = false
        startTime = System.currentTimeMillis()
        firstMoveThinkTimeMs = null
        positionBeforePlayerMove = ""
        lastMoveBeforePlayerMove = null
        initialTriggerMoveUci = null
        currentCuratedExplanation = null
        lastMistakeCoachingNote = null
        smartSessionOutcomeRecorded = false
    }

    private suspend fun setupExercise(
        exercise: Exercise,
        mode: TrainingMode,
        isAlreadySolvedDaily: Boolean,
        isReviewOfFailed: Boolean,
        dueReviewCount: Int = 0
    ) {
        currentExercise = exercise
        isCurrentDailyAlreadySolved = isAlreadySolvedDaily

        val newBoard = Board()
        newBoard.loadFromFen(exercise.fenPosition)
        positionBeforePlayerMove = exercise.fenPosition

        val curatedId = ExerciseMetadata.extractCuratedId(exercise)
        val curatedTemplate = curatedId?.let { CuratedExerciseBank.findTemplateById(it) }
            ?: CuratedExerciseBank.findTemplateByFen(exercise.fenPosition)

        val normalizedStored = normalizeSequenceToUci(exercise.fenPosition, exercise.solutionMoves)
        val oddStored = TrainingRatingCalculator.trimPvToPlayerLastMove(normalizedStored, MAX_SOLUTION_PLIES)

        if (curatedTemplate != null && oddStored.isNotEmpty()) {
            // Для верифицированных задач используем их точную проверенную линию
            targetSequence.addAll(oddStored)
        } else if (oddStored.size < 3) {
            extendSolutionWithEngine(oddStored, newBoard)
        } else {
            targetSequence.addAll(oddStored)
        }

        val prevMoveUci = ExerciseMetadata.extractPrevMove(exercise)
            ?: curatedTemplate?.prevMoveUci
        val preFen = ExerciseMetadata.extractPreFen(exercise)
            ?: curatedTemplate?.preFen
        val canAnimateIntro = SolutionReviewHelper.validateTriggerTransition(
            preFen = preFen,
            prevMoveUci = prevMoveUci,
            targetFen = exercise.fenPosition
        )

        initialTriggerMoveUci = prevMoveUci
        currentCuratedExplanation = curatedTemplate?.explanation
        val initialPrevMove = prevMoveUci?.let { parseSquaresFromUci(it) }
        lastMoveBeforePlayerMove = initialPrevMove

        val effectiveDiff = exercise.getEffectiveDifficulty()
        val totalPlayerSteps = ((targetSequence.size + 1) / 2).coerceAtLeast(1)
        val isDefensive = ExerciseMetadata.isDefensiveGoal(exercise)

        val title = curatedTemplate?.title
            ?: ExerciseMetadata.extractTitle(exercise)
            ?: exercise.tacticalPattern.displayName
        // Без микро-спойлеров мотива и глубины расчёта ДО решения (как в живой партии)
        val neutralPrompt = SolutionReviewHelper.buildNeutralPrompt(
            playerSide = newBoard.sideToMove,
            hasOpponentTriggerMove = initialPrevMove != null,
            isDefensiveGoal = isDefensive
        )

        val initialBanner = when {
            isAlreadySolvedDaily -> "Повтор без изменения рейтинга"
            mode is TrainingMode.DeepCalculation -> "Досчитайте весь форсированный вариант до первого хода"
            else -> null
        }

        val baseReadyState = _uiState.value.copy(
            mode = mode,
            playerSide = newBoard.sideToMove,
            arrows = emptyList(),
            highlights = emptyList(),
            hintLevel = HintLevel.NONE,
            isSolved = false,
            isSolutionRevealed = false,
            isAnimatingPunishment = false,
            isLoading = false,
            hasActiveExercise = true,
            checkUnavailable = false,
            hasNoUserMistakes = false,
            lastRatingDelta = null,
            exerciseTitle = title,
            exerciseSubtitle = neutralPrompt,
            currentPattern = exercise.tacticalPattern.name,
            patternDisplayName = exercise.tacticalPattern.displayName,
            theme = exercise.theme,
            difficultyRating = effectiveDiff,
            difficultyLabel = TrainingLabels.difficultyLabel(effectiveDiff),
            exerciseElo = TrainingRatingCalculator.effectiveExerciseElo(exercise),
            sourceLabel = TrainingLabels.sourceLabel(exercise, isDailyMode = mode is TrainingMode.Daily),
            isDailyAlreadySolved = isAlreadySolvedDaily,
            moveRevealedInExercise = false,
            isReviewOfFailed = isReviewOfFailed,
            nextReviewNote = null,
            dueReviewCount = dueReviewCount,
            currentStepNumber = 1,
            totalPlayerSteps = totalPlayerSteps,
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            betterMoveShown = false,
            reviewSteps = emptyList(),
            reviewStepIndex = 0,
            solutionSanLine = null,
            postSolveExplanation = null,
            isFreeAnalysisMode = false,
            isAnalyzingPosition = false,
            analysisEvalText = null,
            analysisBestMoveSan = null,
            analysisBestMoveUci = null,
            canUndoAnalysisMove = false
        )

        // Входная анимация последнего хода соперника: тренирует живое включение тактического зрения
        if (canAnimateIntro && preFen != null && prevMoveUci != null) {
            val preBoard = runCatching { Board().apply { loadFromFen(preFen) } }.getOrNull()
            val triggerMove = preBoard?.let { ChessMoveUtils.parseUciMove(it, prevMoveUci) }
            if (preBoard != null && triggerMove != null) {
                val wasCapture = isCaptureMove(preBoard, triggerMove)
                _uiState.value = baseReadyState.copy(
                    board = preBoard,
                    lastMove = null,
                    isInputLocked = true,
                    isAnimatingIntro = true,
                    feedbackMessage = initialBanner ?: neutralPrompt
                )
                delay(INTRO_PRE_MOVE_DELAY_MS)

                soundCounter++
                val introSound = when {
                    newBoard.isKingAttacked -> TrainingSoundEvent.CHECK
                    wasCapture -> TrainingSoundEvent.CAPTURE
                    else -> TrainingSoundEvent.MOVE
                }
                startTime = System.currentTimeMillis()
                firstMoveThinkTimeMs = null
                _uiState.value = baseReadyState.copy(
                    board = newBoard,
                    lastMove = initialPrevMove,
                    isInputLocked = false,
                    isAnimatingIntro = false,
                    feedbackMessage = initialBanner,
                    soundEvent = introSound,
                    soundEventId = soundCounter
                )
            } else {
                startTime = System.currentTimeMillis()
                firstMoveThinkTimeMs = null
                _uiState.value = baseReadyState.copy(
                    board = newBoard,
                    lastMove = initialPrevMove,
                    isInputLocked = false,
                    isAnimatingIntro = false,
                    feedbackMessage = initialBanner
                )
            }
        } else {
            startTime = System.currentTimeMillis()
            firstMoveThinkTimeMs = null
            _uiState.value = baseReadyState.copy(
                board = newBoard,
                lastMove = initialPrevMove,
                isInputLocked = false,
                isAnimatingIntro = false,
                feedbackMessage = initialBanner
            )
        }

        exercise.id?.let { id ->
            runCatching { container.exerciseRepository.incrementTimesShown(id) }
        }
    }

    private fun pluralizeMoves(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "ход"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "хода"
        else -> "ходов"
    }

    private fun normalizeSequenceToUci(startFen: String, rawMoves: List<String>): List<String> {
        if (rawMoves.isEmpty()) return emptyList()
        val sim = Board().apply { loadFromFen(startFen) }
        val result = mutableListOf<String>()
        for (raw in rawMoves) {
            val uci = ChessMoveUtils.sanOrUciToUci(raw, sim.fen) ?: break
            if (!ChessMoveUtils.applyUciMove(sim, uci)) break
            result.add(uci)
        }
        return result
    }

    /**
     * Дополняет короткое (1-ходовое) решение через движок, проверяя легальность каждого полухода
     * и обрезая линию до нечётной длины, чтобы последний ход всегда делал игрок.
     */
    private suspend fun extendSolutionWithEngine(fallbackUci: List<String>, board: Board) {
        withContext(Dispatchers.IO) {
            try {
                val engine = container.chessEngine
                if (engine.init()) {
                    val line = engine.getBestMoveWithLine(board.fen, PUZZLE_GEN_DEPTH)
                    val validatedPv = normalizeSequenceToUci(
                        board.fen,
                        line?.principalVariation?.take(MAX_SOLUTION_PLIES).orEmpty()
                    )
                    // Обрезаем линию строго в точке успокоения позиции (после разменов/шахов),
                    // не навязывая игроку угадывание лишних «тихих» ходов
                    val oddPv = PuzzleQualityEvaluator.trimPvAtQuiescence(
                        startFen = board.fen,
                        rawUciMoves = validatedPv,
                        maxPlies = MAX_SOLUTION_PLIES
                    )

                    when {
                        oddPv.isNotEmpty() -> targetSequence.addAll(oddPv)
                        !line?.bestMove.isNullOrBlank() -> {
                            val single = ChessMoveUtils.sanOrUciToUci(line!!.bestMove, board.fen)
                            if (single != null) targetSequence.add(single) else targetSequence.addAll(fallbackUci)
                        }
                        fallbackUci.isNotEmpty() -> targetSequence.addAll(fallbackUci)
                    }
                } else {
                    targetSequence.addAll(fallbackUci)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error extending solution", e)
                targetSequence.addAll(fallbackUci)
            }
        }
    }

    // ================================================================
    // ОБРАБОТКА ХОДА ИГРОКА
    // ================================================================

    fun onMoveMade(move: Move) {
        val state = _uiState.value
        if (state.isLoading || state.isAnimatingIntro || state.isAnimatingPunishment || !state.hasActiveExercise) {
            return
        }

        // В режиме пост-разбора со Stockfish игрок может свободно двигать фигуры за обе стороны
        if (state.isFreeAnalysisMode && (state.isSolved || state.isSolutionRevealed)) {
            handleFreeAnalysisMove(move)
            return
        }

        if (state.isInputLocked || state.isSolved || state.isSolutionRevealed) {
            return
        }
        if (state.board.sideToMove != state.playerSide) return

        if (!ChessMoveUtils.isStrictlyLegal(state.board, move)) {
            triggerShakeEffect()
            emitSound(TrainingSoundEvent.ERROR)
            _uiState.value = _uiState.value.copy(feedbackMessage = "Невозможный ход")
            viewModelScope.launch {
                delay(1000)
                if (_uiState.value.feedbackMessage == "Невозможный ход") {
                    _uiState.value = _uiState.value.copy(feedbackMessage = null)
                }
            }
            return
        }

        val previousThinkMs = firstMoveThinkTimeMs
        if (firstMoveThinkTimeMs == null && currentMoveIndex == 0 && attemptMistakeCount == 0) {
            firstMoveThinkTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        }

        positionBeforePlayerMove = state.board.fen
        lastMoveBeforePlayerMove = state.lastMove

        val newBoard = Board()
        newBoard.loadFromFen(positionBeforePlayerMove)
        val wasCapture = isCaptureMove(newBoard, move)
        newBoard.doMove(move)
        val movePair = move.from to move.to

        soundCounter++
        val moveSound = when {
            newBoard.isKingAttacked -> TrainingSoundEvent.CHECK
            wasCapture -> TrainingSoundEvent.CAPTURE
            else -> TrainingSoundEvent.MOVE
        }

        _uiState.value = state.copy(
            board = newBoard,
            lastMove = movePair,
            isInputLocked = true,
            arrows = emptyList(),
            highlights = emptyList(),
            hintLevel = HintLevel.NONE,
            lastRatingDelta = null,
            feedbackMessage = null,
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            betterMoveShown = false,
            soundEvent = moveSound,
            soundEventId = soundCounter
        )

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            processUserMove(PendingMoveCheck(state, move, previousThinkMs, System.currentTimeMillis()))
        }
    }

    private fun isCaptureMove(board: Board, move: Move): Boolean {
        if (board.getPiece(move.to) != Piece.NONE) return true
        val moving = board.getPiece(move.from)
        return moving.pieceType == com.github.bhlangonijr.chesslib.PieceType.PAWN &&
            move.from.file != move.to.file
    }

    private fun emitSound(event: TrainingSoundEvent) {
        soundCounter++
        _uiState.value = _uiState.value.copy(
            soundEvent = event,
            soundEventId = soundCounter
        )
    }

    private fun emitBoardWithSound(
        newBoard: Board,
        movePair: Pair<Square, Square>?,
        wasCapture: Boolean,
        unlockInput: Boolean = false
    ) {
        soundCounter++
        val sound = when {
            newBoard.isKingAttacked -> TrainingSoundEvent.CHECK
            wasCapture -> TrainingSoundEvent.CAPTURE
            else -> TrainingSoundEvent.MOVE
        }
        _uiState.value = _uiState.value.copy(
            board = newBoard,
            lastMove = movePair,
            arrows = emptyList(),
            isInputLocked = !unlockInput,
            soundEvent = sound,
            soundEventId = soundCounter
        )
    }

    private fun triggerShakeEffect() {
        _uiState.value = _uiState.value.copy(isErrorShake = true)
        viewModelScope.launch {
            delay(300)
            _uiState.value = _uiState.value.copy(isErrorShake = false)
        }
    }

    fun retryMoveCheck() {
        val check = pendingCheck ?: return
        if (!_uiState.value.checkUnavailable || _uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(checkUnavailable = false)
        currentJob = viewModelScope.launch { processUserMove(check) }
    }

    fun returnFromMoveCheck() {
        val check = pendingCheck ?: return
        if (!_uiState.value.checkUnavailable || _uiState.value.isLoading) return
        pendingCheck = null
        startTime += (System.currentTimeMillis() - check.startedAt).coerceAtLeast(0L)
        firstMoveThinkTimeMs = check.previousThinkMs
        // Restore the pre-move board and its hint without replaying an old sound.
        _uiState.value = check.before.copy(soundEvent = null, soundEventId = soundCounter)
    }

    private suspend fun processUserMove(check: PendingMoveCheck) {
        val fenBefore = check.before.board.fen
        val move = check.move
        val userUci = ChessMoveUtils.moveToUci(move)

        val expected = targetSequence.getOrNull(currentMoveIndex)?.lowercase()
        val isExactExpected = expected != null && userUci == expected

        if (!isExactExpected) {
            _uiState.value = _uiState.value.copy(
                isInputLocked = true,
                feedbackMessage = "Проверяем вариант..."
            )

            val defensive = currentExercise?.let { ExerciseMetadata.isDefensiveGoal(it) } == true
            val quality = try {
                withContext(Dispatchers.IO) { verifyTrainingMove(verificationEngine, fenBefore, move, defensive) }
            } catch (e: CancellationException) {
                // Leaving the exercise cancels this job. Cancelling only the engine's
                // initialization, however, must not leave the current board locked.
                currentCoroutineContext().ensureActive()
                TrainingMoveQuality.UNAVAILABLE
            }
            if (quality == TrainingMoveQuality.UNAVAILABLE) {
                pendingCheck = check
                _uiState.value = _uiState.value.copy(
                    checkUnavailable = true, feedbackMessage = "Не удалось оценить ход. Ошибка не засчитана."
                )
                return
            }
            pendingCheck = null
            startTime += (System.currentTimeMillis() - check.startedAt).coerceAtLeast(0L)
            recordedMoves.add(userUci)
            if (quality == TrainingMoveQuality.BEST || quality == TrainingMoveQuality.EXCELLENT) {
                val remainingPlies = (targetSequence.size - currentMoveIndex - 1).coerceAtLeast(0)
                // Если в задаче предполагалось продолжение и мата ещё нет — пробуем продолжить комбинацию с движком
                if (remainingPlies >= 2 && !_uiState.value.board.isMated) {
                    val continuation = fetchEvenContinuationForOpponent(
                        fenAfterUserMove = _uiState.value.board.fen,
                        maxPlies = remainingPlies.coerceAtMost(4)
                    )
                    if (continuation.size >= 2) {
                        // Перестраиваем остаток последовательности под сильный альтернативный ход игрока
                        val prefix = targetSequence.take(currentMoveIndex) + userUci
                        targetSequence.clear()
                        targetSequence.addAll(prefix + continuation)
                        currentMoveIndex = prefix.size
                        val totalSteps = ((targetSequence.size + 1) / 2).coerceAtLeast(1)

                        delay(HUMAN_REACTION_DELAY_MS)
                        val oppUci = targetSequence[currentMoveIndex]
                        val applied = makeOpponentMove(oppUci)
                        currentMoveIndex++

                        if (!applied || currentMoveIndex >= targetSequence.size || _uiState.value.board.isMated) {
                            handleSuccess("Отличный альтернативный ход!")
                        } else {
                            val nextStep = (currentMoveIndex / 2) + 1
                            _uiState.value = _uiState.value.copy(
                                currentStepNumber = nextStep,
                                totalPlayerSteps = totalSteps,
                                feedbackMessage = "Сильный ход! Найдите продолжение."
                            )
                        }
                        return
                    }
                }

                handleSuccess("Отличный альтернативный ход!")
                return
            } else {
                handleMistakeComprehensive(fenBefore, move)
                return
            }
        }

        recordedMoves.add(userUci)
        currentMoveIndex++

        if (targetSequence.isNotEmpty() && currentMoveIndex < targetSequence.size && !_uiState.value.board.isMated) {
            delay(HUMAN_REACTION_DELAY_MS)

            val opponentMoveUci = targetSequence[currentMoveIndex]
            val applied = makeOpponentMove(opponentMoveUci)
            currentMoveIndex++

            if (!applied || currentMoveIndex >= targetSequence.size || _uiState.value.board.isMated) {
                handleSuccess("Комбинация завершена!")
            } else {
                val nextStep = (currentMoveIndex / 2) + 1
                _uiState.value = _uiState.value.copy(
                    currentStepNumber = nextStep,
                    feedbackMessage = "Верно. Найдите продолжение."
                )
            }
        } else {
            val message = if (_uiState.value.board.isMated) {
                "Мат! Задача решена!"
            } else {
                "Задача решена!"
            }
            handleSuccess(message)
        }
    }

    /**
     * Запрашивает у движка чётное число полуходов после альтернативного хода игрока:
     * `[ответ_соперника, добивающий_ход_игрока, ...]`, чтобы финал остался за игроком.
     */
    private suspend fun fetchEvenContinuationForOpponent(
        fenAfterUserMove: String,
        maxPlies: Int
    ): List<String> = withContext(Dispatchers.IO) {
        if (maxPlies < 2) return@withContext emptyList()
        try {
            val engine = container.chessEngine
            if (!engine.init()) return@withContext emptyList()
            val line = engine.getBestMoveWithLine(fenAfterUserMove, VERIFICATION_DEPTH) ?: return@withContext emptyList()
            val validated = normalizeSequenceToUci(fenAfterUserMove, line.principalVariation.take(maxPlies))
            if (validated.size < 2) return@withContext emptyList()
            if (validated.size % 2 == 0) validated else validated.dropLast(1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun makeOpponentMove(uci: String): Boolean {
        val board = Board()
        board.loadFromFen(_uiState.value.board.fen)
        val parsed = ChessMoveUtils.parseUciMove(board, uci) ?: return false
        val wasCapture = isCaptureMove(board, parsed)
        val ok = board.doMove(parsed)
        if (!ok) return false

        recordedMoves.add(ChessMoveUtils.moveToUci(parsed))
        positionBeforePlayerMove = board.fen
        val pair = parsed.from to parsed.to
        lastMoveBeforePlayerMove = pair

        emitBoardWithSound(
            newBoard = board,
            movePair = pair,
            wasCapture = wasCapture,
            unlockInput = true
        )
        return true
    }

    // ================================================================
    // АНАЛИЗ ОШИБКИ
    // ================================================================

    private suspend fun handleMistakeComprehensive(fenBefore: String, userMove: Move) {
        val isFirstMistakeOnExercise = !attemptFailed
        val exercise = currentExercise
        val user = currentUser

        // Детектор импульсивного первого хода («игры руками» без расчёта ответа соперника)
        val thinkMs = firstMoveThinkTimeMs
        val activeMode = _uiState.value.mode
        val wasImpulsiveFirstMove = isFirstMistakeOnExercise &&
            TrainingRatingCalculator.isImpulsiveMistake(
                firstMoveThinkMs = thinkMs,
                exercise = exercise,
                isFirstMoveOfPuzzle = currentMoveIndex == 0,
                mode = activeMode
            )

        attemptMistakeCount++
        attemptFailed = true

        // Тренерская диагностика дисциплины расчёта
        lastMistakeCoachingNote = when {
            wasImpulsiveFirstMove && thinkMs != null -> {
                val secs = String.format(java.util.Locale.US, "%.1f", thinkMs / 1000.0)
                "Поспешный ход ($secs с)! Сначала проверьте шахи, взятия и лучший ответ соперника."
            }
            attemptMistakeCount >= 2 -> {
                "Не перебирайте ходы наугад — посчитайте ответ соперника или возьмите подсказку фигуры."
            }
            else -> null
        }

        val stats = _uiState.value.sessionStats
        var shouldSaveFirstFailedAttempt = false
        var appliedDelta: Int? = null

        if (!isCurrentDailyAlreadySolved && exercise != null && user != null) {
            if (isFirstMistakeOnExercise && !failedAttemptSaved) {
                failedAttemptSaved = true
                shouldSaveFirstFailedAttempt = true
                appliedDelta = TrainingRatingCalculator.calculateFailureDelta(
                    playerElo = user.rating,
                    exercise = exercise,
                    completedRatedSittings = completedRatedSittings
                )
            } else {
                // Мягкий дисциплинирующий штраф за повторный слепой перебор ходов в той же задаче
                val guessPenalty = TrainingRatingCalculator.calculateRepeatedGuessPenalty(attemptMistakeCount)
                if (guessPenalty != 0) {
                    appliedDelta = guessPenalty
                }
            }
        }

        if (activeMode is TrainingMode.SmartSession) {
            smartSessionNetElo += (appliedDelta ?: 0)
            if (wasImpulsiveFirstMove) {
                smartSessionImpulsiveCount++
            }
        }

        val updatedNet = stats.netRatingDelta + (appliedDelta ?: 0)
        val updatedImpulsive = if (wasImpulsiveFirstMove) stats.impulsiveMistakes + 1 else stats.impulsiveMistakes
        soundCounter++
        _uiState.value = _uiState.value.copy(
            isInputLocked = true,
            isAnimatingPunishment = true,
            lastRatingDelta = appliedDelta,
            sessionStats = stats.copy(
                failed = stats.failed + 1,
                impulsiveMistakes = updatedImpulsive,
                currentStreak = 0,
                netRatingDelta = updatedNet
            ),
            feedbackMessage = "Проверяем ответ соперника…",
            soundEvent = TrainingSoundEvent.ERROR,
            soundEventId = soundCounter
        )

        if (shouldSaveFirstFailedAttempt || appliedDelta != null) {
            runCatching {
                if (shouldSaveFirstFailedAttempt) {
                    saveAttempt(solved = false, wasImpulsive = wasImpulsiveFirstMove)
                    completedRatedSittings++
                }
                if (appliedDelta != null) {
                    updateRating(appliedDelta)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error saving failed attempt", e)
            }
        }

        // Отложенное чередование мотива (даже если движок недоступен — по мотиву самой задачи)
        if (isFirstMistakeOnExercise) {
            scheduleInterleavedReinforcement(null)
        }

        try {
            val engine = container.chessEngine
            if (!engine.init()) {
                fallbackMistakeHandling(fenBefore, userMove)
                return
            }

            val boardAfterPlayer = Board()
            boardAfterPlayer.loadFromFen(fenBefore)
            if (!boardAfterPlayer.doMove(userMove)) {
                fallbackMistakeHandling(fenBefore, userMove)
                return
            }

            val punishmentLineObj = withContext(Dispatchers.IO) {
                engine.getBestMoveWithLine(boardAfterPlayer.fen, VERIFICATION_DEPTH)
            }

            val bestMoveLineObj = withContext(Dispatchers.IO) {
                engine.getBestMoveWithLine(fenBefore, VERIFICATION_DEPTH)
            }

            if (punishmentLineObj == null || bestMoveLineObj == null) {
                fallbackMistakeHandling(fenBefore, userMove)
                return
            }

            val boardBefore = Board()
            boardBefore.loadFromFen(fenBefore)

            val analysis = try {
                semanticAnalyzer.analyzeComprehensive(
                    boardBefore = boardBefore,
                    playerMove = userMove,
                    bestMoveLine = bestMoveLineObj,
                    punishmentLine = punishmentLineObj
                )
            } catch (e: Exception) {
                Log.e(TAG, "Semantic analysis error", e)
                fallbackMistakeHandling(fenBefore, userMove)
                return
            }

            if (isFirstMistakeOnExercise) {
                scheduleInterleavedReinforcement(analysis)
            }
            displayMistakeAnalysis(boardAfterPlayer, analysis, punishmentLineObj.principalVariation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleMistakeComprehensive", e)
            fallbackMistakeHandling(fenBefore, userMove)
        }
    }

    private suspend fun displayMistakeAnalysis(
        boardAfterPlayer: Board,
        analysis: MistakeAnalysis,
        punishmentMoves: List<String>
    ) {
        val cleanExplanation = cleanEvaluationText(analysis.explanation)
        val coachingSuffix = lastMistakeCoachingNote?.let { "\n$it" } ?: ""

        _uiState.value = _uiState.value.copy(
            feedbackMessage = (if (punishmentMoves.isEmpty()) "Не лучший ход" else "Ответ соперника") + "\n" + cleanExplanation + coachingSuffix,
            currentMistakeAnalysis = analysis,
            showBetterMoveHint = analysis.shouldShowBetterMove()
        )

        if (punishmentMoves.isNotEmpty()) {
            animatePunishmentLine(boardAfterPlayer, punishmentMoves)
        }

        delay(PUNISHMENT_DISPLAY_MS)
        resetToPosition(positionBeforePlayerMove, keepAnalysis = true)
    }

    private suspend fun animatePunishmentLine(startBoard: Board, moves: List<String>) {
        val currentBoard = Board().apply { loadFromFen(startBoard.fen) }

        for ((index, moveUci) in moves.withIndex()) {
            if (index >= 4) break

            val arrow = createArrowFromUci(moveUci, Color(0xAAF44336)) ?: break

            _uiState.value = _uiState.value.copy(
                punishmentAnimationStep = index + 1,
                arrows = listOf(arrow)
            )

            delay(ANIMATION_DELAY_MS)

            val parsed = ChessMoveUtils.parseUciMove(currentBoard, moveUci) ?: break
            if (!currentBoard.doMove(parsed)) break

            _uiState.value = _uiState.value.copy(
                board = Board().apply { loadFromFen(currentBoard.fen) },
                lastMove = parsed.from to parsed.to,
                arrows = emptyList()
            )

            delay(ANIMATION_DELAY_MS / 2)
        }
    }

    private suspend fun fallbackMistakeHandling(fenBefore: String, userMove: Move) {
        try {
            val engine = container.chessEngine
            if (engine.init()) {
                val boardAfterPlayer = Board()
                boardAfterPlayer.loadFromFen(fenBefore)
                if (boardAfterPlayer.doMove(userMove)) {
                    val punishmentUci = withContext(Dispatchers.IO) {
                        engine.getBestMove(boardAfterPlayer.fen, 10)
                    }

                    val arrow = punishmentUci?.let { createArrowFromUci(it, Color(0xAAF44336)) }
                    if (punishmentUci != null && arrow != null) {
                        _uiState.value = _uiState.value.copy(
                            arrows = listOf(arrow),
                            feedbackMessage = "Ответ соперника",
                            currentMistakeAnalysis = null
                        )

                        delay(ANIMATION_DELAY_MS)
                        val parsed = ChessMoveUtils.parseUciMove(boardAfterPlayer, punishmentUci)
                        if (parsed != null && boardAfterPlayer.doMove(parsed)) {
                            _uiState.value = _uiState.value.copy(
                                board = boardAfterPlayer,
                                lastMove = parsed.from to parsed.to
                            )
                        }
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    feedbackMessage = "Не лучший ход"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fallback mistake handling failed", e)
            _uiState.value = _uiState.value.copy(feedbackMessage = "Неверный ход")
        }

        delay(PUNISHMENT_DISPLAY_MS)
        resetToPosition(fenBefore, keepAnalysis = false)
    }

    private fun resetToPosition(fen: String, keepAnalysis: Boolean) {
        if (fen.isBlank()) return
        val board = Board()
        board.loadFromFen(fen)

        val prev = _uiState.value
        val keptExplanation = if (keepAnalysis) {
            prev.currentMistakeAnalysis?.explanation
                ?.let { cleanEvaluationText(it) }
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val coaching = lastMistakeCoachingNote
        val prompt = buildString {
            append("Попробуйте ещё раз")
            if (keptExplanation != null) {
                append('\n')
                append(keptExplanation)
            }
            if (coaching != null) {
                append('\n')
                append(coaching)
            }
        }

        _uiState.value = prev.copy(
            board = board,
            lastMove = lastMoveBeforePlayerMove,
            isInputLocked = false,
            isAnimatingPunishment = false,
            arrows = emptyList(),
            punishmentAnimationStep = 0,
            currentMistakeAnalysis = if (keepAnalysis) prev.currentMistakeAnalysis else null,
            showBetterMoveHint = if (keepAnalysis) prev.showBetterMoveHint else false,
            feedbackMessage = prompt
        )
    }

    // ================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ================================================================

    /**
     * Планирует отложенный (interleaved) возврат к ошибочному тактическому мотиву
     * через 2 и 5 задач на другие темы — без механических «блоков подряд» и без спойлера.
     */
    private fun scheduleInterleavedReinforcement(analysis: MistakeAnalysis?) {
        val mode = _uiState.value.mode
        if (mode !is TrainingMode.All && mode !is TrainingMode.SmartSession) return
        val pattern = analysis?.tacticalMotif?.let { TacticalPattern.fromMotif(it) }
            ?: currentExercise?.tacticalPattern
            ?: return
        interleavedQueue.scheduleAfterMistake(pattern.name)
    }

    private fun cleanEvaluationText(text: String): String {
        val textWithoutBrackets = text.replace(Regex("\\s*\\([+-]?\\d+\\.?\\d*\\)"), "")
        return textWithoutBrackets.replace(Regex("[+-]?\\d+\\.\\d+"), "").trim()
    }

    private fun parseSquaresFromUci(uci: String): Pair<Square, Square>? {
        val normalized = uci.trim()
        if (normalized.length < 4) return null
        return try {
            val from = Square.fromValue(normalized.substring(0, 2).uppercase())
            val to = Square.fromValue(normalized.substring(2, 4).uppercase())
            if (from == Square.NONE || to == Square.NONE) null else from to to
        } catch (e: Exception) {
            null
        }
    }

    private fun createArrowFromUci(uci: String, color: Color): BoardArrow? {
        val (from, to) = parseSquaresFromUci(uci) ?: return null
        return BoardArrow(from, to, color)
    }

    private suspend fun updateRating(change: Int) {
        if (change == 0) return
        val user = currentUser ?: return
        if (user.id == null) return
        val newRating = (user.rating + change).coerceIn(
            TrainingRatingCalculator.MIN_RATING,
            TrainingRatingCalculator.MAX_RATING
        )
        val updated = user.withNewRating(newRating)
        currentUser = updated
        container.userRepository.update(updated)
        onUserUpdatedCallback?.invoke(updated)
    }

    private fun captureAttemptSnapshot(timeSpentSeconds: Int? = null): AttemptSnapshot? {
        val userId = currentUser?.id ?: return null
        val exerciseId = currentExercise?.id ?: return null
        val elapsed = timeSpentSeconds ?: ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceAtLeast(0)
        return AttemptSnapshot(
            userId = userId,
            exerciseId = exerciseId,
            rawUciMoves = recordedMoves.toList(),
            hintUsage = maxHintUsed,
            retryCount = attemptMistakeCount.coerceAtLeast(0),
            firstMoveThinkMs = firstMoveThinkTimeMs,
            timeSpentSeconds = elapsed
        )
    }

    private suspend fun saveAttempt(
        solved: Boolean,
        solutionRevealed: Boolean = false,
        wasImpulsive: Boolean = false,
        snapshot: AttemptSnapshot? = captureAttemptSnapshot()
    ) {
        val data = snapshot ?: return
        val now = System.currentTimeMillis()

        val taggedMoves = AttemptMetadata.buildTaggedMoves(
            rawUciMoves = data.rawUciMoves,
            hintUsage = data.hintUsage,
            retryCount = data.retryCount,
            firstMoveThinkMs = data.firstMoveThinkMs,
            solutionRevealed = solutionRevealed,
            wasImpulsive = wasImpulsive
        )

        val attempt = ExerciseAttempt(
            id = null,
            userId = data.userId,
            exerciseId = data.exerciseId,
            solved = solved,
            timeSpentSeconds = data.timeSpentSeconds,
            movesMade = taggedMoves,
            attemptedAt = now
        )
        container.exerciseRepository.saveAttempt(attempt)
    }

    /**
     * Пересчитывает состояние SM-2/Лейтнера для текущей задачи и счётчик подошедших повторов.
     */
    private suspend fun refreshCardAndDueCount(targetExercise: Exercise? = currentExercise): ReviewCardState? {
        val userId = currentUser?.id ?: return null
        val exerciseId = targetExercise?.id
        return try {
            val now = System.currentTimeMillis()
            val accessible = container.exerciseRepository.findAccessibleByUserId(userId)
            val byId = accessible.mapNotNull { ex -> ex.id?.let { it to ex } }.toMap()
            val attempts = container.exerciseRepository.getAttemptsByUserId(userId)
            val schedule = SpacedRepetitionScheduler.buildSchedule(attempts, byId)
            val dueCount = accessible.count { ex ->
                val id = ex.id ?: return@count false
                schedule[id]?.isDue(now) == true
            }
            val currentCard = exerciseId?.let { id ->
                schedule[id] ?: SpacedRepetitionScheduler.computeCardState(
                    id,
                    attempts.filter { it.exerciseId == id },
                    targetExercise
                )
            }
            _uiState.value = _uiState.value.copy(
                dueReviewCount = dueCount
            )
            currentCard
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh spaced-repetition state", e)
            null
        }
    }

    // ================================================================
    // ПОДСКАЗКИ И ЗАВЕРШЕНИЕ ЗАДАЧИ
    // ================================================================

    fun onShowBetterMoveClicked() {
        val state = _uiState.value
        val analysis = state.currentMistakeAnalysis ?: return
        val betterMove = analysis.betterMoveInfo ?: return
        if (state.betterMoveShown) return

        val arrow = createArrowFromUci(betterMove.moveUci, Color(0xAA4CAF50)) ?: return
        maxHintUsed = HintUsage.FULL_SOLUTION

        _uiState.value = state.copy(
            arrows = listOf(arrow),
            betterMoveShown = true,
            moveRevealedInExercise = true,
            feedbackMessage = "Лучше было: ${betterMove.explanation}"
        )
    }

    fun onHintClicked() {
        val state = _uiState.value
        if (state.isSolved || state.isSolutionRevealed || state.isInputLocked ||
            state.isAnimatingPunishment || state.isLoading || !state.hasActiveExercise
        ) {
            return
        }

        val correctMoveUci = targetSequence.getOrNull(currentMoveIndex)
        if (correctMoveUci == null || correctMoveUci.length < 4) {
            _uiState.value = state.copy(feedbackMessage = "Подсказка недоступна")
            return
        }

        val fromSq = correctMoveUci.substring(0, 2)
        val nextLevel = when (state.hintLevel) {
            HintLevel.NONE -> HintLevel.PIECE_HIGHLIGHT
            HintLevel.PIECE_HIGHLIGHT, HintLevel.FULL_SOLUTION -> HintLevel.FULL_SOLUTION
        }

        when (nextLevel) {
            HintLevel.PIECE_HIGHLIGHT -> {
                if (maxHintUsed == HintUsage.NONE) {
                    maxHintUsed = HintUsage.PIECE_HIGHLIGHT
                }
                _uiState.value = state.copy(
                    hintLevel = nextLevel,
                    highlights = listOf(fromSq),
                    arrows = emptyList(),
                    feedbackMessage = "Найдите ход фигурой с $fromSq"
                )
            }
            HintLevel.FULL_SOLUTION -> {
                maxHintUsed = HintUsage.FULL_SOLUTION
                val arrow = createArrowFromUci(correctMoveUci, Color(0xAA4CAF50)) ?: return
                val san = ChessMoveUtils.uciToSan(correctMoveUci, state.board.fen)
                _uiState.value = state.copy(
                    hintLevel = nextLevel,
                    highlights = emptyList(),
                    arrows = listOf(arrow),
                    moveRevealedInExercise = true,
                    feedbackMessage = "Лучший ход: $san (без начисления рейтинга)"
                )
            }
            HintLevel.NONE -> {}
        }
    }

    private fun handleSuccess(msg: String) {
        val exercise = currentExercise
        val user = currentUser
        val usedFullHint = maxHintUsed == HintUsage.FULL_SOLUTION

        val ratingDelta = if (exercise != null && user != null && !isCurrentDailyAlreadySolved) {
            TrainingRatingCalculator.calculateSolveDelta(
                playerElo = user.rating,
                exercise = exercise,
                hadMistake = attemptFailed,
                maxHintUsed = maxHintUsed,
                solutionRevealed = false,
                completedRatedSittings = completedRatedSittings,
                mistakeCount = attemptMistakeCount
            )
        } else {
            0
        }

        val solveSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceIn(1, 600)
        val activeMode = _uiState.value.mode
        val isCleanFirstTry = !attemptFailed && maxHintUsed == HintUsage.NONE && !isCurrentDailyAlreadySolved

        // Фиксируем результат шага структурированной сессии (10 задач)
        if (activeMode is TrainingMode.SmartSession && !smartSessionOutcomeRecorded) {
            smartSessionOutcomeRecorded = true
            smartSessionTotalSeconds += solveSeconds
            smartSessionNetElo += ratingDelta
            if (!usedFullHint) {
                smartSessionSolvedCount++
            }
            if (isCleanFirstTry) {
                smartSessionCleanCount++
            } else if (attemptFailed || usedFullHint) {
                smartSessionFailedCount++
                exercise?.tacticalPattern?.displayName?.let { smartSessionMissedPatterns.add(it) }
            }
        }

        // Чистое решение (без раскрытия полного ответа и без ошибок) увеличивает серию
        val stats = _uiState.value.sessionStats
        val countsForStreak = !attemptFailed && !usedFullHint && !isCurrentDailyAlreadySolved
        val newStreak = if (countsForStreak) stats.currentStreak + 1 else stats.currentStreak
        val newBestStreak = maxOf(stats.bestStreak, newStreak)

        val cognitiveNote = if (exercise != null) {
            TrainingSessionPlanner.formatCognitiveModeFeedback(
                mode = activeMode,
                exercise = exercise,
                solveTimeSeconds = solveSeconds,
                isCleanFirstTry = isCleanFirstTry
            )?.let { "\n$it" } ?: ""
        } else {
            ""
        }

        val postReview = buildPostSolveReviewState()
        val outcome = when {
            usedFullHint -> "Завершено с показом хода"
            maxHintUsed == HintUsage.PIECE_HIGHLIGHT -> "Решено с подсказкой фигуры"
            else -> msg
        }
        val combinedMessage = outcome + cognitiveNote

        soundCounter++
        _uiState.value = _uiState.value.copy(
            isSolved = true,
            isInputLocked = true,
            isFreeAnalysisMode = false,
            lastRatingDelta = if (isCurrentDailyAlreadySolved) null else ratingDelta,
            feedbackMessage = combinedMessage,
            reviewSteps = postReview.first,
            reviewStepIndex = (postReview.first.size - 1).coerceAtLeast(0),
            solutionSanLine = postReview.second.ifBlank { null },
            postSolveExplanation = postReview.third,
            sessionStats = stats.copy(
                solved = stats.solved + 1,
                cleanFirstTrySolved = if (isCleanFirstTry) stats.cleanFirstTrySolved + 1 else stats.cleanFirstTrySolved,
                currentStreak = newStreak,
                bestStreak = newBestStreak,
                netRatingDelta = stats.netRatingDelta + ratingDelta
            ),
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            soundEvent = TrainingSoundEvent.SOLVED,
            soundEventId = soundCounter
        )

        // Самостоятельное решение (без готовой стрелки) сохраняется как solved = true,
        // а подсказка готовым ходом — как нерешённая попытка (коробка 0, повтор через 10 мин).
        // Снимок попытки фиксируется синхронно до запуска корутины, чтобы быстрый переход
        // к следующей задаче (`loadNextExercise`) не очистил ходы и `currentExercise`.
        val markAsSolvedInDb = !usedFullHint && !isCurrentDailyAlreadySolved
        val markFullHintForReview = usedFullHint && !isCurrentDailyAlreadySolved && !failedAttemptSaved
        if (markFullHintForReview) {
            failedAttemptSaved = true
        }
        val attemptSnapshot = captureAttemptSnapshot(timeSpentSeconds = solveSeconds)
        val hadAttemptMistake = attemptFailed
        val wasCleanSolve = !hadAttemptMistake && maxHintUsed == HintUsage.NONE
        val wasDailyReplay = isCurrentDailyAlreadySolved
        val targetExerciseId = exercise?.id

        viewModelScope.launch {
            try {
                if (ratingDelta != 0) {
                    updateRating(ratingDelta)
                }
                if (markAsSolvedInDb) {
                    saveAttempt(solved = true, snapshot = attemptSnapshot)
                    if (!hadAttemptMistake && currentExercise?.id == targetExerciseId) {
                        completedRatedSittings++
                    }
                    // В глобальную успешность задачи засчитываем только чистое решение с 1-й попытки без подсказок
                    if (wasCleanSolve && targetExerciseId != null) {
                        container.exerciseRepository.incrementTimesSolved(targetExerciseId)
                    }
                } else if (markFullHintForReview) {
                    saveAttempt(solved = false, snapshot = attemptSnapshot)
                    if (currentExercise?.id == targetExerciseId) {
                        completedRatedSittings++
                    }
                }

                if (!wasDailyReplay) {
                    val updatedCard = refreshCardAndDueCount(targetExercise = exercise)
                    if (updatedCard != null && _uiState.value.isSolved && currentExercise?.id == targetExerciseId) {
                        val scheduleNote = SpacedRepetitionScheduler.formatNextIntervalNote(updatedCard)
                        _uiState.value = _uiState.value.copy(
                            nextReviewNote = scheduleNote
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error saving training success", e)
            }
        }
    }

    // ================================================================
    // ПОСТ-РАЗБОР РЕШЕНИЯ И СВОБОДНЫЙ АНАЛИЗ СО STOCKFISH
    // ================================================================

    private fun buildPostSolveReviewState(): Triple<List<SolutionReviewStep>, String, String?> {
        val exercise = currentExercise ?: return Triple(emptyList(), "", null)
        val solution = targetSequence.toList().ifEmpty { exercise.solutionMoves }
        val steps = SolutionReviewHelper.buildReviewSteps(
            startFen = exercise.fenPosition,
            solutionUci = solution,
            initialPrevMoveUci = initialTriggerMoveUci
        )
        val sanLine = SolutionReviewHelper.formatNumberedSanLine(exercise.fenPosition, solution)
        val explanation = SolutionReviewHelper.buildPostSolveExplanation(
            startFen = exercise.fenPosition,
            solutionUci = solution,
            pattern = exercise.tacticalPattern,
            curatedExplanation = currentCuratedExplanation
        )
        return Triple(steps, sanLine, explanation)
    }

    fun onReviewFirst() = applyReviewStep(0)

    fun onReviewPrev() {
        val state = _uiState.value
        applyReviewStep((state.reviewStepIndex - 1).coerceAtLeast(0))
    }

    fun onReviewNext() {
        val state = _uiState.value
        val maxIdx = (state.reviewSteps.size - 1).coerceAtLeast(0)
        applyReviewStep((state.reviewStepIndex + 1).coerceAtMost(maxIdx))
    }

    fun onReviewLast() {
        val state = _uiState.value
        applyReviewStep((state.reviewSteps.size - 1).coerceAtLeast(0))
    }

    private fun applyReviewStep(targetIndex: Int) {
        val state = _uiState.value
        if ((!state.isSolved && !state.isSolutionRevealed) || state.reviewSteps.isEmpty()) return
        val clamped = targetIndex.coerceIn(0, state.reviewSteps.lastIndex)
        val step = state.reviewSteps[clamped]

        analysisJob?.cancel()
        analysisHistory.clear()

        val board = runCatching { Board().apply { loadFromFen(step.fen) } }.getOrNull() ?: return
        val lastPair = step.lastMoveUci?.let { parseSquaresFromUci(it) }
        val stepArrow = if (clamped > 0 && step.lastMoveUci != null) {
            val color = if (step.isPlayerMove) Color(0x994CAF50) else Color(0x882196F3)
            listOfNotNull(createArrowFromUci(step.lastMoveUci, color))
        } else {
            emptyList()
        }

        soundCounter++
        val sound = if (board.isKingAttacked) TrainingSoundEvent.CHECK else TrainingSoundEvent.MOVE
        _uiState.value = state.copy(
            board = board,
            lastMove = lastPair,
            arrows = stepArrow,
            highlights = emptyList(),
            reviewStepIndex = clamped,
            isFreeAnalysisMode = false,
            isInputLocked = true,
            isAnalyzingPosition = false,
            analysisEvalText = null,
            analysisBestMoveSan = null,
            analysisBestMoveUci = null,
            canUndoAnalysisMove = false,
            soundEvent = sound,
            soundEventId = soundCounter
        )
    }

    /**
     * Включает/выключает режим свободного анализа со Stockfish после завершения задачи,
     * чтобы игрок мог проверить любой побочный вариант («а что если соперник побил ладьёй?»).
     */
    fun onToggleFreeAnalysis() {
        val state = _uiState.value
        if (!state.isSolved && !state.isSolutionRevealed) return

        if (state.isFreeAnalysisMode) {
            // Возвращаемся к текущему шагу основного решения
            if (state.reviewSteps.isNotEmpty()) {
                applyReviewStep(state.reviewStepIndex)
            } else {
                analysisJob?.cancel()
                analysisHistory.clear()
                _uiState.value = state.copy(
                    isFreeAnalysisMode = false,
                    isInputLocked = true,
                    isAnalyzingPosition = false,
                    arrows = emptyList(),
                    analysisEvalText = null,
                    analysisBestMoveSan = null,
                    analysisBestMoveUci = null,
                    canUndoAnalysisMove = false
                )
            }
            return
        }

        analysisHistory.clear()
        val currentFen = state.board.fen
        analysisHistory.add(currentFen to state.lastMove)

        _uiState.value = state.copy(
            isFreeAnalysisMode = true,
            isInputLocked = false,
            arrows = emptyList(),
            highlights = emptyList(),
            canUndoAnalysisMove = false
        )
        requestFreeAnalysisEvaluation(currentFen)
    }

    private fun handleFreeAnalysisMove(move: Move) {
        val state = _uiState.value
        val board = Board().apply { loadFromFen(state.board.fen) }
        if (!ChessMoveUtils.isStrictlyLegal(board, move)) return

        val wasCapture = isCaptureMove(board, move)
        if (!board.doMove(move)) return
        val pair = move.from to move.to
        val newFen = board.fen
        analysisHistory.add(newFen to pair)

        soundCounter++
        val sound = when {
            board.isKingAttacked -> TrainingSoundEvent.CHECK
            wasCapture -> TrainingSoundEvent.CAPTURE
            else -> TrainingSoundEvent.MOVE
        }

        _uiState.value = state.copy(
            board = board,
            lastMove = pair,
            arrows = emptyList(),
            canUndoAnalysisMove = analysisHistory.size > 1,
            soundEvent = sound,
            soundEventId = soundCounter
        )
        requestFreeAnalysisEvaluation(newFen)
    }

    fun onUndoAnalysisMove() {
        val state = _uiState.value
        if (!state.isFreeAnalysisMode || analysisHistory.size <= 1) return

        analysisHistory.removeAt(analysisHistory.lastIndex)
        val (prevFen, prevLastMove) = analysisHistory.last()
        val restored = runCatching { Board().apply { loadFromFen(prevFen) } }.getOrNull() ?: return

        soundCounter++
        _uiState.value = state.copy(
            board = restored,
            lastMove = prevLastMove,
            arrows = emptyList(),
            canUndoAnalysisMove = analysisHistory.size > 1,
            soundEvent = TrainingSoundEvent.MOVE,
            soundEventId = soundCounter
        )
        requestFreeAnalysisEvaluation(prevFen)
    }

    fun onPlayEngineBestInAnalysis() {
        val state = _uiState.value
        if (!state.isFreeAnalysisMode || state.isAnalyzingPosition) return
        val bestUci = state.analysisBestMoveUci ?: return
        val parsed = ChessMoveUtils.parseUciMove(state.board, bestUci) ?: return
        handleFreeAnalysisMove(parsed)
    }

    private fun requestFreeAnalysisEvaluation(fen: String) {
        analysisJob?.cancel()
        val boardCheck = runCatching { Board().apply { loadFromFen(fen) } }.getOrNull() ?: return

        if (boardCheck.isMated) {
            val winnerLabel = if (boardCheck.sideToMove == Side.WHITE) "0–1 (мат)" else "1–0 (мат)"
            _uiState.value = _uiState.value.copy(
                isAnalyzingPosition = false,
                analysisEvalText = winnerLabel,
                analysisBestMoveSan = null,
                analysisBestMoveUci = null,
                arrows = emptyList()
            )
            return
        }
        if (boardCheck.isStaleMate || boardCheck.isDraw) {
            _uiState.value = _uiState.value.copy(
                isAnalyzingPosition = false,
                analysisEvalText = "½–½ (ничья)",
                analysisBestMoveSan = null,
                analysisBestMoveUci = null,
                arrows = emptyList()
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isAnalyzingPosition = true,
            analysisEvalText = "Анализ...",
            analysisBestMoveSan = null,
            analysisBestMoveUci = null
        )

        analysisJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val engine = container.chessEngine
                    if (!engine.init()) return@withContext null
                    val depth = runCatching {
                        EngineAnalysisProfile.interactiveDepth(container.engineSettingsRepository.getSettings())
                    }.getOrDefault(FREE_ANALYSIS_DEPTH)
                    engine.getBestMoveWithLine(fen, depth)
                }

                val latest = _uiState.value
                if (!latest.isFreeAnalysisMode || latest.board.fen != fen) return@launch

                if (result == null) {
                    _uiState.value = latest.copy(
                        isAnalyzingPosition = false,
                        analysisEvalText = "Движок недоступен",
                        analysisBestMoveSan = null,
                        analysisBestMoveUci = null
                    )
                    return@launch
                }

                // Переводим оценку в стандартную шкалу со стороны белых (+ в пользу белых, − в пользу чёрных)
                val whiteSign = if (boardCheck.sideToMove == Side.WHITE) 1 else -1
                val evalLabel = formatEngineEvalLabel(
                    sideToMoveScore = result.score,
                    mateIn = result.mateIn,
                    whiteSign = whiteSign
                )
                val bestUci = result.bestMove.takeIf { ChessMoveUtils.isUciMove(it) }
                val bestSan = bestUci?.let { ChessMoveUtils.uciToSan(it, fen) }
                val bestArrow = bestUci?.let { listOfNotNull(createArrowFromUci(it, Color(0xAA4CAF50))) }
                    ?: emptyList()

                _uiState.value = latest.copy(
                    isAnalyzingPosition = false,
                    analysisEvalText = evalLabel,
                    analysisBestMoveSan = bestSan,
                    analysisBestMoveUci = bestUci,
                    arrows = bestArrow
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Free analysis failed", e)
                if (_uiState.value.isFreeAnalysisMode) {
                    _uiState.value = _uiState.value.copy(
                        isAnalyzingPosition = false,
                        analysisEvalText = "Ошибка анализа"
                    )
                }
            }
        }
    }

    private fun formatEngineEvalLabel(sideToMoveScore: Int, mateIn: Int?, whiteSign: Int): String {
        if (mateIn != null && mateIn != 0) {
            val whiteMate = mateIn * whiteSign
            val sign = if (whiteMate > 0) "+" else "-"
            return "${sign}М${kotlin.math.abs(whiteMate)}"
        }
        if (ChessEngine.isMateScore(sideToMoveScore)) {
            val whiteScore = sideToMoveScore * whiteSign
            return if (whiteScore > 0) "+Мат" else "-Мат"
        }
        val pawns = (sideToMoveScore * whiteSign) / 100.0
        val sign = if (pawns > 0.04) "+" else ""
        return String.format(java.util.Locale.US, "%s%.1f", sign, pawns)
    }

    fun clearSessionQueue() {
        interleavedQueue.clear()
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        generationJob?.cancel()
        analysisJob?.cancel()
        interleavedQueue.clear()
        resetSmartSessionTrackers()
        onUserUpdatedCallback = null
    }
}
