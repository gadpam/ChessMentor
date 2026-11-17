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
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.HintUsage
import com.example.chessmentor.domain.training.PuzzleQualityEvaluator
import com.example.chessmentor.domain.training.ReviewCardState
import com.example.chessmentor.domain.training.SolutionReviewHelper
import com.example.chessmentor.domain.training.SolutionReviewStep
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
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
    val isSolved: Boolean = false,
    val isSolutionRevealed: Boolean = false,
    val isLoading: Boolean = false,
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
    val isReviewOfFailed: Boolean = false,
    val reviewBox: Int? = null,
    val dueReviewCount: Int = 0,
    val currentStepNumber: Int = 1,
    val totalPlayerSteps: Int = 1,
    val isAnimatingIntro: Boolean = false,

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

    // Очередь похожих задач
    val similarExercisesQueued: Int = 0,

    // Звуковые события (soundEventId монотонно растёт для надёжной обработки в LaunchedEffect)
    val soundEvent: TrainingSoundEvent? = null,
    val soundEventId: Long = 0L
)

data class SessionStats(
    val solved: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val netRatingDelta: Int = 0
)

enum class HintLevel { NONE, PIECE_HIGHLIGHT, FULL_SOLUTION }
enum class TrainingMoveQuality { BEST, EXCELLENT, MISTAKE }

