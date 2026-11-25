package com.example.chessmentor.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.engine.EngineAnalysisProfile
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.SoundManager
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние интерактивного режима «Исправить ошибку» на доске разбора партии.
 */
data class RetryMistakeUiState(
    val targetMoveIndex: Int,
    val moveNumber: Int,
    val moverColor: ChessColor,
    val isPlayerMistake: Boolean,
    val fenBefore: String,
    val playedSan: String,
    val expectedBestRaw: String,
    val expectedBestSan: String,
    val hintLevel: Int = 0,
    val hintFromSquare: Square? = null,
    val hintArrowUci: String? = null,
    val isCheckingEngine: Boolean = false,
    val isSolved: Boolean = false,
    val attemptsCount: Int = 0,
    val feedback: String,
    val answerRevealedBeforeRetry: Boolean = false,
    val isSavingRepetition: Boolean = false,
    val savedRepetitionMessage: String? = null,
    val savedExerciseId: Long? = null,
    val playedMistakeNotation: String? = if (isPlayerMistake) playedSan else null,
    val skillProfile: GameReviewHelper.RetrySkillProfile? = null
)

/**
 * Состояние живого анализа позиции движком Stockfish на экране разбора.
 */
data class EngineLineUiState(
    val isLoading: Boolean = false,
    val analyzedFen: String = "",
    /** `true`, если показана лучшая линия ДО сыгранной ошибки (объяснение лучшего хода). */
    val isAlternativeBeforeMove: Boolean = false,
    val evalWhiteCp: Int? = null,
    val bestMoveUci: String? = null,
    val pvSteps: List<GameReviewHelper.VariationStep> = emptyList(),
    val pvFormatted: String = "",
    val errorMessage: String? = null
)

class BoardViewModel : ViewModel() {
    companion object {
        private const val TAG = "BoardViewModel"
        private const val REVIEW_ENGINE_DEPTH = EngineAnalysisProfile.INTERACTIVE_ANALYSIS_DEPTH
        private const val RETRY_EQUIV_CHECK_DEPTH = EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH
        private const val MAX_PV_PLIES = EngineAnalysisProfile.MAX_PV_PLIES
    }

    val board = mutableStateOf(Board())
    val moves = mutableStateListOf<Move>()
    val moveNotations = mutableStateListOf<String>()
    val sanMoves = mutableStateListOf<String>()
    val currentMoveIndex = mutableIntStateOf(-1)
    val highlightedSquares = mutableStateOf<Set<Square>>(emptySet())
    val lastMove = mutableStateOf<Pair<Square, Square>?>(null)

    val evaluations = mutableStateListOf<Int>()
    val analyzedMoves = mutableStateListOf<AnalyzedMove>()
    val mistakes = mutableStateListOf<Mistake>()
    val clocksByPly = mutableStateMapOf<Int, Int>()

    /** Ориентация доски (по умолчанию со стороны игрока, можно перевернуть в 1 тап). */
    val isBoardFlipped = mutableStateOf(false)

    /** Интерактивный режим «Исправить ошибку» прямо на доске разбора. */
    val retryState = mutableStateOf<RetryMistakeUiState?>(null)

    /** Свободная аналитическая ветка поверх позиции партии (`variationAnchorIndex`). */
    val variationSteps = mutableStateListOf<GameReviewHelper.VariationStep>()
    val variationAnchorIndex = mutableIntStateOf(-1)

    /** Линия движка Stockfish по запросу / в живом режиме. */
    val isLiveEngineEnabled = mutableStateOf(false)
    val engineLineState = mutableStateOf<EngineLineUiState?>(null)

    private var playerColor: ChessColor = ChessColor.WHITE
    private var currentGame: Game? = null
    private var startFen: String? = null
    private var initialFen: String = Board().fen
    private var startMoveNumber: Int = 1
    private var firstMoveIsWhite: Boolean = true

    // Предвычисленные состояния для мгновенной O(1) навигации без пересборки с 1-го хода
    private val fensAfterPly = ArrayList<String>()
    private val lastMovesByPly = ArrayList<Pair<Square, Square>>()

    private var engineJob: Job? = null
    private var retryCheckJob: Job? = null
    private var cachedEngine: ChessEngine? = null
    private var pendingJumpGameId: Long? = null
    private var pendingJumpMoveIndex: Int? = null

    fun scheduleJumpToMove(gameId: Long, moveIndex: Int) {
        pendingJumpGameId = gameId
        pendingJumpMoveIndex = moveIndex
    }

    var soundManager: SoundManager? = null

    fun getLoadedGameId(): Long? = currentGame?.id

    fun getStartMoveNumber(): Int = startMoveNumber

    fun isFirstMoveWhite(): Boolean = firstMoveIsWhite

    fun isInVariation(): Boolean = variationSteps.isNotEmpty()

    fun isInRetryMode(): Boolean = retryState.value != null

