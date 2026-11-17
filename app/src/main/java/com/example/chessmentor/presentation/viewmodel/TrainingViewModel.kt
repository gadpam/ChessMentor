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
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.usecase.GenerateExercisesFromMistakesUseCase
import com.example.chessmentor.domain.usecase.GetTrainingExerciseUseCase
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.BoardArrow
import com.github.bhlangonijr.chesslib.Board
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
 * Единое состояние экрана тренировки
 */
data class TrainingUiState(
    val board: Board = Board(),
    val arrows: List<BoardArrow> = emptyList(),
    val highlights: List<String> = emptyList(),
    val feedbackMessage: String? = null,
    val playerSide: Side = Side.WHITE,
    val isInputLocked: Boolean = false,
    val isSolved: Boolean = false,
    val isLoading: Boolean = false,
    val hintLevel: HintLevel = HintLevel.NONE,
    val sessionStats: SessionStats = SessionStats(),

    // Ошибки и анимации
    val currentMistakeAnalysis: MistakeAnalysis? = null,
    val showBetterMoveHint: Boolean = false,
    val betterMoveShown: Boolean = false,
    val punishmentAnimationStep: Int = 0,
    val isAnimatingPunishment: Boolean = false,
    val isErrorShake: Boolean = false,

    // Кластеризация и очередь
    val currentPattern: String? = null,
    val similarExercisesQueued: Int = 0
)

data class SessionStats(
    val solved: Int = 0,
    val failed: Int = 0
)

enum class HintLevel { NONE, PIECE_HIGHLIGHT, FULL_SOLUTION }
enum class TrainingMoveQuality { BEST, EXCELLENT, MISTAKE }

