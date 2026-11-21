package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.CancellationException

internal enum class TrainingMoveQuality { BEST, EXCELLENT, MISTAKE, UNAVAILABLE }

/** Check an alternative legal move. Missing engine data is not an evaluation of zero. */
internal suspend fun verifyTrainingMove(
    engine: ChessEngine, fen: String, userMove: Move, isDefensive: Boolean
): TrainingMoveQuality {
    return try {
        val userBoard = Board()
        userBoard.loadFromFen(fen)
        if (!userBoard.doMove(userMove)) return TrainingMoveQuality.MISTAKE

        if (userBoard.isMated) {
            return TrainingMoveQuality.BEST
        }

        if (!engine.init()) return TrainingMoveQuality.UNAVAILABLE

        val bestLine = engine.getBestMoveWithLine(fen, 10)?.takeIf { it.bestMove.isNotBlank() }
            ?: return TrainingMoveQuality.UNAVAILABLE

        val userUci = ChessMoveUtils.moveToUci(move = userMove)
        if (userUci.equals(bestLine.bestMove, ignoreCase = true)) {
            return TrainingMoveQuality.BEST
        }

        val opponentEvalAfterUser = if (userBoard.isStaleMate || userBoard.isDraw) {
            0
        } else {
            engine.getBestMoveWithLine(userBoard.fen, 10)?.takeIf { it.bestMove.isNotBlank() }?.score
                ?: return TrainingMoveQuality.UNAVAILABLE
        }
        val playerEvalAfterUser = -opponentEvalAfterUser
        val playerEvalBest = bestLine.score

        if (ChessEngine.isMateScore(playerEvalAfterUser) && playerEvalAfterUser > 0) {
            return if (playerEvalAfterUser >= playerEvalBest) {
                TrainingMoveQuality.BEST
            } else {
                TrainingMoveQuality.EXCELLENT
            }
        }

        if (ChessEngine.isMateScore(playerEvalBest) && playerEvalBest > 0) {
            return TrainingMoveQuality.MISTAKE
        }

        val cpLoss = playerEvalBest - playerEvalAfterUser
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
    } catch (_: Exception) {
        TrainingMoveQuality.UNAVAILABLE
    }
}
