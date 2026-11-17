package com.example.chessmentor.domain.opening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningDatabaseTest {

    @Test
    fun findOpening_returnsNotFoundForStartingPosition() {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertTrue(OpeningDatabase.findOpening(startFen) is OpeningMatch.NotFound)
    }

    @Test
    fun findOpening_matchesExactFullFenForSicilianAndItalian() {
        val sicilianFen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2"
        val sicilianMatch = OpeningDatabase.findOpening(sicilianFen)
        assertTrue(sicilianMatch is OpeningMatch.Found)
        val sicilian = (sicilianMatch as OpeningMatch.Found)
        assertEquals("B20", sicilian.opening.eco)
        assertEquals(MatchConfidence.EXACT, sicilian.confidence)

        val italianFen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
        val italianMatch = OpeningDatabase.findOpening(italianFen)
        assertTrue(italianMatch is OpeningMatch.Found)
        assertEquals("C50", (italianMatch as OpeningMatch.Found).opening.eco)
    }

    @Test
    fun findByEco_returnsRuyLopezWithoutInitializationCrash() {
        val list = OpeningDatabase.findByEco("C65")
        assertEquals(1, list.size)
        assertEquals("Испанская партия", list.first().name)
    }
}
