package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.engine.EngineAnalysisProfile
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

        val bestLine = engine.getBestMoveWithLine(fen, EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH)
            ?.takeIf { it.bestMove.isNotBlank() }
            ?: return TrainingMoveQuality.UNAVAILABLE

        val userUci = ChessMoveUtils.moveToUci(move = userMove)
        if (userUci.equals(bestLine.bestMove, ignoreCase = true)) {
            return TrainingMoveQuality.BEST
        }

        val opponentEvalAfterUser = if (userBoard.isStaleMate || userBoard.isDraw) {
            0
        } else {
            engine.getBestMoveWithLine(userBoard.fen, EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH)
                ?.takeIf { it.bestMove.isNotBlank() }?.score
                ?: return TrainingMoveQuality.UNAVAILABLE
        }
        val playerEvalAfterUser = -opponentEvalAfterUser
        val playerEvalBest = bestLine.score

        val bestIsWinMate = ChessEngine.isMateScore(playerEvalBest) && playerEvalBest > 0
        val afterIsWinMate = ChessEngine.isMateScore(playerEvalAfterUser) && playerEvalAfterUser > 0

        if (afterIsWinMate) {
            if (!bestIsWinMate) {
                return TrainingMoveQuality.BEST
            }
            val bestMateMoves = ChessEngine.getMateInMoves(playerEvalBest)
            val afterMateMoves = ChessEngine.getMateInMoves(playerEvalAfterUser)
            val expectedAfterMoves = (bestMateMoves - 1).coerceAtLeast(0)
            return when {
                bestMateMoves <= 1 -> TrainingMoveQuality.MISTAKE
                afterMateMoves <= expectedAfterMoves -> TrainingMoveQuality.BEST
                bestMateMoves >= 5 && afterMateMoves == bestMateMoves -> TrainingMoveQuality.EXCELLENT
                else -> TrainingMoveQuality.MISTAKE
            }
        }

        if (bestIsWinMate) {
            return TrainingMoveQuality.MISTAKE
        }

        val bestIsLoseMate = ChessEngine.isMateScore(playerEvalBest) && playerEvalBest < 0
        val afterIsLoseMate = ChessEngine.isMateScore(playerEvalAfterUser) && playerEvalAfterUser < 0
        if (afterIsLoseMate) {
            return if (bestIsLoseMate && playerEvalAfterUser >= playerEvalBest) {
                TrainingMoveQuality.BEST
            } else {
                TrainingMoveQuality.MISTAKE
            }
        }
        if (bestIsLoseMate) {
            return TrainingMoveQuality.BEST
        }

        val cpLoss = playerEvalBest - playerEvalAfterUser
        when {
            cpLoss <= 15 -> TrainingMoveQuality.BEST
            cpLoss <= 45 -> TrainingMoveQuality.EXCELLENT
            // В защитной задаче любой ход, надёжно удерживающий равновесие без тактических потерь, корректен
            isDefensive && playerEvalAfterUser >= -140 && cpLoss <= 60 ->
                TrainingMoveQuality.EXCELLENT
            // При реализации уже подавляющего перевеса (+5 пешек) допускаем равноценный выигрывающий путь
            playerEvalAfterUser >= 500 && cpLoss <= 55 ->
                TrainingMoveQuality.EXCELLENT
            else -> TrainingMoveQuality.MISTAKE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TrainingMoveQuality.UNAVAILABLE
    }
}
