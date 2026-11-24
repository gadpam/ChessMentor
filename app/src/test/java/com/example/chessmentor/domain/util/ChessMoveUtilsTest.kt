package com.example.chessmentor.domain.util

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessMoveUtilsTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun uciToSan_convertsKnightMoveAndCastling() {
        assertEquals("Nf3", ChessMoveUtils.uciToSan("g1f3", startFen))

        val castlingFen = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        assertEquals("O-O", ChessMoveUtils.uciToSan("e1g1", castlingFen))
    }

    @Test
    fun sanOrUciToUci_handlesSanUciAndPromotions() {
        assertEquals("g1f3", ChessMoveUtils.sanOrUciToUci("Nf3", startFen))
        assertEquals("e2e4", ChessMoveUtils.sanOrUciToUci("e2e4", startFen))

        val promoFen = "7k/4P3/8/8/8/8/8/4K3 w - - 0 1"
        assertEquals("e7e8q", ChessMoveUtils.sanOrUciToUci("e8=Q+", promoFen))
        assertEquals("e7e8n", ChessMoveUtils.sanOrUciToUci("e8=N", promoFen))
        assertEquals("e7e8r", ChessMoveUtils.sanOrUciToUci("e7e8r", promoFen))
    }

    @Test
    fun isPawnPromotion_detectsLegalPromotionAndCreateMoveEncodesSuffix() {
        val board = Board().apply {
            loadFromFen("7k/4P3/8/8/8/8/8/4K3 w - - 0 1")
        }

        assertTrue(ChessMoveUtils.isPawnPromotion(board, Square.E7, Square.E8))
        assertFalse(ChessMoveUtils.isPawnPromotion(board, Square.E7, Square.D8))

        val knightPromotion = ChessMoveUtils.createMove(
            from = Square.E7,
            to = Square.E8,
            side = Side.WHITE,
            promotion = PieceType.KNIGHT
        )
        assertEquals("e7e8n", ChessMoveUtils.moveToUci(knightPromotion))
        assertTrue(board.doMove(knightPromotion))
        assertEquals(PieceType.KNIGHT, board.getPiece(Square.E8).pieceType)
    }

    @Test
    fun normalizePgn_stripsNestedVariationsCommentsAndPreservesFen() {
        val customFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val rawPgn = """
            [Event "Test"]
            [SetUp "1"]
            [FEN "$customFen"]

            3. Bb5 {Ruy Lopez [%clk 0:05:00]} (3. Bc4 Bc5 (3... Nf6 4. Ng5)) 3... a6 $1 ; Morphy defense
            4. Ba4 Nf6 1-0
        """.trimIndent()

        val normalized = ChessMoveUtils.normalizePgn(rawPgn)
        assertEquals(customFen, normalized.startFen)
        assertEquals("3. Bb5 3... a6 4. Ba4 Nf6", normalized.movesText)
        assertTrue(normalized.document.contains("""[FEN "$customFen"]"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizePgn_throwsOnHeadersWithoutMoves() {
        val emptyMoves = """
            [Event "Empty"]
            [Result "1/2-1/2"]

            {Only comment} 1/2-1/2
        """.trimIndent()
        ChessMoveUtils.normalizePgn(emptyMoves)
    }

    @Test
    fun sanOrUciToMove_returnsNullForIllegalMove() {
        assertNull(ChessMoveUtils.sanOrUciToMove("e2e5", startFen))
        assertNotNull(ChessMoveUtils.sanOrUciToMove("e4", startFen))
    }

    @Test
    fun parseMovesInMemory_parsesCastlingVariationsAndCustomFenWithoutTempFiles() {
        val rawPgn = """
            [Event "Memory"]
            [White "A"]
            [Black "B"]

            1. e4 {center} e5 (1... c5 2. Nf3) 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 1-0
        """.trimIndent()
        val normalized = ChessMoveUtils.normalizePgn(rawPgn)
        val moves = ChessMoveUtils.parseMovesInMemory(normalized)
        assertEquals(10, moves.size)
        assertEquals("e2e4", ChessMoveUtils.moveToUci(moves.first()))
        assertEquals("e1g1", ChessMoveUtils.moveToUci(moves[8]))

        val customFen = "7k/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val promoPgn = ChessMoveUtils.normalizePgn("""[SetUp "1"] [FEN "$customFen"] 1. e8=N Kg8 *""")
        val promoMoves = ChessMoveUtils.parseMovesInMemory(promoPgn)
        assertEquals(2, promoMoves.size)
        assertEquals("e7e8n", ChessMoveUtils.moveToUci(promoMoves.first()))
    }
}
