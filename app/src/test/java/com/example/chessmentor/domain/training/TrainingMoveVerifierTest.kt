package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingMoveVerifierTest {
    private val fen = Board().fen
    private val move = Move(Square.D2, Square.D4)
    private fun line(uci: String, cp: Int) = AnalysisLine(uci, cp, null, listOf(uci))
    private fun engine(ready: Boolean = true, search: (String) -> AnalysisLine?) = object : ChessEngine {
        override suspend fun init() = ready
        override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int) = search(fen)
        override suspend fun evaluate(fen: String, depthLimit: Int): Int = error("Need a verified score")
        override suspend fun getBestMove(fen: String, depthLimit: Int): String? = error("Need a line")
        override suspend fun setOption(name: String, value: String) = Unit
        override fun stop() = Unit
        override fun destroy() = Unit
    }

    @Test fun unavailableIsNeitherAMistakeNorAnEqualPosition() = runBlocking {
        val cases = listOf(engine(false) { null }, engine { null }, engine { error("engine stopped") },
            engine { if (it == fen) line("e2e4", 0) else null })
        for (engine in cases) {
            assertEquals(TrainingMoveQuality.UNAVAILABLE, verifyTrainingMove(engine, fen, move, false))
        }
    }

    @Test fun compareVerifiedOpponentScoreFromPlayersPerspective() = runBlocking {
        for ((opponentScore, expected) in listOf(-80 to TrainingMoveQuality.EXCELLENT, 80 to TrainingMoveQuality.MISTAKE)) {
            val engine = engine { if (it == fen) line("e2e4", 100) else line("d7d5", opponentScore) }
            assertEquals(expected, verifyTrainingMove(engine, fen, move, false))
        }
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotAVerdict(): Unit = runBlocking {
        verifyTrainingMove(engine { throw CancellationException() }, fen, move, false)
        Unit
    }
}
