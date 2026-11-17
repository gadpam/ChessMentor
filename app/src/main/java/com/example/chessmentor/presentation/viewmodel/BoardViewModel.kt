package com.example.chessmentor.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.example.chessmentor.presentation.ui.components.SoundManager
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import kotlin.math.abs

class BoardViewModel : ViewModel() {
    companion object {
        private const val TAG = "BoardViewModel"
    }

    val board = mutableStateOf(Board())
    val moves = mutableStateListOf<Move>()
    val moveNotations = mutableStateListOf<String>()
    val currentMoveIndex = mutableIntStateOf(-1)
    val highlightedSquares = mutableStateOf<Set<Square>>(emptySet())
    val lastMove = mutableStateOf<Pair<Square, Square>?>(null)

    val evaluations = mutableStateListOf<Int>()
    val analyzedMoves = mutableStateListOf<AnalyzedMove>()
    val mistakes = mutableStateListOf<Mistake>()

    private var playerColor: ChessColor = ChessColor.WHITE
    private var currentGame: Game? = null
    private var startFen: String? = null
    private var firstMoveIsWhite: Boolean = true
    var soundManager: SoundManager? = null

    fun getLoadedGameId(): Long? = currentGame?.id

    fun loadGame(
        game: Game,
        gameMistakes: List<Mistake>,
        gameAnalyzedMoves: List<AnalyzedMove> = emptyList(),
        gameEvaluations: List<Int> = emptyList(),
        preserveMoveIndex: Boolean = false
    ) {
        try {
            val previousIndex = if (preserveMoveIndex && currentGame?.id == game.id) {
                currentMoveIndex.intValue
            } else {
                -1
            }
            currentGame = game
            playerColor = game.playerColor
            clearState()
            mistakes.addAll(gameMistakes)
            analyzedMoves.addAll(gameAnalyzedMoves)

            val normalized = runCatching { ChessMoveUtils.normalizePgn(game.pgnData) }.getOrNull()
            startFen = normalized?.startFen

            val initialBoard = createInitialBoard()
            firstMoveIsWhite = initialBoard.sideToMove == Side.WHITE

            val halfMoves = if (normalized != null) {
                parsePgn(normalized)
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
            if (previousIndex >= 0) {
                goToMove(previousIndex)
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

    private fun clearState() {
        startFen = null
        firstMoveIsWhite = true
        board.value = Board()
        moves.clear()
        moveNotations.clear()
        evaluations.clear()
        mistakes.clear()
        analyzedMoves.clear()
        currentMoveIndex.intValue = -1
        lastMove.value = null
        highlightedSquares.value = emptySet()
    }

    private fun parsePgn(normalized: ChessMoveUtils.NormalizedPgn): List<Move> {
        if (normalized.movesText.isEmpty()) return emptyList()
        val temp = File.createTempFile("game_pgn", ".pgn")
        return try {
            temp.writeText(normalized.document)
            val holder = PgnHolder(temp.absolutePath)
            holder.loadPgn()
            val parsed = holder.games.firstOrNull()?.halfMoves?.toList().orEmpty()
            if (parsed.isEmpty()) {
                Log.w(TAG, "PGN parsed but contained no moves, trying SAN fallback")
                parseMovesFromSan(normalized)
            } else {
                parsed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PGN for game view, trying SAN fallback", e)
            parseMovesFromSan(normalized)
        } finally {
            temp.delete()
        }
    }

    private fun parseMovesFromSan(normalized: ChessMoveUtils.NormalizedPgn): List<Move> {
        return try {
            val moveList = if (normalized.startFen != null) {
                MoveList(normalized.startFen)
            } else {
                MoveList()
            }
            moveList.loadFromSan(normalized.movesText)
            moveList.toList().also {
                Log.i(TAG, "Parsed ${it.size} moves via SAN fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed SAN fallback parsing for game view", e)
            emptyList()
        }
    }

    private fun loadMoves(halfMoves: List<Move>) {
        moves.addAll(halfMoves)
        val simBoard = createInitialBoard()
        var moveNumber = simBoard.moveCounter.coerceAtLeast(1)

        halfMoves.forEach { move ->
            val isWhite = simBoard.sideToMove == Side.WHITE
            val fenBefore = simBoard.fen
            val san = move.san?.takeIf { it.isNotBlank() }
                ?: ChessMoveUtils.uciToSan(ChessMoveUtils.moveToUci(move), fenBefore)

            moveNotations.add(if (isWhite) "$moveNumber. $san" else "$moveNumber... $san")

            simBoard.doMove(move)
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

    private fun rebuildBoardToIndex(targetIndex: Int): Pair<Board, Pair<Square, Square>?> {
        val rebuiltBoard = createInitialBoard()
        var rebuiltLastMove: Pair<Square, Square>? = null

        if (targetIndex >= 0 && moves.isNotEmpty()) {
            for (i in 0..targetIndex.coerceAtMost(moves.lastIndex)) {
                val move = moves[i]
                rebuiltBoard.doMove(move)
                rebuiltLastMove = Pair(move.from, move.to)
            }
        }

        return rebuiltBoard to rebuiltLastMove
    }

    /**
     * FEN позиции ДО указанного полухода (для построения стрелки лучшего хода)
     */
    fun getFenBeforeMove(moveIndex: Int): String {
        if (moveIndex <= 0) return createInitialBoard().fen
        val (boardBefore, _) = rebuildBoardToIndex(moveIndex - 1)
        return boardBefore.fen
    }

    fun goToStart() {
        board.value = createInitialBoard()
        currentMoveIndex.intValue = -1
        lastMove.value = null
        highlightedSquares.value = emptySet()
    }

    fun goToEnd() {
        if (moves.isEmpty()) return

        val targetIndex = moves.lastIndex
        val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(targetIndex)
        board.value = rebuiltBoard
        currentMoveIndex.intValue = targetIndex
        lastMove.value = rebuiltLastMove
        updateHighlights()
    }

    fun goToNextMove() {
        if (currentMoveIndex.intValue >= moves.lastIndex) return

        val nextIndex = currentMoveIndex.intValue + 1
        currentMoveIndex.intValue = nextIndex

        val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(nextIndex)
        board.value = rebuiltBoard
        lastMove.value = rebuiltLastMove
        updateHighlights()
    }

    fun goToPreviousMove() {
        if (currentMoveIndex.intValue < 0) return

        currentMoveIndex.intValue--

        if (currentMoveIndex.intValue >= 0) {
            val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(currentMoveIndex.intValue)
            board.value = rebuiltBoard
            lastMove.value = rebuiltLastMove
        } else {
            board.value = createInitialBoard()
            lastMove.value = null
        }

        updateHighlights()
    }

    fun goToMove(idx: Int) {
        if (idx < 0) {
            goToStart()
            return
        }
        if (moves.isEmpty()) return

        val targetIndex = idx.coerceIn(0, moves.lastIndex)
        val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(targetIndex)
        board.value = rebuiltBoard
        currentMoveIndex.intValue = targetIndex
        lastMove.value = rebuiltLastMove
        updateHighlights()
    }

    private fun updateHighlights() {
        val squares = mutableSetOf<Square>()
        val quality = getCurrentAnalyzedMove()?.quality
        if (quality == MoveQuality.MISTAKE ||
            quality == MoveQuality.BLUNDER ||
            quality == MoveQuality.MISSED_WIN
        ) {
            lastMove.value?.second?.let { squares.add(it) }
        }
        highlightedSquares.value = squares
    }

    fun getCurrentEvaluation(): Int = evaluations.getOrElse(currentMoveIndex.intValue + 1) { 0 }

    fun getCurrentMoveNotation(): String =
        moveNotations.getOrElse(currentMoveIndex.intValue) { "Начало" }

    fun getCurrentAnalyzedMove(): AnalyzedMove? =
        if (currentMoveIndex.intValue < 0) null
        else analyzedMoves.find { it.moveIndex == currentMoveIndex.intValue }

    fun getCurrentMoveQuality(): MoveQuality? = getCurrentAnalyzedMove()?.quality

    fun getCurrentMistake(): Mistake? = getCurrentAnalyzedMove()?.let { analyzed ->
        if (analyzed.isMistake()) {
            mistakes.find { it.moveNumber == analyzed.moveNumber && it.color == analyzed.color }
        } else {
            null
        }
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

    /**
     * Сводка точности, ACPL и качества ходов для указанной стороны (игрока или соперника).
     * Возвращает null, если по этой стороне нет данных (например, партия анализировалась старой версией).
     */
    fun getSideSummary(color: ChessColor): com.example.chessmentor.domain.analysis.GameMoveClassifier.SideAnalysisSummary? {
        val sideMoves = analyzedMoves.filter { it.color == color }
        if (sideMoves.isNotEmpty()) {
            return com.example.chessmentor.domain.analysis.GameMoveClassifier.summarizeSide(analyzedMoves, color)
        }
        // Резервный расчёт точности соперника по сохранённым оценкам для ранее проанализированных партий
        if (evaluations.size > 2) {
            val acc = com.example.chessmentor.domain.analysis.GameMoveClassifier.calculateGameAccuracyFromEvaluations(
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
                    totalLoss += com.example.chessmentor.domain.analysis.GameMoveClassifier
                        .calculateCappedCpLoss(evaluations[i - 1], evaluations[i], color)
                    count++
                }
            }
            if (count > 0) {
                return com.example.chessmentor.domain.analysis.GameMoveClassifier.SideAnalysisSummary(
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

    /**
     * Точность указанной стороны по трём фазам партии (Дебют / Миттельшпиль / Эндшпиль).
     */
    fun getPhaseAccuracy(
        color: ChessColor = playerColor
    ): com.example.chessmentor.domain.analysis.GameMoveClassifier.PhaseAccuracyBreakdown {
        if (moves.isEmpty()) {
            return com.example.chessmentor.domain.analysis.GameMoveClassifier.PhaseAccuracyBreakdown(
                null, null, null, 0, 0, 0
            )
        }
        val simBoard = createInitialBoard()
        val startFullMove = simBoard.moveCounter.coerceAtLeast(1)
        val fensBefore = ArrayList<String>(moves.size)
        for (move in moves) {
            fensBefore.add(simBoard.fen)
            if (!simBoard.doMove(move)) break
        }
        return com.example.chessmentor.domain.analysis.GameMoveClassifier.calculatePhaseAccuracy(
            moves = analyzedMoves,
            color = color,
            fensBefore = fensBefore,
            startFullMove = startFullMove,
            firstMoveIsWhite = firstMoveIsWhite
        )
    }

    /**
     * Распознаёт сыгранный в партии дебют (ECO + русское название) по реальным ходам партии.
     */
    fun getDetectedOpeningLabel(): String? {
        if (startFen != null || moves.isEmpty()) return null
        val simBoard = createInitialBoard()
        var bestLabel: String? = null
        val limit = moves.size.coerceAtMost(24)
        for (i in 0 until limit) {
            if (!simBoard.doMove(moves[i])) break
            val match = com.example.chessmentor.domain.opening.OpeningDatabase.matchBookMove(simBoard.fen)
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

    fun isCurrentMovePlayerMove(): Boolean {
        val idx = currentMoveIndex.intValue
        if (idx < 0) return false
        val isWhiteMove = if (firstMoveIsWhite) idx % 2 == 0 else idx % 2 != 0
        return (playerColor == ChessColor.WHITE && isWhiteMove) ||
            (playerColor == ChessColor.BLACK && !isWhiteMove)
    }

    override fun onCleared() {
        super.onCleared()
        soundManager = null
    }
}
