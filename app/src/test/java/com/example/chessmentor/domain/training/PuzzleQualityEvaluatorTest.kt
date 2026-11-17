package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleQualityEvaluatorTest {

    private val sampleFen = "3r2k1/5ppp/8/8/3q4/7P/3Q1PP1/4R1K1 w - - 0 1"

    private fun mistake(loss: Int, type: MistakeType = MistakeType.BLUNDER, color: ChessColor = ChessColor.WHITE) =
        Mistake(
            gameId = 1L, themeId = 1L, moveNumber = 22, color = color,
            evaluationLoss = loss, mistakeType = type,
            bestMove = "Qxd4", userMove = "Qe2", fenBefore = sampleFen
        )

    private fun analyzed(evalBefore: Int, evalAfter: Int, loss: Int, color: ChessColor = ChessColor.WHITE) =
        AnalyzedMove(
            gameId = 1L, moveIndex = 42, moveNumber = 22, color = color,
            quality = MoveQuality.BLUNDER, san = "Qe2", bestMove = "Qxd4",
            evalBefore = evalBefore, evalAfter = evalAfter, evalLoss = loss
        )

    @Test
    fun classifyMistake_distinguishesWinningTactic_DefensiveResource_andMurkyPositionalSlip() {
        // 1. Чёткий упущенный тактический выигрыш (+290 -> +10) -> WINNING_TACTIC
        assertEquals(
            PuzzleKind.WINNING_TACTIC,
            PuzzleQualityEvaluator.classifyMistake(mistake(280), analyzed(290, 10, 280))
        )

        // 2. Зевок в равной позиции (+20 -> -340) -> DEFENSIVE_RESOURCE (задача на защиту/бдительность)
        assertEquals(
            PuzzleKind.DEFENSIVE_RESOURCE,
            PuzzleQualityEvaluator.classifyMistake(mistake(360), analyzed(20, -340, 360))
        )

        // 3. Зевок мата в равной позиции (0 -> -M2) -> DEFENSIVE_RESOURCE
        assertEquals(
            PuzzleKind.DEFENSIVE_RESOURCE,
            PuzzleQualityEvaluator.classifyMistake(
                mistake(99_800),
                analyzed(0, -(ChessEngine.MATE_VALUE - 200), 99_800)
            )
        )

        // 4. «Мутное» позиционное проседание (+75 -> -25, потеря 100 сП) -> REJECTED
        assertEquals(
            PuzzleKind.REJECTED,
            PuzzleQualityEvaluator.classifyMistake(
                mistake(100, MistakeType.MISTAKE),
                analyzed(75, -25, 100)
            )
        )

        // 5. Для чёрных знаки White-POV корректно инвертируются (-310 -> +20 = упущенный выигрыш чёрных)
        assertEquals(
            PuzzleKind.WINNING_TACTIC,
            PuzzleQualityEvaluator.classifyMistake(
                mistake(330, color = ChessColor.BLACK),
                analyzed(-310, 20, 330, color = ChessColor.BLACK)
            )
        )
    }

    @Test
    fun trimPvAtQuiescence_stopsAfterCleanCapture_butKeepsFullRecaptureAndCheckChains() {
        // 1. Чистое взятие незащищённого коня + тихие ходы -> обрезается до 1 полухода!
        val hangingFen = "2r3k1/5ppp/8/2N5/8/7P/5PP1/6K1 b - - 0 1"
        assertEquals(
            listOf("c8c5"),
            PuzzleQualityEvaluator.trimPvAtQuiescence(
                hangingFen,
                listOf("c8c5", "g1h2", "g8f8", "h2g3", "f8e7")
            )
        )

        // 2. Устранение защитника (1. Rxf6 gxf6 2. Bxd5 + тихий хвост) -> ровно 3 полухода!
        val removeDefFen = "6k1/5pp1/5n1p/3r4/8/5R1P/5PBK/8 w - - 0 1"
        assertEquals(
            listOf("f3f6", "g7f6", "g2d5"),
            PuzzleQualityEvaluator.trimPvAtQuiescence(
                removeDefFen,
                listOf("f3f6", "g7f6", "g2d5", "f6f5", "h2g3")
            )
        )

        // 3. Завлечение под королевскую вилку (1. Rxe8+ Kxe8 2. Nc7+ Kd7 3. Nxa6 + тихий хвост) -> ровно 5 полуходов!
        val decoyForkFen = "4rk2/5ppp/q7/3N4/8/8/5PPP/4R1K1 w - - 0 1"
        assertEquals(
            listOf("e1e8", "f8e8", "d5c7", "e8d7", "c7a6"),
            PuzzleQualityEvaluator.trimPvAtQuiescence(
                decoyForkFen,
                listOf("e1e8", "f8e8", "d5c7", "e8d7", "c7a6", "d7c6", "a6b4")
            )
        )
    }

    @Test
    fun hasClearTacticalSolution_rejectsPositionWithMultipleEqualAlternatives() = runBlocking {
        val board = com.github.bhlangonijr.chesslib.Board().apply { loadFromFen(sampleFen) }

        // Движок говорит, что все альтернативы так же хороши (+300 -> oppEval = -290) -> отклоняется
        val flatEngine = object : ChessEngine {
            override suspend fun init(): Boolean = true
            override suspend fun evaluate(fen: String, depthLimit: Int): Int = -290
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = "d2d4"
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int) = null
            override suspend fun setOption(name: String, value: String) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        assertFalse(
            PuzzleQualityEvaluator.hasClearTacticalSolution(
                board = board,
                bestMoveUci = "d2d4",
                bestScoreSideToMove = 300,
                puzzleKind = PuzzleKind.WINNING_TACTIC,
                engine = flatEngine
            )
        )

        // Движок говорит, что только лучший ход выигрывает (+500), а остальные проигрывают (oppEval = +200) -> принимается!
        val sharpEngine = object : ChessEngine {
            override suspend fun init(): Boolean = true
            override suspend fun evaluate(fen: String, depthLimit: Int): Int = 200
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = "d2d4"
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int) = null
            override suspend fun setOption(name: String, value: String) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        assertTrue(
            PuzzleQualityEvaluator.hasClearTacticalSolution(
                board = board,
                bestMoveUci = "d2d4",
                bestScoreSideToMove = 500,
                puzzleKind = PuzzleKind.WINNING_TACTIC,
                engine = sharpEngine
            )
        )
    }
}