    fun loadGame(
        game: Game,
        gameMistakes: List<Mistake>,
        gameAnalyzedMoves: List<AnalyzedMove> = emptyList(),
        gameEvaluations: List<Int> = emptyList(),
        preserveMoveIndex: Boolean = false
    ) {
        try {
            val isSameGame = currentGame?.id != null && currentGame?.id == game.id
            val previousIndex = if (preserveMoveIndex && isSameGame) {
                currentMoveIndex.intValue
            } else {
                -1
            }
            val previousFlip = if (isSameGame) {
                isBoardFlipped.value
            } else {
                game.playerColor == ChessColor.BLACK
            }

            currentGame = game
            playerColor = game.playerColor
            clearState()
            isBoardFlipped.value = previousFlip

            mistakes.addAll(gameMistakes)
            analyzedMoves.addAll(gameAnalyzedMoves)
            clocksByPly.putAll(PgnParser.extractClocksByPly(game.pgnData))

            val normalized = runCatching { ChessMoveUtils.normalizePgn(game.pgnData) }.getOrNull()
            startFen = normalized?.startFen

            val initialBoard = createInitialBoard()
            initialFen = initialBoard.fen
            startMoveNumber = initialBoard.moveCounter.coerceAtLeast(1)
            firstMoveIsWhite = initialBoard.sideToMove == Side.WHITE

            val halfMoves = if (normalized != null) {
                ChessMoveUtils.parseMovesInMemory(normalized)
            } else {
                emptyList()
            }

            if (halfMoves.isEmpty()) {
                board.value = initialBoard
                loadEvaluations(gameEvaluations, game)
                return
            }

            loadMoves(halfMoves)
            loadEvaluations(gameEvaluations, game)
            val scheduledJump = if (pendingJumpGameId != null && pendingJumpGameId == game.id) {
                pendingJumpMoveIndex?.also {
                    pendingJumpGameId = null
                    pendingJumpMoveIndex = null
                }
            } else {
                null
            }
            val targetIndex = scheduledJump ?: previousIndex
            if (targetIndex >= 0) {
                goToMove(targetIndex)
            } else {
                goToStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading game into BoardViewModel", e)
        }
    }

    private fun createInitialBoard(): Board {
        val b = Board()
        startFen?.let { fen ->
            runCatching { b.loadFromFen(fen) }
        }
        return b
    }

    private fun boardFromFen(fen: String): Board {
        val b = Board()
        if (runCatching { b.loadFromFen(fen) }.isFailure) {
            startFen?.let { runCatching { b.loadFromFen(it) } }
        }
        return b
    }

    private fun clearState() {
        cancelEngineJobs()
        startFen = null
        initialFen = Board().fen
        startMoveNumber = 1
        firstMoveIsWhite = true
        board.value = Board()
        moves.clear()
        moveNotations.clear()
        sanMoves.clear()
        fensAfterPly.clear()
        lastMovesByPly.clear()
        evaluations.clear()
        mistakes.clear()
        analyzedMoves.clear()
        clocksByPly.clear()
        retryState.value = null
        variationSteps.clear()
        variationAnchorIndex.intValue = -1
        isLiveEngineEnabled.value = false
        engineLineState.value = null
        currentMoveIndex.intValue = -1
        lastMove.value = null
        highlightedSquares.value = emptySet()
    }

    private fun loadMoves(halfMoves: List<Move>) {
        val simBoard = createInitialBoard()
        var moveNumber = simBoard.moveCounter.coerceAtLeast(1)

        halfMoves.forEach { move ->
            val isWhite = simBoard.sideToMove == Side.WHITE
            val fenBefore = simBoard.fen
            val san = move.san?.takeIf { it.isNotBlank() }
                ?: ChessMoveUtils.uciToSan(ChessMoveUtils.moveToUci(move), fenBefore)

            if (!simBoard.doMove(move)) return@forEach

            moves.add(move)
            sanMoves.add(san)
            moveNotations.add(if (isWhite) "$moveNumber. $san" else "$moveNumber... $san")
            fensAfterPly.add(simBoard.fen)
            lastMovesByPly.add(move.from to move.to)

            if (!isWhite) {
                moveNumber++
            }
        }
    }

    private fun loadEvaluations(ev: List<Int>, g: Game) {
        evaluations.clear()
        when {
            ev.isNotEmpty() -> evaluations.addAll(ev)
            g.hasEvaluations() -> evaluations.addAll(g.getEvaluations())
            else -> evaluations.add(0)
        }
    }

    /**
     * FEN позиции ДО указанного полухода (мгновенный O(1) доступ).
     */
    fun getFenBeforeMove(moveIndex: Int): String {
        if (moveIndex <= 0) return initialFen
        return fensAfterPly.getOrElse(moveIndex - 1) { initialFen }
    }

    /**
     * Сделанный ход `(from, to)` на указанном полуходе (для двойных стрелок сравнения).
     */
    fun getPlayedMoveSquares(moveIndex: Int): Pair<Square, Square>? =
        lastMovesByPly.getOrNull(moveIndex)

    fun toggleFlipBoard() {
        isBoardFlipped.value = !isBoardFlipped.value
    }

    // ==================== НАВИГАЦИЯ ПО ПАРТИИ И КЛЮЧЕВЫМ МОМЕНТАМ ====================

    private fun clearInteractiveOverlays() {
        retryCheckJob?.cancel()
        retryState.value = null
        variationSteps.clear()
        variationAnchorIndex.intValue = -1
    }

    private fun applyMainlinePosition(targetIndex: Int) {
        clearInteractiveOverlays()
        if (targetIndex < 0 || moves.isEmpty()) {
            currentMoveIndex.intValue = -1
            board.value = boardFromFen(initialFen)
            lastMove.value = null
            highlightedSquares.value = emptySet()
        } else {
            val clamped = targetIndex.coerceIn(0, moves.lastIndex)
            currentMoveIndex.intValue = clamped
            board.value = boardFromFen(fensAfterPly[clamped])
            lastMove.value = lastMovesByPly[clamped]
            updateHighlights()
        }
        onBoardPositionChanged()
    }

    fun goToStart() {
        applyMainlinePosition(-1)
    }

    fun goToEnd() {
        if (moves.isEmpty()) return
        applyMainlinePosition(moves.lastIndex)
    }

    fun goToNextMove() {
        // Если открыта своя ветка или режим исправления — возвращаемся к партии и идём дальше
        if (isInRetryMode()) {
            exitRetryMode(advanceToNext = true)
            return
        }
        if (isInVariation()) {
            exitVariation()
            return
        }
        if (currentMoveIndex.intValue >= moves.lastIndex) return
        applyMainlinePosition(currentMoveIndex.intValue + 1)
    }

    fun goToPreviousMove() {
        // В аналитической ветке кнопка «Назад» сначала пошагово откатывает ходы ветки
        if (isInVariation()) {
            stepBackVariation()
            return
        }
        if (isInRetryMode()) {
            exitRetryMode(advanceToNext = false)
            return
        }
        if (currentMoveIndex.intValue < 0) return
        applyMainlinePosition(currentMoveIndex.intValue - 1)
    }

    fun goToMove(idx: Int) {
        applyMainlinePosition(idx)
    }

    fun getNavigableKeyMomentIndices(
        filter: GameReviewHelper.MomentFilter = GameReviewHelper.MomentFilter.ALL
    ): List<Int> = GameReviewHelper.navigableMomentIndices(
        analyzedMoves = analyzedMoves,
        mistakes = mistakes,
        playerColor = playerColor,
        filter = filter
    )

    fun hasPreviousKeyMoment(): Boolean =
        GameReviewHelper.findPreviousMomentIndex(
            currentMoveIndex.intValue,
            getNavigableKeyMomentIndices()
        ) != null

    fun hasNextKeyMoment(): Boolean =
        GameReviewHelper.findNextMomentIndex(
            currentMoveIndex.intValue,
            getNavigableKeyMomentIndices()
        ) != null

    fun goToPreviousKeyMoment(): Boolean {
        val prev = GameReviewHelper.findPreviousMomentIndex(
            currentMoveIndex.intValue,
            getNavigableKeyMomentIndices()
        ) ?: return false
        applyMainlinePosition(prev)
        return true
    }

    fun goToNextKeyMoment(): Boolean {
        val next = GameReviewHelper.findNextMomentIndex(
            currentMoveIndex.intValue,
            getNavigableKeyMomentIndices()
        ) ?: return false
        applyMainlinePosition(next)
        return true
    }

    private fun updateHighlights() {
        val squares = mutableSetOf<Square>()
        val quality = getCurrentAnalyzedMove()?.quality
        val fallbackMistake = if (quality == null) getCurrentMistake() else null
        if (quality == MoveQuality.MISTAKE ||
            quality == MoveQuality.BLUNDER ||
            quality == MoveQuality.MISSED_WIN ||
            fallbackMistake != null
        ) {
            lastMove.value?.second?.let { squares.add(it) }
        }
        highlightedSquares.value = squares
    }

    // ==================== ИНТЕРАКТИВНЫЙ РЕЖИМ «ИСПРАВИТЬ ОШИБКУ» ====================

    private fun resolveRetrySetupForCurrentMove(
        idx: Int = currentMoveIndex.intValue
    ): GameReviewHelper.RetryChallengeSetup? {
        if (idx < 0 || idx >= moves.size) return null
        return GameReviewHelper.resolveRetryChallengeSetup(
            targetMoveIndex = idx,
            playerColor = playerColor,
            fenBeforeMove = getFenBeforeMove(idx),
            fenAfterMove = fensAfterPly.getOrNull(idx),
            playedSan = sanMoves.getOrElse(idx) { "" },
            analyzedMoves = analyzedMoves,
            mistakes = mistakes,
            sanMoves = sanMoves,
            firstMoveIsWhite = firstMoveIsWhite,
            startMoveNumber = startMoveNumber
        )
    }

    fun canRetryCurrentMove(): Boolean {
        if (isInVariation() || isInRetryMode()) return false
        return resolveRetrySetupForCurrentMove() != null
    }

    fun startRetryCurrentMove(answerWasRevealed: Boolean = false) {
        val idx = currentMoveIndex.intValue
        val setup = resolveRetrySetupForCurrentMove(idx) ?: return

        cancelEngineJobs()
        engineLineState.value = null
        variationSteps.clear()
        variationAnchorIndex.intValue = -1

        board.value = boardFromFen(setup.fenToPlay)
        lastMove.value = if (setup.isPlayerMistake) {
            lastMovesByPly.getOrNull(idx - 1)
        } else {
            lastMovesByPly.getOrNull(idx)
        }
        highlightedSquares.value = emptySet()

        val sideText = if (playerColor == ChessColor.WHITE) "белых" else "чёрных"
        val prompt = if (setup.isPlayerMistake) {
            "В партии вы сыграли ${setup.playedSan}. Найдите сильнейший ход за $sideText!"
        } else {
            "Соперник ошибся ходом ${setup.playedSan}. Найдите лучший ответ за $sideText!"
        }

        val analyzed = analyzedMoves.firstOrNull { it.moveIndex == idx }
        val currentMistake = mistakes.firstOrNull {
            it.moveNumber == setup.moveNumber && it.color == setup.moverColor
        }
        val evalAfterOpponent = if (!setup.isPlayerMistake) {
            evaluations.getOrNull(idx + 1)
        } else {
            null
        }
        val skillProfile = GameReviewHelper.resolveRetrySkillProfile(
            mistake = currentMistake,
            analyzedMove = analyzed,
            moveNumber = setup.moveNumber,
            isPlayerMistake = setup.isPlayerMistake,
            evalWhiteAfterOpponentMistake = evalAfterOpponent,
            playerColor = playerColor
        )

        retryState.value = RetryMistakeUiState(
            targetMoveIndex = idx,
            moveNumber = setup.moveNumber,
            moverColor = setup.moverColor,
            isPlayerMistake = setup.isPlayerMistake,
            fenBefore = setup.fenToPlay,
            playedSan = setup.playedSan,
            expectedBestRaw = setup.expectedBestUci,
            expectedBestSan = setup.expectedBestSan,
            feedback = prompt,
            answerRevealedBeforeRetry = answerWasRevealed,
            playedMistakeNotation = setup.playedMistakeNotation,
            skillProfile = skillProfile
        )
    }

    fun saveSolvedRetryToRepetition(
        userId: Long,
        exerciseRepository: ExerciseRepository
    ) {
        val state = retryState.value ?: return
        if (!state.isSolved || state.isSavingRepetition || state.savedRepetitionMessage != null) return
        val targetIdx = state.targetMoveIndex
        val analyzed = analyzedMoves.firstOrNull { it.moveIndex == targetIdx }
        val mistake = mistakes.firstOrNull { it.moveNumber == state.moveNumber && it.color == state.moverColor }
        val gameId = currentGame?.id

        retryState.value = state.copy(isSavingRepetition = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GameReviewHelper.saveRetryPositionToRepetition(
                        userId = userId,
                        gameId = gameId,
                        moveNumber = state.moveNumber,
                        fenBefore = state.fenBefore,
                        expectedBestRaw = state.expectedBestRaw,
                        hintLevel = state.hintLevel,
                        answerRevealedBeforeRetry = state.answerRevealedBeforeRetry,
                        attemptsCount = state.attemptsCount,
                        isPlayerMistake = state.isPlayerMistake,
                        analyzedMove = analyzed,
                        mistake = mistake,
                        exerciseRepository = exerciseRepository
                    )
                }.getOrNull()
            }
            val current = retryState.value ?: return@launch
            if (current.targetMoveIndex != targetIdx) return@launch
            retryState.value = if (result != null) {
                val note = if (result.recordedAssistedAttempt) {
                    "Сохранено к повторению (с учётом подсказки — скоро вернётся)"
                } else if (result.createdNew) {
                    "Позиция сохранена в личный тренажёр к повторению"
                } else {
                    "Позиция уже есть в вашем списке повторения"
                }
                current.copy(
                    isSavingRepetition = false,
                    savedRepetitionMessage = note,
                    savedExerciseId = result.exercise.id
                )
            } else {
                current.copy(
                    isSavingRepetition = false,
                    savedRepetitionMessage = "Не удалось сохранить позицию"
                )
            }
        }
    }

    fun requestRetryHint() {
        val state = retryState.value ?: return
        if (state.isSolved || state.isCheckingEngine) return
        val nextLevel = (state.hintLevel + 1).coerceAtMost(2)
        val hint = GameReviewHelper.buildRetryHint(
            fenBefore = state.fenBefore,
            expectedBestNotation = state.expectedBestRaw,
            level = nextLevel
        ) ?: return

        val fromSq = runCatching {
            Square.fromValue(hint.fromSquare.uppercase()).takeIf { it != Square.NONE }
        }.getOrNull()

        if (fromSq != null) {
            highlightedSquares.value = setOf(fromSq)
        }

        retryState.value = state.copy(
            hintLevel = hint.level,
            hintFromSquare = fromSq,
            hintArrowUci = if (hint.level >= 2) hint.bestUci else null,
            feedback = hint.message
        )
    }

    fun submitRetryMove(
        move: Move,
        engine: ChessEngine? = null,
        isEngineBusy: Boolean = false
    ): GameReviewHelper.RetryVerdict {
        val state = retryState.value ?: return GameReviewHelper.RetryVerdict.ILLEGAL
        if (state.isSolved || state.isCheckingEngine) return GameReviewHelper.RetryVerdict.ILLEGAL

        val attemptedUci = ChessMoveUtils.moveToUci(move)
        val eval = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = state.fenBefore,
            attemptedNotation = attemptedUci,
            expectedBestNotation = state.expectedBestRaw,
            playedMistakeNotation = state.playedMistakeNotation
        )

        val nextAttempts = state.attemptsCount + 1

        when (eval.verdict) {
            GameReviewHelper.RetryVerdict.EXACT_BEST -> {
                val updatedBoard = boardFromFen(state.fenBefore)
                val parsed = ChessMoveUtils.parseUciMove(updatedBoard, eval.attemptedUci)
                if (parsed != null && updatedBoard.doMove(parsed)) {
                    board.value = updatedBoard
                    lastMove.value = parsed.from to parsed.to
                    highlightedSquares.value = emptySet()
                }
                retryState.value = state.copy(
                    isSolved = true,
                    isCheckingEngine = false,
                    hintArrowUci = null,
                    attemptsCount = nextAttempts,
                    feedback = "${eval.message}"
                )
            }

            GameReviewHelper.RetryVerdict.SAME_AS_GAME_MISTAKE -> {
                board.value = boardFromFen(state.fenBefore)
                retryState.value = state.copy(
                    attemptsCount = nextAttempts,
                    feedback = "↩ ${eval.message}"
                )
            }

            GameReviewHelper.RetryVerdict.ALTERNATIVE_LEGAL -> {
                // Если Stockfish свободен — проверяем, не нашёл ли игрок равноценный сильнейший ход
                if (engine != null && !isEngineBusy && eval.expectedBestUci.isNotEmpty()) {
                    checkEquivalentRetryMoveWithEngine(state, eval, nextAttempts, engine)
                } else {
                    board.value = boardFromFen(state.fenBefore)
                    retryState.value = state.copy(
                        attemptsCount = nextAttempts,
                        feedback = "${eval.message}"
                    )
                }
            }

            GameReviewHelper.RetryVerdict.ILLEGAL -> {
                retryState.value = state.copy(feedback = eval.message)
            }
        }
        return eval.verdict
    }

    private fun checkEquivalentRetryMoveWithEngine(
        state: RetryMistakeUiState,
        eval: GameReviewHelper.RetryEvaluation,
        nextAttempts: Int,
        engine: ChessEngine
    ) {
        val boardAttempt = boardFromFen(state.fenBefore)
        val attemptMove = ChessMoveUtils.parseUciMove(boardAttempt, eval.attemptedUci) ?: return
        if (!boardAttempt.doMove(attemptMove)) return
        val fenAfterAttempt = boardAttempt.fen

        // Если ход сразу ставит мат — засчитываем мгновенно без обращения к движку
        if (boardAttempt.isMated) {
            board.value = boardAttempt
            lastMove.value = attemptMove.from to attemptMove.to
            highlightedSquares.value = emptySet()
            retryState.value = state.copy(
                isSolved = true,
                isCheckingEngine = false,
                hintArrowUci = null,
                attemptsCount = nextAttempts,
                feedback = "Блестяще! ${eval.attemptedSan} сразу ставит мат!"
            )
            return
        }

        val boardBest = boardFromFen(state.fenBefore)
        val bestMove = ChessMoveUtils.parseUciMove(boardBest, eval.expectedBestUci) ?: return
        if (!boardBest.doMove(bestMove)) return
        val fenAfterBest = boardBest.fen

        retryState.value = state.copy(
            isCheckingEngine = true,
            attemptsCount = nextAttempts,
            feedback = "Проверяем ход ${eval.attemptedSan} движком Stockfish..."
        )

        retryCheckJob?.cancel()
        retryCheckJob = viewModelScope.launch {
            try {
                val equivalent = withContext(Dispatchers.IO) {
                    if (!engine.init()) return@withContext false
                    val oppAfterAttempt = engine.getBestMoveWithLine(fenAfterAttempt, RETRY_EQUIV_CHECK_DEPTH)
                        ?: return@withContext false
                    val oppAfterBest = engine.getBestMoveWithLine(fenAfterBest, RETRY_EQUIV_CHECK_DEPTH)
                        ?: return@withContext false
                    val moverScoreAttempt = -oppAfterAttempt.score
                    val moverScoreBest = -oppAfterBest.score
                    (moverScoreBest - moverScoreAttempt) <= GameReviewHelper.EQUIVALENT_BEST_TOLERANCE_CP
                }

                val current = retryState.value ?: return@launch
                if (current.targetMoveIndex != state.targetMoveIndex) return@launch

                if (equivalent) {
                    board.value = boardAttempt
                    lastMove.value = attemptMove.from to attemptMove.to
                    highlightedSquares.value = emptySet()
                    retryState.value = current.copy(
                        isSolved = true,
                        isCheckingEngine = false,
                        hintArrowUci = null,
                        feedback = "Засчитано! ${eval.attemptedSan} не уступает ходу движка (${eval.expectedBestSan})."
                    )
                } else {
                    board.value = boardFromFen(state.fenBefore)
                    retryState.value = current.copy(
                        isCheckingEngine = false,
                        feedback = "${eval.message}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Equivalent retry check failed", e)
                val current = retryState.value ?: return@launch
                board.value = boardFromFen(state.fenBefore)
                retryState.value = current.copy(
                    isCheckingEngine = false,
                    feedback = "${eval.message}"
                )
            }
        }
    }

    fun exitRetryMode(advanceToNext: Boolean = false) {
        val state = retryState.value ?: return
        retryCheckJob?.cancel()
        retryState.value = null
        val target = if (advanceToNext && state.targetMoveIndex < moves.lastIndex) {
            state.targetMoveIndex + 1
        } else {
            state.targetMoveIndex
        }
        applyMainlinePosition(target)
    }

    // ==================== СВОБОДНАЯ АНАЛИТИЧЕСКАЯ ВЕТКА НА ДОСКЕ ====================

    /**
     * Выполняет пользовательский ход на доске разбора:
     * - если ход совпадает со следующим ходом партии (и ветка ещё не начата), просто продвигает партию;
     * - иначе открывает/продолжает безопасную аналитическую ветку поверх текущего хода партии.
     */
    fun playExploratoryMove(
        move: Move,
        engine: ChessEngine? = null,
        isEngineBusy: Boolean = false
    ): Boolean {
        if (isInRetryMode()) return false
        if (engine != null) cachedEngine = engine

        val currentBoard = boardFromFen(board.value.fen)
        if (!ChessMoveUtils.isStrictlyLegal(currentBoard, move)) return false

        val moveUci = ChessMoveUtils.moveToUci(move)

        // Если мы на главной линии и игрок сделал ровно следующий ход партии — идём по партии
        if (!isInVariation()) {
            val isAltBefore = engineLineState.value?.isAlternativeBeforeMove == true && currentMoveIndex.intValue >= 0
            val nextIdx = if (isAltBefore) currentMoveIndex.intValue else currentMoveIndex.intValue + 1
            if (nextIdx in moves.indices) {
                val gameNextUci = ChessMoveUtils.moveToUci(moves[nextIdx])
                if (gameNextUci.equals(moveUci, ignoreCase = true)) {
                    applyMainlinePosition(nextIdx)
                    return true
                }
            }
            variationAnchorIndex.intValue = if (isAltBefore) currentMoveIndex.intValue - 1 else currentMoveIndex.intValue
        }

        val step = GameReviewHelper.buildVariationSteps(
            startFen = currentBoard.fen,
            moves = listOf(moveUci),
            maxPlies = 1
        ).firstOrNull() ?: return false

        variationSteps.add(step)
        board.value = boardFromFen(step.fenAfter)
        lastMove.value = move.from to move.to
        highlightedSquares.value = emptySet()

        val activeEngine = engine ?: cachedEngine
        if (activeEngine != null && !isEngineBusy && (isLiveEngineEnabled.value || engineLineState.value != null)) {
            requestEngineAnalysis(
                engine = activeEngine,
                fen = step.fenAfter,
                isAlternativeBeforeMove = false,
                isEngineBusy = false
            )
        } else {
            engineLineState.value = null
        }
        return true
    }

    fun stepBackVariation() {
        if (variationSteps.isEmpty()) return
        variationSteps.removeAt(variationSteps.lastIndex)
        if (variationSteps.isEmpty()) {
            exitVariation()
            return
        }
        val prev = variationSteps.last()
        board.value = boardFromFen(prev.fenAfter)
        lastMove.value = ChessMoveUtils.parseUciSquares(prev.uci)
        highlightedSquares.value = emptySet()
        onBoardPositionChanged()
    }

    fun exitVariation() {
        val anchor = variationAnchorIndex.intValue
        variationSteps.clear()
        variationAnchorIndex.intValue = -1
        applyMainlinePosition(anchor)
    }

    /**
     * Делает на доске первый ход из рассчитанной главной линии Stockfish,
     * позволяя пошагово разыграть сильнейшее продолжение или опровержение.
     */
    fun playEngineBestStep(engine: ChessEngine, isEngineBusy: Boolean = false): Boolean {
        cachedEngine = engine
        val line = engineLineState.value ?: return false
        val firstStep = line.pvSteps.firstOrNull() ?: return false

        // Если линия была посчитана для позиции ДО ошибки — начинаем ветку от предыдущего полухода
        if (line.isAlternativeBeforeMove && !isInVariation() && currentMoveIndex.intValue >= 0) {
            val anchorBefore = currentMoveIndex.intValue - 1
            val fenBefore = getFenBeforeMove(currentMoveIndex.intValue)
            val parsed = ChessMoveUtils.sanOrUciToMove(firstStep.uci, fenBefore) ?: return false
            variationAnchorIndex.intValue = anchorBefore
            variationSteps.clear()
            variationSteps.add(firstStep)
            board.value = boardFromFen(firstStep.fenAfter)
            lastMove.value = parsed.from to parsed.to
            highlightedSquares.value = emptySet()
            requestEngineAnalysis(
                engine = engine,
                fen = firstStep.fenAfter,
                isAlternativeBeforeMove = false,
                isEngineBusy = isEngineBusy
            )
            return true
        }

        val sim = boardFromFen(board.value.fen)
        val parsed = ChessMoveUtils.parseUciMove(sim, firstStep.uci) ?: return false
        return playExploratoryMove(parsed, engine = engine, isEngineBusy = isEngineBusy)
    }

    // ==================== АНАЛИЗ ПОЗИЦИИ ДВИЖКОМ STOCKFISH ====================

    fun toggleLiveEngine(engine: ChessEngine, isEngineBusy: Boolean = false) {
        cachedEngine = engine
        val enable = !isLiveEngineEnabled.value
        isLiveEngineEnabled.value = enable
        if (enable) {
            if (!isInRetryMode()) {
                requestEngineAnalysis(
                    engine = engine,
                    fen = board.value.fen,
                    isAlternativeBeforeMove = false,
                    isEngineBusy = isEngineBusy
                )
            }
        } else {
            engineJob?.cancel()
            engineLineState.value = null
        }
    }

    /**
     * Запрашивает у Stockfish сильнейшую линию ДО сыгранной ошибки (объясняя, почему лучший ход работает)
     * и переводит доску в пред-ошибочную позицию, чтобы стрелка и линия точно соответствовали фигурам.
     */
    fun analyzeBestAlternativeForCurrentMove(engine: ChessEngine, isEngineBusy: Boolean = false) {
        cachedEngine = engine
        val idx = currentMoveIndex.intValue
        val analyzeBefore = !isInVariation() && idx >= 0 &&
            (getCurrentMistake() != null || getCurrentAnalyzedMove()?.isMistake() == true)
        val targetFen = if (analyzeBefore) getFenBeforeMove(idx) else board.value.fen
        if (analyzeBefore && !isEngineBusy) {
            clearInteractiveOverlays()
            board.value = boardFromFen(targetFen)
            lastMove.value = lastMovesByPly.getOrNull(idx - 1)
            highlightedSquares.value = emptySet()
        }
        requestEngineAnalysis(
            engine = engine,
            fen = targetFen,
            isAlternativeBeforeMove = analyzeBefore && !isEngineBusy,
            isEngineBusy = isEngineBusy
        )
    }

    /**
     * Запрашивает у Stockfish сильнейшую линию для текущей позиции на доске
     * (например, для просмотра опровержения после ответа на ошибку соперника).
     */
    fun analyzeCurrentPosition(engine: ChessEngine, isEngineBusy: Boolean = false) {
        cachedEngine = engine
        requestEngineAnalysis(
            engine = engine,
            fen = board.value.fen,
            isAlternativeBeforeMove = false,
            isEngineBusy = isEngineBusy
        )
    }

    fun dismissEngineLine() {
        val wasAltBefore = engineLineState.value?.isAlternativeBeforeMove == true
        engineJob?.cancel()
        isLiveEngineEnabled.value = false
        engineLineState.value = null
        if (wasAltBefore && !isInVariation() && !isInRetryMode()) {
            val idx = currentMoveIndex.intValue
            if (idx in fensAfterPly.indices) {
                board.value = boardFromFen(fensAfterPly[idx])
                lastMove.value = lastMovesByPly.getOrNull(idx)
                updateHighlights()
            }
        }
    }

    private fun onBoardPositionChanged() {
        val engine = cachedEngine
        if (isLiveEngineEnabled.value && engine != null && !isInRetryMode()) {
            requestEngineAnalysis(
                engine = engine,
                fen = board.value.fen,
                isAlternativeBeforeMove = false,
                isEngineBusy = false
            )
        } else {
            engineJob?.cancel()
            engineLineState.value = null
        }
    }

    private fun requestEngineAnalysis(
        engine: ChessEngine,
        fen: String,
        isAlternativeBeforeMove: Boolean,
        isEngineBusy: Boolean
    ) {
        engineJob?.cancel()
        if (isEngineBusy) {
            engineLineState.value = EngineLineUiState(
                isLoading = false,
                analyzedFen = fen,
                errorMessage = "Движок занят фоновым анализом партии"
            )
            return
        }

        val sim = boardFromFen(fen)
        if (sim.isMated || sim.isStaleMate || sim.isDraw) {
            val terminalEval = when {
                sim.isMated -> if (sim.sideToMove == Side.WHITE) -ChessEngine.MATE_VALUE else ChessEngine.MATE_VALUE
                else -> 0
            }
            val terminalText = when {
                sim.isMated -> "Мат на доске"
                sim.isStaleMate -> "Пат — ничья"
                else -> "Ничья"
            }
            engineLineState.value = EngineLineUiState(
                isLoading = false,
                analyzedFen = fen,
                isAlternativeBeforeMove = isAlternativeBeforeMove,
                evalWhiteCp = terminalEval,
                pvFormatted = terminalText
            )
            return
        }

        engineLineState.value = EngineLineUiState(
            isLoading = true,
            analyzedFen = fen,
            isAlternativeBeforeMove = isAlternativeBeforeMove
        )

        engineJob = viewModelScope.launch {
            try {
                val computed = withContext(Dispatchers.IO) {
                    if (!engine.init()) {
                        return@withContext EngineLineUiState(
                            isLoading = false,
                            analyzedFen = fen,
                            errorMessage = "Не удалось инициализировать Stockfish"
                        )
                    }
                    val line = engine.getBestMoveWithLine(fen, REVIEW_ENGINE_DEPTH)
                        ?: return@withContext EngineLineUiState(
                            isLoading = false,
                            analyzedFen = fen,
                            errorMessage = "Линия не получена"
                        )
                    val evalWhite = GameReviewHelper.sideToMoveScoreToWhite(line.score, fen)
                    val rawMoves = if (line.principalVariation.isNotEmpty()) {
                        line.principalVariation
                    } else if (line.bestMove.isNotBlank()) {
                        listOf(line.bestMove)
                    } else {
                        emptyList()
                    }
                    val steps = GameReviewHelper.buildVariationSteps(fen, rawMoves, maxPlies = MAX_PV_PLIES)
                    EngineLineUiState(
                        isLoading = false,
                        analyzedFen = fen,
                        isAlternativeBeforeMove = isAlternativeBeforeMove,
                        evalWhiteCp = evalWhite,
                        bestMoveUci = steps.firstOrNull()?.uci ?: line.bestMove.takeIf { it.isNotBlank() },
                        pvSteps = steps,
                        pvFormatted = GameReviewHelper.formatVariationLine(steps)
                    )
                }
                if (!isRequestStillCurrent(fen, isAlternativeBeforeMove)) return@launch
                engineLineState.value = computed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live review engine analysis failed", e)
                if (!isRequestStillCurrent(fen, isAlternativeBeforeMove)) return@launch
                engineLineState.value = EngineLineUiState(
                    isLoading = false,
                    analyzedFen = fen,
                    errorMessage = "Ошибка анализа позиции"
                )
            }
        }
    }

    private fun isRequestStillCurrent(fen: String, isAlternativeBeforeMove: Boolean): Boolean {
        if (isInRetryMode()) return false
        return if (isAlternativeBeforeMove) {
            !isInVariation() && currentMoveIndex.intValue >= 0 &&
                getFenBeforeMove(currentMoveIndex.intValue) == fen
        } else {
            board.value.fen == fen
        }
    }

    private fun cancelEngineJobs() {
        engineJob?.cancel()
        retryCheckJob?.cancel()
        engineJob = null
        retryCheckJob = null
    }

    // ==================== ДАННЫЕ ТЕКУЩЕЙ ПОЗИЦИИ, ЧАСОВ И СВОДКИ ====================

    fun getCurrentEvaluation(): Int {
        // В аналитической ветке или при включённом движке показываем свежую оценку Stockfish
        val live = engineLineState.value
        if (live?.evalWhiteCp != null && !live.isAlternativeBeforeMove) {
            return live.evalWhiteCp
        }
        val retry = retryState.value
        if (retry != null && !retry.isSolved) {
            val evalIdx = if (retry.isPlayerMistake) retry.targetMoveIndex else retry.targetMoveIndex + 1
            return evaluations.getOrElse(evalIdx) { 0 }
        }
        return evaluations.getOrElse(currentMoveIndex.intValue + 1) { 0 }
    }

    fun getClockForSide(color: ChessColor): Int? = GameReviewHelper.clockForSideAtPly(
        clocksByPly = clocksByPly,
        color = color,
        currentMoveIndex = currentMoveIndex.intValue,
        firstMoveIsWhite = firstMoveIsWhite
    )

    fun getCurrentMoveClock(): Int? {
        val idx = currentMoveIndex.intValue
        return if (idx >= 0) clocksByPly[idx] else null
    }

    fun getCurrentMoveNotation(): String =
        moveNotations.getOrElse(currentMoveIndex.intValue) { "Начало" }

    fun getCurrentAnalyzedMove(): AnalyzedMove? =
        if (currentMoveIndex.intValue < 0 || isInVariation() || isInRetryMode()) {
            null
        } else {
            analyzedMoves.find { it.moveIndex == currentMoveIndex.intValue }
        }

    fun getCurrentMoveQuality(): MoveQuality? = getCurrentAnalyzedMove()?.quality

    fun getCurrentMistake(): Mistake? {
        if (currentMoveIndex.intValue < 0 || isInVariation() || isInRetryMode()) return null
        val analyzed = getCurrentAnalyzedMove()
        if (analyzed != null) {
            return if (analyzed.isMistake()) {
                mistakes.find { it.moveNumber == analyzed.moveNumber && it.color == analyzed.color }
            } else {
                null
            }
        }
        // Резерв для партий без записей analyzed_moves
        val idx = currentMoveIndex.intValue
        val isWhite = if (firstMoveIsWhite) idx % 2 == 0 else idx % 2 != 0
        val moveNo = startMoveNumber + ((idx + if (firstMoveIsWhite) 0 else 1) / 2)
        val color = if (isWhite) ChessColor.WHITE else ChessColor.BLACK
        return mistakes.find { it.moveNumber == moveNo && it.color == color }
    }

    fun hasRealEvaluations(): Boolean = evaluations.size > 1

    fun getKeyMoments(): List<KeyMoment> {
        return analyzedMoves.map { it.toKeyMoment(playerColor) }.sortedWith(
            compareBy(
                {
                    when (it.quality) {
                        MoveQuality.BLUNDER -> 0
                        MoveQuality.MISSED_WIN -> 1
                        MoveQuality.MISTAKE -> 2
                        MoveQuality.INACCURACY -> 3
                        MoveQuality.BRILLIANT -> 4
                        MoveQuality.GREAT_MOVE -> 5
                        MoveQuality.BEST_MOVE -> 6
                        MoveQuality.EXCELLENT -> 7
                        MoveQuality.GOOD -> 8
                        MoveQuality.BOOK -> 9
                    }
                },
                { -abs(it.evaluationChange) }
            )
        )
    }

    fun getMoveQualityStats(color: ChessColor = playerColor): Map<MoveQuality, Int> =
        analyzedMoves
            .filter { it.color == color }
            .groupBy { it.quality }
            .mapValues { it.value.size }

    fun getSideSummary(color: ChessColor): GameMoveClassifier.SideAnalysisSummary? {
        val sideMoves = analyzedMoves.filter { it.color == color }
        if (sideMoves.isNotEmpty()) {
            return GameMoveClassifier.summarizeSide(analyzedMoves, color)
        }
        if (evaluations.size > 2) {
            val acc = GameMoveClassifier.calculateGameAccuracyFromEvaluations(
                evaluations = evaluations,
                color = color,
                firstMoveIsWhite = firstMoveIsWhite
            )
            var totalLoss = 0
            var count = 0
            for (i in 1 until evaluations.size) {
                val idx = i - 1
                val isWhite = if (firstMoveIsWhite) idx % 2 == 0 else idx % 2 != 0
                val moveColor = if (isWhite) ChessColor.WHITE else ChessColor.BLACK
                if (moveColor == color) {
                    totalLoss += GameMoveClassifier.calculateCappedCpLoss(
                        evaluations[i - 1],
                        evaluations[i],
                        color
                    )
                    count++
                }
            }
            if (count > 0) {
                return GameMoveClassifier.SideAnalysisSummary(
                    color = color,
                    accuracy = acc,
                    averageCpLoss = totalLoss / count,
                    moveCount = count,
                    blunders = 0,
                    mistakes = 0,
                    missedWins = 0,
                    inaccuracies = 0,
                    qualityCounts = emptyMap()
                )
            }
        }
        return null
    }

    fun getPhaseAccuracy(
        color: ChessColor = playerColor
    ): GameMoveClassifier.PhaseAccuracyBreakdown {
        if (moves.isEmpty()) {
            return GameMoveClassifier.PhaseAccuracyBreakdown(null, null, null, 0, 0, 0)
        }
        val fensBefore = ArrayList<String>(moves.size)
        for (i in moves.indices) {
            fensBefore.add(getFenBeforeMove(i))
        }
        return GameMoveClassifier.calculatePhaseAccuracy(
            moves = analyzedMoves,
            color = color,
            fensBefore = fensBefore,
            startFullMove = startMoveNumber,
            firstMoveIsWhite = firstMoveIsWhite
        )
    }

    fun getCoachReport(
        outcome: PgnParser.PlayerOutcome = PgnParser.PlayerOutcome.UNKNOWN,
        phaseAccuracy: GameMoveClassifier.PhaseAccuracyBreakdown? = getPhaseAccuracy(playerColor)
    ): GameReviewHelper.CoachReport? = GameReviewHelper.buildCoachReport(
        playerColor = playerColor,
        analyzedMoves = analyzedMoves,
        mistakes = mistakes,
        phaseAccuracy = phaseAccuracy,
        clocksByPly = clocksByPly,
        outcome = outcome
    )

    fun getTrainableMistakesCount(): Int = GameReviewHelper.countTrainableMistakes(
        mistakes = mistakes,
        analyzedMoves = analyzedMoves,
        playerColor = playerColor
    )

    fun getDetectedOpeningLabel(): String? {
        if (startFen != null || fensAfterPly.isEmpty()) return null
        var bestLabel: String? = null
        val limit = fensAfterPly.size.coerceAtMost(24)
        for (i in 0 until limit) {
            val match = OpeningDatabase.matchBookMove(fensAfterPly[i])
            if (match?.openingName != null) {
                bestLabel = if (!match.eco.isNullOrBlank()) {
                    "${match.eco} · ${match.openingName}"
                } else {
                    match.openingName
                }
            }
        }
        return bestLabel
    }

    /** Распознанный дебют на момент текущего хода (для стартовых/дебютных ходов в разборе). */
    fun getOpeningLabelAtCurrentMove(): String? {
        if (startFen != null || currentMoveIndex.intValue < 0 || fensAfterPly.isEmpty()) return null
        val limit = currentMoveIndex.intValue.coerceAtMost(23).coerceAtMost(fensAfterPly.lastIndex)
        var bestLabel: String? = null
        for (i in 0..limit) {
            val match = OpeningDatabase.matchBookMove(fensAfterPly[i])
            if (match?.openingName != null) {
                bestLabel = if (!match.eco.isNullOrBlank()) {
                    "${match.eco} · ${match.openingName}"
                } else {
                    match.openingName
                }
            }
        }
        return bestLabel
    }

    fun getPlayerColor(): ChessColor = playerColor

    fun getTotalMoves(): Int = moves.size

    /**
     * Возвращает чистый снимок доски и пары клеток последнего хода для индекса [moveIndex]
     * без изменения текущего состояния просмотра в [BoardViewModel].
     */
    fun getBoardSnapshotAtMove(moveIndex: Int): Pair<Board, Pair<Square, Square>?> {
        if (fensAfterPly.isEmpty() || moveIndex < 0) {
            return boardFromFen(initialFen) to null
        }
        val clamped = moveIndex.coerceAtMost(fensAfterPly.lastIndex)
        return boardFromFen(fensAfterPly[clamped]) to lastMovesByPly.getOrNull(clamped)
    }

    fun isCurrentMovePlayerMove(): Boolean {
        val idx = currentMoveIndex.intValue
        if (idx < 0) return false
        val isWhiteMove = if (firstMoveIsWhite) idx % 2 == 0 else idx % 2 != 0
        return (playerColor == ChessColor.WHITE && isWhiteMove) ||
            (playerColor == ChessColor.BLACK && !isWhiteMove)
    }

    override fun onCleared() {
        super.onCleared()
        cancelEngineJobs()
        cachedEngine = null
        soundManager = null
    }
}
