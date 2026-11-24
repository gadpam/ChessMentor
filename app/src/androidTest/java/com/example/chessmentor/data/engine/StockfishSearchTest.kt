package com.example.chessmentor.data.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockfishSearchTest {
    @Test fun analysisAndMoveEvaluationFinishInTheAndroidProcess() = runBlocking {
        val engine = StockfishEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val ready = engine.init()
            assertTrue("Stockfish init: ${engine.status.value}", ready)
            val board = Board()
            val line = requireNotNull(engine.getBestMoveWithLine(board.fen, 3))
            assertEquals(line.bestMove, line.principalVariation.first())
            assertTrue(board.legalMoves().any { it.toString() == line.bestMove })

            // Training evaluates the position after the player's non-solution move.
            assertTrue(ChessMoveUtils.applyUciMove(board, "f2f3"))
            assertFalse(ChessEngine.isMateScore(engine.evaluate(board.fen, 3)))
        } finally {
            engine.destroy()
        }
    }

    @Test fun stopDuringSearchDoesNotReturnTruncatedResult() = runBlocking {
        val engine = StockfishEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val ready = engine.init()
            assertTrue("Stockfish init: ${engine.status.value}", ready)
            val fen = Board().fen
            val deepSearch = async(Dispatchers.IO) {
                engine.getBestMoveWithLine(fen, 30)
            }
            delay(250)
            withTimeout(10_000L) {
                while (deepSearch.isActive) {
                    engine.stop()
                    delay(20)
                }
            }
            assertNull("Stopped search must not return partial depth-1 result", deepSearch.await())

            val followUp = engine.getBestMoveWithLine(fen, 3)
            assertNotNull("Subsequent search after stop() must succeed", followUp)
        } finally {
            engine.destroy()
        }
    }
}
