package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMistakeAnalyzerTest {

    private class FakeEngine : ChessEngine {
        override suspend fun init(): Boolean = true
        override suspend fun evaluate(fen: String, depthLimit: Int): Int = 0
        override suspend fun getBestMove(fen: String, depthLimit: Int): String? = null
        override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = null
        override suspend fun setOption(name: String, value: String) = Unit
        override fun stop() = Unit
        override fun destroy() = Unit
    }

    private val analyzer = SemanticMistakeAnalyzer(FakeEngine())

    @Test
    fun detectMotif_recognizesKnightForkOnKingAndRook() {
        // Конь с d5 идёт на c7 с вилкой на короля e8 и ладью a8
        val board = Board().apply {
            loadFromFen("r3k3/8/8/3N4/8/8/8/4K3 w - - 0 1")
        }

        val motif = analyzer.detectMotif(board, "d5c7")
        assertEquals(TacticalMotif.FORK, motif)
    }

    @Test
    fun analyzeComprehensive_reportsForcedMateThreat() {
        // Белые ходят a2-a3 и получают мат на 1-й горизонтали ...Re1#
        val board = Board().apply {
            loadFromFen("4r1k1/5ppp/8/8/8/8/P4PPP/6K1 w - - 0 1")
        }

        val bestLine = AnalysisLine(
            bestMove = "g1f1",
            score = -400,
            mateIn = null,
            principalVariation = listOf("g1f1")
        )
        val punishmentLine = AnalysisLine(
            bestMove = "e8e1",
            score = ChessEngine.MATE_VALUE - 100,
            mateIn = 1,
            principalVariation = listOf("e8e1")
        )

        val result = analyzer.analyzeComprehensive(
            boardBefore = board,
            playerMove = Move(Square.A2, Square.A3),
            bestMoveLine = bestLine,
            punishmentLine = punishmentLine
        )

        assertEquals(MistakeCategory.MATE_THREAT, result.category)
        assertEquals(TacticalMotif.BACK_RANK, result.tacticalMotif)
        assertTrue(result.shouldShowBetterMove())
        assertNotNull(result.betterMoveInfo)
    }

    @Test
    fun analyzeComprehensive_reportsMissedWinningTactic() {
        // Белые упускают вилку Nc7+ и ходят Ke2
        val board = Board().apply {
            loadFromFen("r3k3/8/8/3N4/8/8/8/4K3 w - - 0 1")
        }

        val bestLine = AnalysisLine(
            bestMove = "d5c7",
            score = 520,
            mateIn = null,
            principalVariation = listOf("d5c7", "e8d7", "c7a8")
        )
        val punishmentLine = AnalysisLine(
            bestMove = "a8a5",
            score = 180,
            mateIn = null,
            principalVariation = listOf("a8a5")
        )

        val result = analyzer.analyzeComprehensive(
            boardBefore = board,
            playerMove = Move(Square.E1, Square.E2),
            bestMoveLine = bestLine,
            punishmentLine = punishmentLine
        )

        assertEquals(MistakeCategory.MISSED_TACTIC, result.category)
        assertEquals(TacticalMotif.FORK, result.tacticalMotif)
        assertEquals("d5c7", result.betterMoveInfo?.moveUci)
    }

    @Test
    fun detectMotif_recognizesDeflectionAndOverloading() {
        // Отвлечение: 1. Qa8! отвлекает чёрную ладью d8 с защиты поля e8 под Re8#
        val deflectionBoard = Board().apply {
            loadFromFen("3r2k1/5ppp/8/8/Q7/8/8/4R1K1 w - - 0 1")
        }
        assertEquals(TacticalMotif.DEFLECTION, analyzer.detectMotif(deflectionBoard, "a4a8"))

        // Перегрузка: чёрный ферзь b7 одновременно защищает ладью a8 и слона c6; 1. Rxc6!
        val overloadingBoard = Board().apply {
            loadFromFen("r5k1/1q3ppp/2b5/8/8/8/8/R1R3K1 w - - 0 1")
        }
        assertEquals(TacticalMotif.OVERLOADING, analyzer.detectMotif(overloadingBoard, "c1c6"))
    }
}
