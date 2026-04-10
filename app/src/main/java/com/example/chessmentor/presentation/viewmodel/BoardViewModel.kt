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
import com.example.chessmentor.domain.entity.MoveQuality // <--- ВАЖНО: Импортируем только этот!
import com.example.chessmentor.presentation.ui.components.SoundManager
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import kotlin.math.abs

class BoardViewModel : ViewModel() {
    companion object { private const val TAG = "BoardViewModel" }

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
    var soundManager: SoundManager? = null

    private fun totalPlyCount(): Int = maxOf(moves.size, analyzedMoves.size)

    fun loadGame(game: Game, gameMistakes: List<Mistake>, gameAnalyzedMoves: List<AnalyzedMove> = emptyList(), gameEvaluations: List<Int> = emptyList()) {
        try {
            currentGame = game
            playerColor = game.playerColor
            clearState()
            mistakes.addAll(gameMistakes)
            analyzedMoves.addAll(gameAnalyzedMoves)
            val movesText = extractMovesText(game.pgnData)
            val pgnDocument = buildPgnDocument(movesText)
            val halfMoves = parsePgn(pgnDocument, movesText).ifEmpty {
                restoreMovesFromAnalyzedMoves(gameAnalyzedMoves)
            }
            if (halfMoves.isEmpty()) return
            loadMoves(halfMoves)
            loadEvaluations(gameEvaluations, game)
            goToStart()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun clearState() {
        board.value = Board(); moves.clear(); moveNotations.clear(); evaluations.clear()
        mistakes.clear(); analyzedMoves.clear(); currentMoveIndex.intValue = -1
        lastMove.value = null; highlightedSquares.value = emptySet()
    }

    private fun extractMovesText(pgn: String): String {
        val movesOnly = pgn.trim().replace(Regex("""\[[^\]]*\]"""), " ").replace(Regex("""\{[^}]*\}"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\$\d+"""), " ")
            .replace(Regex("""(1-0|0-1|1/2-1/2|\*)\s*$"""), "").replace(Regex("""\s+"""), " ").trim()
        if (movesOnly.isEmpty()) return ""
        return movesOnly
    }

    private fun buildPgnDocument(movesText: String): String {
        if (movesText.isEmpty()) return ""
        return "[Event \"Imported\"]\n\n$movesText *"
    }

    private fun parsePgn(pgnDocument: String, movesText: String): List<Move> {
        if (movesText.isEmpty()) return emptyList()
        val temp = File.createTempFile("game_pgn", ".pgn")
        return try {
            temp.writeText(pgnDocument)
            val holder = PgnHolder(temp.absolutePath)
            holder.loadPgn()
            if (holder.games.isEmpty()) {
                Log.w(TAG, "PGN parsed but contained no games, trying SAN fallback")
                parseMovesFromSan(movesText)
            } else {
                holder.games[0].halfMoves.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PGN for game view, trying SAN fallback", e)
            parseMovesFromSan(movesText)
        } finally {
            temp.delete()
        }
    }

    private fun parseMovesFromSan(movesText: String): List<Move> {
        return try {
            val moveList = MoveList()
            moveList.loadFromSan(movesText)
            moveList.toList().also {
                Log.i(TAG, "Parsed ${it.size} moves via SAN fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed SAN fallback parsing for game view", e)
            emptyList()
        }
    }

    private fun loadMoves(m: List<Move>) {
        moves.addAll(m)
        var num = 1
        m.forEachIndexed { i, move ->
            val san = move.san ?: move.toString()
            moveNotations.add(if (i % 2 == 0) "`$num. " + san else "`$num... " + san)
            if (i % 2 == 1) num++
        }
    }

    private fun restoreMovesFromAnalyzedMoves(gameAnalyzedMoves: List<AnalyzedMove>): List<Move> {
        if (gameAnalyzedMoves.isEmpty()) {
            Log.w(TAG, "No analyzed moves available for fallback reconstruction")
            return emptyList()
        }

        return try {
            val moveList = MoveList()
            gameAnalyzedMoves
                .sortedBy { it.moveIndex }
                .forEach { analyzedMove ->
                    moveList.addSanMove(analyzedMove.san, true, true)
                }

            moveList.toList().also {
                Log.i(TAG, "Restored ${it.size} moves from analyzed move SAN data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore moves from analyzed SAN data", e)
            emptyList()
        }
    }

    private fun loadEvaluations(ev: List<Int>, g: Game) {
        evaluations.clear()
        if (ev.isNotEmpty()) evaluations.addAll(ev)
        else if (g.hasEvaluations()) evaluations.addAll(g.getEvaluations())
        else evaluations.add(0)
    }

    private fun rebuildBoardToIndex(targetIndex: Int): Pair<Board, Pair<Square, Square>?> {
        val rebuiltBoard = Board()
        var rebuiltLastMove: Pair<Square, Square>? = null

        if (targetIndex >= 0) {
            for (i in 0..targetIndex.coerceAtMost(moves.lastIndex)) {
                val move = moves[i]
                rebuiltBoard.doMove(move)
                rebuiltLastMove = Pair(move.from, move.to)
            }
        }

        return rebuiltBoard to rebuiltLastMove
    }

    fun goToStart() {
        board.value = Board()
        currentMoveIndex.intValue = -1
        lastMove.value = null
        highlightedSquares.value = emptySet()
    }

    fun goToEnd() {
        val totalMoves = totalPlyCount()
        if (totalMoves == 0) return

        val targetIndex = moves.lastIndex
        val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(targetIndex)
        board.value = rebuiltBoard
        currentMoveIndex.intValue = totalMoves - 1
        lastMove.value = rebuiltLastMove
        updateHighlights()
    }

    fun goToNextMove() {
        val totalMoves = totalPlyCount()
        if (currentMoveIndex.intValue >= totalMoves - 1) return

        val nextIndex = currentMoveIndex.intValue + 1
        currentMoveIndex.intValue = nextIndex

        if (nextIndex < moves.size) {
            val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(nextIndex)
            board.value = rebuiltBoard
            lastMove.value = rebuiltLastMove
        } else {
            lastMove.value = null
        }

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
            board.value = Board()
            null
        }

        if (currentMoveIndex.intValue < 0) {
            lastMove.value = null
        }

        updateHighlights()
    }

    fun goToMove(idx: Int) {
        if (idx < 0) {
            goToStart()
            return
        }

        val totalMoves = totalPlyCount()
        if (totalMoves == 0) return

        val targetIndex = idx.coerceIn(0, totalMoves - 1)
        val (rebuiltBoard, rebuiltLastMove) = rebuildBoardToIndex(targetIndex)
        board.value = rebuiltBoard
        currentMoveIndex.intValue = targetIndex

        lastMove.value = if (targetIndex in moves.indices) {
            rebuiltLastMove
        } else {
            null
        }

        updateHighlights()
    }

    private fun updateHighlights() {
        val s = mutableSetOf<Square>()
        // Используем MoveQuality из Domain
        if (getCurrentAnalyzedMove()?.quality == MoveQuality.MISTAKE ||
            getCurrentAnalyzedMove()?.quality == MoveQuality.BLUNDER) {
            lastMove.value?.second?.let { s.add(it) }
        }
        highlightedSquares.value = s
    }

    fun getCurrentEvaluation(): Int = evaluations.getOrElse(currentMoveIndex.intValue + 1) { 0 }
    fun getCurrentMoveNotation(): String = moveNotations.getOrElse(currentMoveIndex.intValue) { "Start" }
    fun getCurrentAnalyzedMove(): AnalyzedMove? = if(currentMoveIndex.intValue < 0) null else analyzedMoves.find { it.moveIndex == currentMoveIndex.intValue }

    // Возвращаем MoveQuality из Domain
    fun getCurrentMoveQuality(): MoveQuality? = getCurrentAnalyzedMove()?.quality

    fun getCurrentMistake(): Mistake? = getCurrentAnalyzedMove()?.let { a -> if(a.isMistake()) mistakes.find { it.moveNumber == a.moveNumber && it.color == a.color } else null }
    fun hasRealEvaluations(): Boolean = evaluations.size > 1

    fun getKeyMoments(): List<KeyMoment> {
        return analyzedMoves.map { it.toKeyMoment() }.sortedWith(compareBy({
            // Используем MoveQuality из Domain
            when (it.quality) {
                MoveQuality.BLUNDER -> 0
                MoveQuality.MISTAKE -> 1
                MoveQuality.INACCURACY -> 2
                MoveQuality.MISSED_WIN -> 3
                MoveQuality.BRILLIANT -> 4
                MoveQuality.GREAT_MOVE -> 5
                MoveQuality.BEST_MOVE -> 6
                MoveQuality.EXCELLENT -> 7
                MoveQuality.GOOD -> 8
                MoveQuality.BOOK -> 9
            }
        }, { -abs(it.evaluationChange) }))
    }

    fun getMoveQualityStats(): Map<MoveQuality, Int> = analyzedMoves.groupBy { it.quality }.mapValues { it.value.size }
    fun getPlayerColor(): ChessColor = playerColor
    fun getTotalMoves(): Int = totalPlyCount()
    fun isCurrentMovePlayerMove(): Boolean = if (currentMoveIndex.intValue < 0) false else (playerColor == ChessColor.WHITE && currentMoveIndex.intValue % 2 == 0) || (playerColor == ChessColor.BLACK && currentMoveIndex.intValue % 2 != 0)
}
