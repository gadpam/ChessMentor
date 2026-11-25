package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.engine.EngineAnalysisProfile
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
            val requestedDepths = mutableListOf<Int>()
            val engine = object : ChessEngine {
                override suspend fun init() = true
                override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? {
                    requestedDepths.add(depthLimit)
                    return if (fen == this@TrainingMoveVerifierTest.fen) {
                        line("e2e4", 100)
                    } else {
                        line("d7d5", opponentScore)
                    }
                }
                override suspend fun evaluate(fen: String, depthLimit: Int): Int = error("Need a verified score")
                override suspend fun getBestMove(fen: String, depthLimit: Int): String? = error("Need a line")
                override suspend fun setOption(name: String, value: String) = Unit
                override fun stop() = Unit
                override fun destroy() = Unit
            }
            assertEquals(expected, verifyTrainingMove(engine, fen, move, false))
            assertEquals(
                listOf(EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH, EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH),
                requestedDepths
            )
        }
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotAVerdict(): Unit = runBlocking {
        verifyTrainingMove(engine { throw CancellationException() }, fen, move, false)
        Unit
    }

    @Test fun forcedMateChecksTempo_rejectsSlowerMateInPhilidorAndAcceptsSameTempo() = runBlocking {
        val philidor = CuratedExerciseBank.puzzles.single { it.id == "calc_black_philidor_mate3" }
        val optimalCheck = Move(Square.F2, Square.H3) // 1... Nh3+
        val slowerCheck = Move(Square.F2, Square.D3)  // 1... Nd3+ (матовый отход медленнее)
        val boardOptimal = Board().apply { loadFromFen(philidor.fen); doMove(optimalCheck) }
        val boardSlower = Board().apply { loadFromFen(philidor.fen); doMove(slowerCheck) }

        val m3Score = ChessEngine.MATE_VALUE - 300
        val oppM2Score = -(ChessEngine.MATE_VALUE - 200)
        val oppM3Score = -(ChessEngine.MATE_VALUE - 300)

        val engine = engine { qFen ->
            when (qFen) {
                philidor.fen -> AnalysisLine("f2h3", m3Score, 3, listOf("f2h3"))
                boardOptimal.fen -> AnalysisLine("g1h1", oppM2Score, -2, listOf("g1h1"))
                boardSlower.fen -> AnalysisLine("g1h1", oppM3Score, -3, listOf("g1h1"))
                else -> null
            }
        }

        // Тот же темп мата (М3 -> сопернику -М2) — лучший ход, а более медленный М4 (-М3) — ошибка
        assertEquals(TrainingMoveQuality.BEST, verifyTrainingMove(engine, philidor.fen, optimalCheck, false))
        assertEquals(TrainingMoveQuality.MISTAKE, verifyTrainingMove(engine, philidor.fen, slowerCheck, false))
    }

    @Test fun crushingAdvantageTolerance_requiresFivePawnEdgeAndTightLoss() = runBlocking {
        val boardAfter = Board().apply { loadFromFen(fen); doMove(move) }

        // При +4.00 (400 сП) потеря 60 сП уже не прощается
        val subFiveEngine = engine { qFen ->
            if (qFen == fen) line("e2e4", 460) else line("d7d5", -400)
        }
        assertEquals(TrainingMoveQuality.MISTAKE, verifyTrainingMove(subFiveEngine, fen, move, false))

        // При >= +5.00 (550 сП) и потере <= 55 сП равноценный выигрывающий путь допускается
        val crushingEngine = engine { qFen ->
            if (qFen == fen) line("e2e4", 600) else if (qFen == boardAfter.fen) line("d7d5", -550) else null
        }
        assertEquals(TrainingMoveQuality.EXCELLENT, verifyTrainingMove(crushingEngine, fen, move, false))
    }
}