class TrainingViewModel(
    private val container: AppContainer
) : ViewModel() {

    companion object {
        private const val TAG = "TrainingVM"
        private const val HUMAN_REACTION_DELAY_MS = 700L
        private const val ANIMATION_DELAY_MS = 600L
        private const val PUNISHMENT_DISPLAY_MS = 2500L
        private const val SIMILAR_EXERCISES_COUNT = 2
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
    private var attemptFailed = false
    private var failedAttemptSaved = false
    private var currentJob: Job? = null
    private var generationJob: Job? = null
    private var positionBeforePlayerMove: String = ""

    private val sessionQueue = mutableListOf<Exercise>()
    private var lastMistakePattern: String? = null

    // ================================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ================================================================

    fun init(user: User, onUserUpdated: ((User) -> Unit)? = null) {
        onUserUpdatedCallback = onUserUpdated
        val isNewUser = currentUser?.id != user.id
        currentUser = user

        if (isNewUser || (currentExercise == null && !_uiState.value.isLoading)) {
            sessionQueue.clear()
            lastMistakePattern = null
            initializeExercisesAndLoad()
        }
    }

    /**
     * Если у пользователя ещё нет готовых задач, сначала дожидаемся генерации из ошибок,
     * а уже затем загружаем первую задачу (устраняет гонку инициализации).
     */
    private fun initializeExercisesAndLoad() {
        val userId = currentUser?.id ?: return
        currentJob?.cancel()

        _uiState.value = TrainingUiState(
            isLoading = true,
            feedbackMessage = "Подготавливаем задачи...",
            sessionStats = _uiState.value.sessionStats
        )

        currentJob = viewModelScope.launch {
            try {
                val existingExercises = container.exerciseRepository.findByUserId(userId)
                if (existingExercises.isEmpty()) {
                    // Генерируем первую порцию синхронно перед показом
                    runCatching {
                        container.generateExercisesFromMistakesUseCase.execute(
                            GenerateExercisesFromMistakesUseCase.Input(userId, maxExercises = 15)
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Error generating initial exercises", e)
                    }
                    // Остальные догенерируем в фоне
                    scheduleBackgroundGeneration(userId)
                } else {
                    scheduleBackgroundGeneration(userId)
                }

                loadNextExerciseInternal(userId, excludeId = -1L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing training", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    feedbackMessage = "Сбой: ${e.message}"
                )
            }
        }
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
    // ЗАГРУЗКА ЗАДАЧИ
    // ================================================================

    fun loadNextExercise() {
        val userId = currentUser?.id ?: return
        val excludeId = currentExercise?.id ?: -1L

        currentJob?.cancel()

        // 1. Проверяем очередь похожих задач
        if (sessionQueue.isNotEmpty()) {
            val nextFromQueue = sessionQueue.removeFirst()
            currentJob = viewModelScope.launch {
                resetStateForNewExercise()
                setupExercise(nextFromQueue)
                _uiState.value = _uiState.value.copy(
                    similarExercisesQueued = sessionQueue.size,
                    currentPattern = nextFromQueue.tacticalPattern.name
                )
            }
            return
        }

        // 2. Загружаем из БД
        _uiState.value = TrainingUiState(
            isLoading = true,
            sessionStats = _uiState.value.sessionStats
        )

        resetStateForNewExercise()

        currentJob = viewModelScope.launch {
            loadNextExerciseInternal(userId, excludeId)
        }
    }

    private suspend fun loadNextExerciseInternal(userId: Long, excludeId: Long) {
        resetStateForNewExercise()
        try {
            val result = container.getTrainingExerciseUseCase.execute(
                GetTrainingExerciseUseCase.Input(
                    userId = userId,
                    excludeExerciseId = excludeId,
                    preferredPattern = lastMistakePattern
                )
            )

            // Если нет других задач, кроме текущей — разрешаем повторить текущую
            val finalResult = if (result is GetTrainingExerciseUseCase.Result.NoSuitableExercises && excludeId != -1L) {
                container.getTrainingExerciseUseCase.execute(
                    GetTrainingExerciseUseCase.Input(
                        userId = userId,
                        excludeExerciseId = -1L,
                        preferredPattern = null
                    )
                )
            } else {
                result
            }

            when (finalResult) {
                is GetTrainingExerciseUseCase.Result.Success -> {
                    setupExercise(finalResult.exercise)
                    _uiState.value = _uiState.value.copy(
                        currentPattern = finalResult.exercise.tacticalPattern.name,
                        similarExercisesQueued = sessionQueue.size
                    )
                }
                is GetTrainingExerciseUseCase.Result.NoSuitableExercises -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isInputLocked = true,
                        feedbackMessage = "Нет новых задач. Загрузите и проанализируйте партии!"
                    )
                }
                is GetTrainingExerciseUseCase.Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isInputLocked = true,
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
                feedbackMessage = "Сбой: ${e.message}"
            )
        }
    }

    private fun resetStateForNewExercise() {
        targetSequence.clear()
        recordedMoves.clear()
        currentMoveIndex = 0
        attemptFailed = false
        failedAttemptSaved = false
        startTime = System.currentTimeMillis()
        positionBeforePlayerMove = ""
    }

    private suspend fun setupExercise(exercise: Exercise) {
        currentExercise = exercise
        val newBoard = Board()
        newBoard.loadFromFen(exercise.fenPosition)
        positionBeforePlayerMove = exercise.fenPosition

        val normalizedStored = normalizeSequenceToUci(exercise.fenPosition, exercise.solutionMoves)
        if (normalizedStored.size < 3) {
            extendSolutionWithEngine(normalizedStored, newBoard)
        } else {
            targetSequence.addAll(normalizedStored)
        }

        _uiState.value = _uiState.value.copy(
            board = newBoard,
            playerSide = newBoard.sideToMove,
            arrows = emptyList(),
            highlights = emptyList(),
            hintLevel = HintLevel.NONE,
            isInputLocked = false,
            isSolved = false,
            isAnimatingPunishment = false,
            isLoading = false,
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            betterMoveShown = false,
            feedbackMessage = null
        )

        exercise.id?.let { id ->
            runCatching { container.exerciseRepository.incrementTimesShown(id) }
        }
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

    private suspend fun extendSolutionWithEngine(fallbackUci: List<String>, board: Board) {
        withContext(Dispatchers.IO) {
            try {
                val engine = container.chessEngine
                if (engine.init()) {
                    val line = engine.getBestMoveWithLine(board.fen, 12)
                    val pv = line?.principalVariation
                        ?.map { it.trim().lowercase() }
                        ?.filter { ChessMoveUtils.isUciMove(it) }
                        ?.take(5)
                        .orEmpty()

                    when {
                        pv.isNotEmpty() -> targetSequence.addAll(pv)
                        !line?.bestMove.isNullOrBlank() -> targetSequence.add(line!!.bestMove.lowercase())
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
        if (state.isInputLocked || state.isSolved || state.isAnimatingPunishment || state.isLoading) return
        if (state.board.sideToMove != state.playerSide) return

        // Проверка легальности хода (включая превращение пешки и рокировку)
        if (!state.board.isMoveLegal(move, true)) {
            triggerShakeEffect()
            _uiState.value = state.copy(feedbackMessage = "Невозможный ход")
            viewModelScope.launch {
                delay(1000)
                if (_uiState.value.feedbackMessage == "Невозможный ход") {
                    _uiState.value = _uiState.value.copy(feedbackMessage = null)
                }
            }
            return
        }

        positionBeforePlayerMove = state.board.fen

        val newBoard = Board()
        newBoard.loadFromFen(positionBeforePlayerMove)
        newBoard.doMove(move)

        _uiState.value = state.copy(
            board = newBoard,
            isInputLocked = true,
            arrows = emptyList(),
            highlights = emptyList(),
            hintLevel = HintLevel.NONE,
            feedbackMessage = null,
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            betterMoveShown = false
        )

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            processUserMove(positionBeforePlayerMove, move)
        }
    }

    private fun triggerShakeEffect() {
        _uiState.value = _uiState.value.copy(isErrorShake = true)
        viewModelScope.launch {
            delay(300)
            _uiState.value = _uiState.value.copy(isErrorShake = false)
        }
    }

    private suspend fun processUserMove(fenBefore: String, move: Move) {
        // Полный UCI с учётом превращения пешки (например, e7e8q != e7e8n)
        val userUci = ChessMoveUtils.moveToUci(move)
        recordedMoves.add(userUci)

        val expected = targetSequence.getOrNull(currentMoveIndex)?.lowercase()
        val isExactExpected = expected != null && userUci == expected

        if (!isExactExpected) {
            _uiState.value = _uiState.value.copy(
                isInputLocked = true,
                feedbackMessage = "Анализирую..."
            )

            val quality = checkMoveQuality(fenBefore, move)
            if (quality == TrainingMoveQuality.BEST || quality == TrainingMoveQuality.EXCELLENT) {
                targetSequence.clear()
                handleSuccess("✅ Отличный альтернативный ход!")
                return
            } else {
                handleMistakeComprehensive(fenBefore, move)
                return
            }
        }

        currentMoveIndex++

        if (targetSequence.isNotEmpty() && currentMoveIndex < targetSequence.size) {
            delay(HUMAN_REACTION_DELAY_MS)

            val opponentMoveUci = targetSequence[currentMoveIndex]
            val applied = makeOpponentMove(opponentMoveUci)
            currentMoveIndex++

            if (!applied || currentMoveIndex >= targetSequence.size || _uiState.value.board.isMated) {
                handleSuccess("🏆 Отлично!")
            }
        } else {
            handleSuccess("✅ Задача решена!")
        }
    }

    private fun makeOpponentMove(uci: String): Boolean {
        val board = Board()
        board.loadFromFen(_uiState.value.board.fen)
        val ok = ChessMoveUtils.applyUciMove(board, uci)
        if (ok) {
            recordedMoves.add(uci)
        }

        _uiState.value = _uiState.value.copy(
            board = board,
            isInputLocked = false
        )
        return ok
    }

    // ================================================================
    // АНАЛИЗ ОШИБКИ
    // ================================================================

    private suspend fun handleMistakeComprehensive(fenBefore: String, userMove: Move) {
        val isFirstMistakeOnExercise = !attemptFailed
        attemptFailed = true
        val stats = _uiState.value.sessionStats

        _uiState.value = _uiState.value.copy(
            isInputLocked = true,
            isAnimatingPunishment = true,
            sessionStats = stats.copy(failed = stats.failed + 1),
            feedbackMessage = "Анализ ошибки..."
        )

        if (isFirstMistakeOnExercise && !failedAttemptSaved) {
            failedAttemptSaved = true
            runCatching {
                saveAttempt(solved = false)
                updateRating(-5)
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
                Log.w(TAG, "Engine returned null lines")
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
                Log.e(TAG, "Semantic analysis crash", e)
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
        val queueInfo = if (sessionQueue.isNotEmpty()) {
            "\n\n📚 +${sessionQueue.size} похожих задач добавлено"
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

    /**
     * Анимация цепочки наказания (PV)
     */
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

            if (!ChessMoveUtils.applyUciMove(currentBoard, moveUci)) break

            _uiState.value = _uiState.value.copy(
                board = Board().apply { loadFromFen(currentBoard.fen) },
                arrows = emptyList()
            )

            delay(ANIMATION_DELAY_MS / 2)
        }
    }

    private suspend fun fallbackMistakeHandling(fenBefore: String, userMove: Move) {
        Log.w(TAG, "Using fallback mistake handling")

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
                            feedbackMessage = "Неточный ход. Посмотрите ответ соперника.",
                            currentMistakeAnalysis = null
                        )

                        delay(ANIMATION_DELAY_MS)
                        ChessMoveUtils.applyUciMove(boardAfterPlayer, punishmentUci)
                        _uiState.value = _uiState.value.copy(board = boardAfterPlayer)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fallback also failed", e)
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
        _uiState.value = prev.copy(
            board = board,
            isInputLocked = false,
            isAnimatingPunishment = false,
            arrows = emptyList(),
            punishmentAnimationStep = 0,
            currentMistakeAnalysis = if (keepAnalysis) prev.currentMistakeAnalysis else null,
            showBetterMoveHint = if (keepAnalysis) prev.showBetterMoveHint else false,
            feedbackMessage = "Попробуйте ещё раз!"
        )
    }

    // ================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ================================================================

    private suspend fun loadSimilarExercisesIntoQueue(analysis: MistakeAnalysis) {
        val motif = analysis.tacticalMotif ?: return
        val pattern = TacticalPattern.fromMotif(motif)
        val patternName = pattern.name

        lastMistakePattern = patternName

        try {
            val currentId = currentExercise?.id ?: -1L
            val currentDifficulty = currentExercise?.difficultyRating ?: 50
            val alreadyQueuedIds = sessionQueue.mapNotNull { it.id }.toSet()

            val similarExercises = container.exerciseRepository.findSimilarByPattern(
                pattern = patternName,
                excludeId = currentId,
                minDifficulty = (currentDifficulty - 20).coerceAtLeast(0),
                maxDifficulty = (currentDifficulty + 20).coerceAtMost(100),
                limit = SIMILAR_EXERCISES_COUNT
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

    /**
     * Проверяет альтернативный ход игрока с учётом знака оценки и форсированных матов:
     * ход лучше или равный линии движка засчитывается как BEST, а не отвергается из-за abs().
     */
    private suspend fun checkMoveQuality(fen: String, userMove: Move): TrainingMoveQuality {
        return withContext(Dispatchers.IO) {
            try {
                val userBoard = Board()
                userBoard.loadFromFen(fen)
                if (!userBoard.doMove(userMove)) return@withContext TrainingMoveQuality.MISTAKE

                // Мат в 1 ход всегда является лучшим решением
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

                // Оценка позиции ПОСЛЕ хода игрока со стороны соперника -> инвертируем для игрока
                val opponentEvalAfterUser = engine.evaluate(userBoard.fen, 10)
                val playerEvalAfterUser = -opponentEvalAfterUser
                val playerEvalBest = bestLine.score

                // Если игрок всё равно ставит форсированный мат — засчитываем решение
                if (ChessEngine.isMateScore(playerEvalAfterUser) && playerEvalAfterUser > 0) {
                    return@withContext if (playerEvalAfterUser >= playerEvalBest) {
                        TrainingMoveQuality.BEST
                    } else {
                        TrainingMoveQuality.EXCELLENT
                    }
                }

                // Если был форсированный мат, но ход игрока его упускает — ошибка
                if (ChessEngine.isMateScore(playerEvalBest) && playerEvalBest > 0) {
                    return@withContext TrainingMoveQuality.MISTAKE
                }

                // Знаковая потеря: отрицательное значение означает, что игрок нашёл ход сильнее!
                val cpLoss = playerEvalBest - playerEvalAfterUser

                when {
                    cpLoss <= 15 -> TrainingMoveQuality.BEST
                    cpLoss <= 40 -> TrainingMoveQuality.EXCELLENT
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

    private fun createArrowFromUci(uci: String, color: Color): BoardArrow? {
        val normalized = uci.trim()
        if (normalized.length < 4) return null
        return try {
            val from = Square.fromValue(normalized.substring(0, 2).uppercase())
            val to = Square.fromValue(normalized.substring(2, 4).uppercase())
            if (from == Square.NONE || to == Square.NONE) null else BoardArrow(from, to, color)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun updateRating(change: Int) {
        val user = currentUser ?: return
        if (user.id == null) return
        val newRating = (user.rating + change).coerceIn(100, 3500)
        // withNewRating автоматически пересчитывает skillLevel
        val updated = user.withNewRating(newRating)
        currentUser = updated
        container.userRepository.update(updated)
        onUserUpdatedCallback?.invoke(updated)
    }

    private suspend fun saveAttempt(solved: Boolean) {
        val userId = currentUser?.id ?: return
        val exerciseId = currentExercise?.id ?: return
        val timeSpent = ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceAtLeast(0)

        val attempt = ExerciseAttempt(
            id = null,
            userId = userId,
            exerciseId = exerciseId,
            solved = solved,
            timeSpentSeconds = timeSpent,
            movesMade = recordedMoves.toList(),
            attemptedAt = System.currentTimeMillis()
        )
        container.exerciseRepository.saveAttempt(attempt)
    }

    // ================================================================
    // UI HANDLERS
    // ================================================================

    fun onShowBetterMoveClicked() {
        val state = _uiState.value
        val analysis = state.currentMistakeAnalysis ?: return
        val betterMove = analysis.betterMoveInfo ?: return

        if (state.betterMoveShown) return

        val arrow = createArrowFromUci(betterMove.moveUci, Color(0xAA4CAF50)) ?: return

        _uiState.value = state.copy(
            arrows = listOf(arrow),
            betterMoveShown = true,
            feedbackMessage = "Лучше было: ${betterMove.explanation}"
        )
    }

    fun onHintClicked() {
        val state = _uiState.value
        if (state.isSolved || state.isInputLocked || state.isAnimatingPunishment || state.isLoading) return

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
                _uiState.value = state.copy(
                    hintLevel = nextLevel,
                    highlights = listOf(fromSq),
                    arrows = emptyList(),
                    feedbackMessage = "Посмотрите на эту фигуру"
                )
            }
            HintLevel.FULL_SOLUTION -> {
                val arrow = createArrowFromUci(correctMoveUci, Color(0xAA4CAF50)) ?: return
                val san = ChessMoveUtils.uciToSan(correctMoveUci, state.board.fen)
                _uiState.value = state.copy(
                    hintLevel = nextLevel,
                    highlights = emptyList(),
                    arrows = listOf(arrow),
                    feedbackMessage = "Лучший ход: $san"
                )
            }
            HintLevel.NONE -> {}
        }
    }

    private fun handleSuccess(msg: String) {
        val stats = _uiState.value.sessionStats
        val queueInfo = if (sessionQueue.isNotEmpty()) {
            "\n📚 Ещё ${sessionQueue.size} похожих задач"
        } else {
            ""
        }

        _uiState.value = _uiState.value.copy(
            isSolved = true,
            isInputLocked = true,
            feedbackMessage = msg + queueInfo,
            sessionStats = stats.copy(solved = stats.solved + 1),
            currentMistakeAnalysis = null,
            showBetterMoveHint = false,
            similarExercisesQueued = sessionQueue.size
        )

        val ratingChange = if (attemptFailed) 10 else 15

        viewModelScope.launch {
            try {
                updateRating(ratingChange)
                saveAttempt(solved = true)
                currentExercise?.id?.let { id ->
                    container.exerciseRepository.incrementTimesSolved(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error saving training success", e)
            }
        }
    }

    fun clearSessionQueue() {
        sessionQueue.clear()
        lastMistakePattern = null
        _uiState.value = _uiState.value.copy(
            similarExercisesQueued = 0,
            currentPattern = null
        )
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        generationJob?.cancel()
        onUserUpdatedCallback = null
    }
}