class TrainingViewModel(
    private val container: AppContainer
) : ViewModel() {

    companion object {
        private const val TAG = "TrainingVM"
        private const val INTRO_PRE_MOVE_DELAY_MS = 450L
        private const val HUMAN_REACTION_DELAY_MS = 650L
        private const val ANIMATION_DELAY_MS = 600L
        private const val PUNISHMENT_DISPLAY_MS = 2200L
        private const val SIMILAR_EXERCISES_COUNT = 2
        private const val MAX_SOLUTION_PLIES = PuzzleQualityEvaluator.MAX_QUIESCENCE_PLIES
        private const val FREE_ANALYSIS_DEPTH = 12
    }

    private val semanticAnalyzer = container.semanticMistakeAnalyzer

    private val _uiState = mutableStateOf(TrainingUiState())
    val uiState: State<TrainingUiState> = _uiState

    private var currentUser: User? = null
    private var onUserUpdatedCallback: ((User) -> Unit)? = null
    private var currentExercise: Exercise? = null
    private var targetSequence = mutableListOf<String>()
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
    private val analysisHistory = mutableListOf<Pair<String, Pair<Square, Square>?>>()

    private val sessionQueue = mutableListOf<Exercise>()
    private var lastMistakePattern: String? = null

    // ================================================================
    // ИНИЦИАЛИЗАЦИЯ И ПЕРЕКЛЮЧЕНИЕ РЕЖИМОВ
    // ================================================================

    fun init(
        user: User,
        initialMode: TrainingMode? = null,
        onUserUpdated: ((User) -> Unit)? = null
    ) {
        onUserUpdatedCallback = onUserUpdated
        val isNewUser = currentUser?.id != user.id
        currentUser = user

        val modeChangedByNavigation = initialMode != null && initialMode != _uiState.value.mode

        if (isNewUser) {
            sessionQueue.clear()
            lastMistakePattern = null
            val targetMode = initialMode ?: TrainingMode.All
            _uiState.value = TrainingUiState(mode = targetMode)
            initializeExercisesAndLoad(targetMode)
        } else if (modeChangedByNavigation && initialMode != null) {
            selectMode(initialMode)
        } else if (currentExercise == null && !_uiState.value.isLoading) {
            initializeExercisesAndLoad(_uiState.value.mode)
        }
    }

    /**
     * Переключение режима тренировки пользователем (Все / Мои ошибки / Слабые темы / По теме / Задача дня).
     */
    fun selectMode(mode: TrainingMode) {
        val userId = currentUser?.id ?: return
        if (_uiState.value.mode == mode && currentExercise != null && !_uiState.value.isLoading) {
            return
        }

        currentJob?.cancel()
        sessionQueue.clear()
        if (mode !is TrainingMode.All) {
            lastMistakePattern = null
        }

        _uiState.value = _uiState.value.copy(
            mode = mode,
            isLoading = true,
            isInputLocked = true,
            hasNoUserMistakes = false,
            similarExercisesQueued = 0,
            arrows = emptyList(),
            highlights = emptyList(),
            lastRatingDelta = null,
            feedbackMessage = "Подбираем задачу (${mode.displayName})..."
        )

        currentJob = viewModelScope.launch {
            if (mode is TrainingMode.MyMistakes) {
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

        _uiState.value = _uiState.value.copy(
            mode = mode,
            isLoading = true,
            isInputLocked = true,
            hasNoUserMistakes = false,
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

                loadNextExerciseInternal(userId, excludeId = -1L, mode = mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing training", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasActiveExercise = false,
                    feedbackMessage = "Сбой: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadCurrentStreak(userId: Long): Int {
        return runCatching {
            val attempts = container.exerciseRepository.getAttemptsByUserId(userId)
            var streak = 0
            for (attempt in attempts.asReversed()) {
                if (attempt.solved) streak++ else break
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
        val currentMode = _uiState.value.mode
        val excludeId = currentExercise?.id ?: -1L

        // После завершения «Задачи дня» кнопка «Следующая» плавно переводит в общий поток задач
        val effectiveMode = if (currentMode is TrainingMode.Daily &&
            (_uiState.value.isSolved || _uiState.value.isSolutionRevealed)
        ) {
            TrainingMode.All
        } else {
            currentMode
        }

        currentJob?.cancel()

        // 1. В общем режиме сначала отрабатываем очередь похожих задач после ошибки
        if (effectiveMode is TrainingMode.All && sessionQueue.isNotEmpty()) {
            val nextFromQueue = sessionQueue.removeFirst()
            currentJob = viewModelScope.launch {
                resetStateForNewExercise()
                setupExercise(
                    exercise = nextFromQueue,
                    mode = effectiveMode,
                    isAlreadySolvedDaily = false,
                    isReviewOfFailed = false,
                    reviewState = null,
                    dueReviewCount = _uiState.value.dueReviewCount
                )
            }
            return
        }

        // 2. Загружаем следующую задачу из БД
        _uiState.value = _uiState.value.copy(
            mode = effectiveMode,
            isLoading = true,
            isInputLocked = true,
            arrows = emptyList(),
            highlights = emptyList(),
            lastRatingDelta = null,
            feedbackMessage = null
        )

        resetStateForNewExercise()

        currentJob = viewModelScope.launch {
            loadNextExerciseInternal(userId, excludeId, effectiveMode)
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
                hintLevel = HintLevel.FULL_SOLUTION,
                highlights = emptyList(),
                currentMistakeAnalysis = null,
                showBetterMoveHint = false,
                lastRatingDelta = 0,
                sessionStats = stats.copy(
                    failed = if (shouldRecordFail) stats.failed + 1 else stats.failed,
                    currentStreak = 0
                ),
                feedbackMessage = "Решение: $sanNotation"
            )

            if (shouldRecordFail) {
                runCatching { saveAttempt(solved = false, solutionRevealed = true) }
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

            val scheduleNote = refreshCardAndDueCount()?.let { "\n${SpacedRepetitionScheduler.formatNextIntervalNote(it)}" }
                ?: "\n📅 Вернёмся к задаче через 10 мин"

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
                feedbackMessage = "💡 Решение: $fullSan$scheduleNote"
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
            val preferred = if (mode is TrainingMode.All) lastMistakePattern else null
            val result = container.getTrainingExerciseUseCase.execute(
                GetTrainingExerciseUseCase.Input(
                    userId = userId,
                    excludeExerciseId = excludeId,
                    preferredPattern = preferred,
                    mode = mode
                )
            )

            // Если кроме только что решённой задачи других в этом режиме нет — разрешаем повтор
            val finalResult = if (result is GetTrainingExerciseUseCase.Result.NoSuitableExercises && excludeId != -1L) {
                container.getTrainingExerciseUseCase.execute(
                    GetTrainingExerciseUseCase.Input(
                        userId = userId,
                        excludeExerciseId = -1L,
                        preferredPattern = null,
                        mode = mode
                    )
                )
            } else {
                result
            }

            when (finalResult) {
                is GetTrainingExerciseUseCase.Result.Success -> {
                    setupExercise(
                        exercise = finalResult.exercise,
                        mode = mode,
                        isAlreadySolvedDaily = finalResult.isAlreadySolvedDaily,
                        isReviewOfFailed = finalResult.isReviewOfFailed,
                        reviewState = finalResult.reviewState,
                        dueReviewCount = finalResult.dueReviewCount
                    )
                }
                is GetTrainingExerciseUseCase.Result.NoSuitableExercises -> {
                    currentExercise = null
                    val isMistakesMode = mode is TrainingMode.MyMistakes
                    val emptyMessage = if (isMistakesMode) {
                        "У вас пока нет задач из собственных партий. Загрузите PGN и запустите анализ — или выберите общий режим!"
                    } else {
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
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                hasActiveExercise = false,
                feedbackMessage = "Сбой: ${e.message}"
            )
        }
    }

    private fun resetStateForNewExercise() {
        analysisJob?.cancel()
        analysisHistory.clear()
        targetSequence.clear()
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
    }

    private suspend fun setupExercise(
        exercise: Exercise,
        mode: TrainingMode,
        isAlreadySolvedDaily: Boolean,
        isReviewOfFailed: Boolean,
        reviewState: ReviewCardState? = null,
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
            isAlreadySolvedDaily -> "✓ Задача дня уже решена сегодня — можно перерешать без изменения рейтинга"
            reviewState != null -> SpacedRepetitionScheduler.formatReviewBanner(reviewState)
            isReviewOfFailed -> "🔁 Повтор задачи, в которой ранее была ошибка"
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
            isReviewOfFailed = isReviewOfFailed,
            reviewBox = reviewState?.box,
            dueReviewCount = dueReviewCount,
            currentStepNumber = 1,
            totalPlayerSteps = totalPlayerSteps,
            similarExercisesQueued = sessionQueue.size,
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
                    val line = engine.getBestMoveWithLine(board.fen, 12)
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

        if (!state.board.isMoveLegal(move, true)) {
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
            feedbackMessage = null,
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            betterMoveShown = false,
            soundEvent = moveSound,
            soundEventId = soundCounter
        )

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            processUserMove(positionBeforePlayerMove, move)
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

    private suspend fun processUserMove(fenBefore: String, move: Move) {
        val userUci = ChessMoveUtils.moveToUci(move)
        recordedMoves.add(userUci)

        val expected = targetSequence.getOrNull(currentMoveIndex)?.lowercase()
        val isExactExpected = expected != null && userUci == expected

        if (!isExactExpected) {
            _uiState.value = _uiState.value.copy(
                isInputLocked = true,
                feedbackMessage = "Проверяем вариант..."
            )

            val quality = checkMoveQuality(fenBefore, move)
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
                            handleSuccess("✅ Отличный альтернативный ход!")
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

                handleSuccess("✅ Отличный альтернативный ход!")
                return
            } else {
                handleMistakeComprehensive(fenBefore, move)
                return
            }
        }

        currentMoveIndex++

        if (targetSequence.isNotEmpty() && currentMoveIndex < targetSequence.size && !_uiState.value.board.isMated) {
            delay(HUMAN_REACTION_DELAY_MS)

            val opponentMoveUci = targetSequence[currentMoveIndex]
            val applied = makeOpponentMove(opponentMoveUci)
            currentMoveIndex++

            if (!applied || currentMoveIndex >= targetSequence.size || _uiState.value.board.isMated) {
                handleSuccess("🏆 Комбинация завершена!")
            } else {
                val nextStep = (currentMoveIndex / 2) + 1
                _uiState.value = _uiState.value.copy(
                    currentStepNumber = nextStep,
                    feedbackMessage = "Верно! Продолжайте атаку."
                )
            }
        } else {
            val message = if (_uiState.value.board.isMated) {
                "🏆 Мат! Задача решена!"
            } else {
                "✅ Задача решена!"
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
            val line = engine.getBestMoveWithLine(fenAfterUserMove, 10) ?: return@withContext emptyList()
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
        attemptMistakeCount++
        attemptFailed = true
        val stats = _uiState.value.sessionStats

        var appliedDelta: Int? = null
        if (isFirstMistakeOnExercise && !failedAttemptSaved && !isCurrentDailyAlreadySolved) {
            failedAttemptSaved = true
            val exercise = currentExercise
            val user = currentUser
            if (exercise != null && user != null) {
                appliedDelta = TrainingRatingCalculator.calculateFailureDelta(user.rating, exercise)
            }
        }

        val updatedNet = stats.netRatingDelta + (appliedDelta ?: 0)
        soundCounter++
        _uiState.value = _uiState.value.copy(
            isInputLocked = true,
            isAnimatingPunishment = true,
            lastRatingDelta = appliedDelta,
            sessionStats = stats.copy(
                failed = stats.failed + 1,
                currentStreak = 0,
                netRatingDelta = updatedNet
            ),
            feedbackMessage = "Неверный ход — разбираем опровержение...",
            soundEvent = TrainingSoundEvent.ERROR,
            soundEventId = soundCounter
        )

        if (appliedDelta != null) {
            runCatching {
                saveAttempt(solved = false)
                updateRating(appliedDelta)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error saving failed attempt", e)
            }
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
                engine.getBestMoveWithLine(boardAfterPlayer.fen, 10)
            }

            val bestMoveLineObj = withContext(Dispatchers.IO) {
                engine.getBestMoveWithLine(fenBefore, 10)
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

            loadSimilarExercisesIntoQueue(analysis)
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
        val queueInfo = if (sessionQueue.isNotEmpty() && _uiState.value.mode is TrainingMode.All) {
            "\n📚 +${sessionQueue.size} похожих задач для закрепления"
        } else {
            ""
        }

        val cleanExplanation = cleanEvaluationText(analysis.explanation)

        _uiState.value = _uiState.value.copy(
            feedbackMessage = cleanExplanation + queueInfo,
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
                            feedbackMessage = "Неверный ход. Посмотрите опровержение соперника.",
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
                    feedbackMessage = "❌ Неверный ход — найдите более сильное решение."
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fallback mistake handling failed", e)
            _uiState.value = _uiState.value.copy(feedbackMessage = "❌ Неверный ход")
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
        val prompt = if (keptExplanation != null) {
            "$keptExplanation\nПопробуйте ещё раз!"
        } else {
            "Попробуйте ещё раз!"
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

    private suspend fun loadSimilarExercisesIntoQueue(analysis: MistakeAnalysis) {
        if (_uiState.value.mode !is TrainingMode.All) return
        val userId = currentUser?.id ?: return

        val pattern = analysis.tacticalMotif?.let { TacticalPattern.fromMotif(it) }
            ?: currentExercise?.tacticalPattern
            ?: return
        val patternName = pattern.name
        lastMistakePattern = patternName

        try {
            val currentId = currentExercise?.id ?: -1L
            val currentDifficulty = currentExercise?.getEffectiveDifficulty() ?: 50
            val alreadyQueuedIds = sessionQueue.mapNotNull { it.id }.toSet()

            val remainingSlots = (4 - sessionQueue.size).coerceAtLeast(0)
            if (remainingSlots == 0) return

            val similarExercises = container.exerciseRepository.findForMistakeRepetition(
                userId = userId,
                pattern = patternName,
                difficulty = currentDifficulty,
                excludeId = currentId,
                limit = minOf(SIMILAR_EXERCISES_COUNT, remainingSlots)
            ).filter { it.id !in alreadyQueuedIds }

            if (similarExercises.isNotEmpty()) {
                sessionQueue.addAll(similarExercises)
                _uiState.value = _uiState.value.copy(
                    similarExercisesQueued = sessionQueue.size
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading similar exercises", e)
        }
    }

    private suspend fun checkMoveQuality(fen: String, userMove: Move): TrainingMoveQuality {
        return withContext(Dispatchers.IO) {
            try {
                val userBoard = Board()
                userBoard.loadFromFen(fen)
                if (!userBoard.doMove(userMove)) return@withContext TrainingMoveQuality.MISTAKE

                if (userBoard.isMated) {
                    return@withContext TrainingMoveQuality.BEST
                }

                val engine = container.chessEngine
                if (!engine.init()) return@withContext TrainingMoveQuality.MISTAKE

                val bestLine = engine.getBestMoveWithLine(fen, 10)
                    ?: return@withContext TrainingMoveQuality.MISTAKE

                val userUci = ChessMoveUtils.moveToUci(move = userMove)
                if (userUci.equals(bestLine.bestMove, ignoreCase = true)) {
                    return@withContext TrainingMoveQuality.BEST
                }

                val opponentEvalAfterUser = engine.evaluate(userBoard.fen, 10)
                val playerEvalAfterUser = -opponentEvalAfterUser
                val playerEvalBest = bestLine.score

                if (ChessEngine.isMateScore(playerEvalAfterUser) && playerEvalAfterUser > 0) {
                    return@withContext if (playerEvalAfterUser >= playerEvalBest) {
                        TrainingMoveQuality.BEST
                    } else {
                        TrainingMoveQuality.EXCELLENT
                    }
                }

                if (ChessEngine.isMateScore(playerEvalBest) && playerEvalBest > 0) {
                    return@withContext TrainingMoveQuality.MISTAKE
                }

                val cpLoss = playerEvalBest - playerEvalAfterUser
                val isDefensive = currentExercise?.let { ExerciseMetadata.isDefensiveGoal(it) } == true
                when {
                    cpLoss <= 15 -> TrainingMoveQuality.BEST
                    cpLoss <= 45 -> TrainingMoveQuality.EXCELLENT
                    // В защитной задаче любой ход, надёжно удерживающий равновесие без тактических потерь, корректен
                    isDefensive && playerEvalAfterUser >= -140 && cpLoss <= 60 ->
                        TrainingMoveQuality.EXCELLENT
                    // При реализации уже подавляющего перевеса (+4 пешки) допускаем равноценный выигрывающий путь
                    playerEvalAfterUser >= 400 && cpLoss <= 65 ->
                        TrainingMoveQuality.EXCELLENT
                    else -> TrainingMoveQuality.MISTAKE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error checking move quality", e)
                TrainingMoveQuality.MISTAKE
            }
        }
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

    private suspend fun saveAttempt(
        solved: Boolean,
        solutionRevealed: Boolean = false,
        wasImpulsive: Boolean = false
    ) {
        val userId = currentUser?.id ?: return
        val exerciseId = currentExercise?.id ?: return
        val now = System.currentTimeMillis()
        val timeSpent = ((now - startTime) / 1000).toInt().coerceAtLeast(0)

        val taggedMoves = AttemptMetadata.buildTaggedMoves(
            rawUciMoves = recordedMoves,
            hintUsage = maxHintUsed,
            retryCount = if (solved) attemptMistakeCount else attemptMistakeCount.coerceAtLeast(0),
            firstMoveThinkMs = firstMoveThinkTimeMs,
            solutionRevealed = solutionRevealed,
            wasImpulsive = wasImpulsive
        )

        val attempt = ExerciseAttempt(
            id = null,
            userId = userId,
            exerciseId = exerciseId,
            solved = solved,
            timeSpentSeconds = timeSpent,
            movesMade = taggedMoves,
            attemptedAt = now
        )
        container.exerciseRepository.saveAttempt(attempt)
    }

    /**
     * Пересчитывает состояние SM-2/Лейтнера для текущей задачи и счётчик подошедших повторов.
     */
    private suspend fun refreshCardAndDueCount(): ReviewCardState? {
        val userId = currentUser?.id ?: return null
        val exercise = currentExercise
        val exerciseId = exercise?.id
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
                    exercise
                )
            }
            _uiState.value = _uiState.value.copy(
                dueReviewCount = dueCount,
                reviewBox = currentCard?.box ?: _uiState.value.reviewBox
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
                    feedbackMessage = "Подсказка: обратите внимание на выделенную фигуру"
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
                solutionRevealed = false
            )
        } else {
            0
        }

        // Чистое решение (без раскрытия полного ответа и без ошибок) увеличивает серию
        val stats = _uiState.value.sessionStats
        val countsForStreak = !attemptFailed && !usedFullHint && !isCurrentDailyAlreadySolved
        val newStreak = if (countsForStreak) stats.currentStreak + 1 else stats.currentStreak
        val newBestStreak = maxOf(stats.bestStreak, newStreak)

        val ratingInfo = when {
            isCurrentDailyAlreadySolved -> ""
            ratingDelta > 0 -> " (+$ratingDelta Эло)"
            usedFullHint -> " (с подсказкой хода: +0 Эло)"
            else -> ""
        }
        val queueInfo = if (sessionQueue.isNotEmpty() && _uiState.value.mode is TrainingMode.All) {
            "\n📚 Ещё ${sessionQueue.size} похожих задач для закрепления"
        } else {
            ""
        }

        val postReview = buildPostSolveReviewState()

        soundCounter++
        _uiState.value = _uiState.value.copy(
            isSolved = true,
            isInputLocked = true,
            isFreeAnalysisMode = false,
            lastRatingDelta = if (isCurrentDailyAlreadySolved) null else ratingDelta,
            feedbackMessage = msg + ratingInfo + queueInfo,
            reviewSteps = postReview.first,
            reviewStepIndex = (postReview.first.size - 1).coerceAtLeast(0),
            solutionSanLine = postReview.second.ifBlank { null },
            postSolveExplanation = postReview.third,
            sessionStats = stats.copy(
                solved = stats.solved + 1,
                currentStreak = newStreak,
                bestStreak = newBestStreak,
                netRatingDelta = stats.netRatingDelta + ratingDelta
            ),
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            similarExercisesQueued = sessionQueue.size,
            soundEvent = TrainingSoundEvent.SOLVED,
            soundEventId = soundCounter
        )

        // Самостоятельное решение (без готовой стрелки) сохраняется как solved = true,
        // а подсказка готовым ходом — как нерешённая попытка (коробка 0, повтор через 10 мин)
        val markAsSolvedInDb = !usedFullHint && !isCurrentDailyAlreadySolved
        val markFullHintForReview = usedFullHint && !isCurrentDailyAlreadySolved && !failedAttemptSaved
        val baseFeedback = msg + ratingInfo + queueInfo

        viewModelScope.launch {
            try {
                if (ratingDelta != 0) {
                    updateRating(ratingDelta)
                }
                if (markAsSolvedInDb) {
                    saveAttempt(solved = true)
                    // В глобальную успешность задачи засчитываем только чистое решение с 1-й попытки без подсказок
                    if (!attemptFailed && maxHintUsed == HintUsage.NONE) {
                        exercise?.id?.let { id ->
                            container.exerciseRepository.incrementTimesSolved(id)
                        }
                    }
                } else if (markFullHintForReview) {
                    failedAttemptSaved = true
                    saveAttempt(solved = false)
                }

                if (!isCurrentDailyAlreadySolved) {
                    val updatedCard = refreshCardAndDueCount()
                    if (updatedCard != null && _uiState.value.isSolved && currentExercise?.id == exercise?.id) {
                        val scheduleNote = SpacedRepetitionScheduler.formatNextIntervalNote(updatedCard)
                        _uiState.value = _uiState.value.copy(
                            feedbackMessage = "$baseFeedback\n$scheduleNote"
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
        if (!board.isMoveLegal(move, true)) return

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
                    engine.getBestMoveWithLine(fen, FREE_ANALYSIS_DEPTH)
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
        sessionQueue.clear()
        lastMistakePattern = null
        _uiState.value = _uiState.value.copy(
            similarExercisesQueued = 0
        )
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        generationJob?.cancel()
        analysisJob?.cancel()
        onUserUpdatedCallback = null
    }
}
