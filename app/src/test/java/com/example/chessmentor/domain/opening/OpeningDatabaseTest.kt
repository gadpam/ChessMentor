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

    @Test
    fun expandedDatabase_matchesEarlyQueenIndianFlankOpeningsAndNimzoExact() {
        // E20 Защита Нимцовича (ранее содержала опечатку FEN без коня на c3)
        val nimzoFen = "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4"
        val nimzo = OpeningDatabase.matchBookMove(nimzoFen)
        assertEquals("E20", nimzo?.eco)
        assertEquals("Защита Нимцовича", nimzo?.openingName)

        // A40 1.d4 e6 (защита Горвица)
        val horwitzFen = "rnbqkbnr/pppp1ppp/4p3/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2"
        val horwitz = OpeningDatabase.matchBookMove(horwitzFen)
        assertEquals("A40", horwitz?.eco)
        assertEquals("Дебют ферзевых пешек: защита Горвица", horwitz?.openingName)

        // A45 1.d4 Nf6 2.Bg5 (атака Тромповского)
        val trompFen = "rnbqkb1r/pppppppp/5n2/6B1/3P4/8/PPP1PPPP/RN1QKBNR b KQkq - 2 2"
        val tromp = OpeningDatabase.matchBookMove(trompFen)
        assertEquals("A45", tromp?.eco)
        assertEquals("Атака Тромповского", tromp?.openingName)

        // D02 Лондонская система 1.d4 d5 2.Bf4
        val londonFen = "rnbqkbnr/ppp1pppp/8/3p4/3P1B2/8/PPP1PPPP/RN1QKBNR b KQkq - 1 2"
        val london = OpeningDatabase.matchBookMove(londonFen)
        assertEquals("D02", london?.eco)
        assertEquals("Лондонская система", london?.openingName)
    }

    @Test
    fun ecoFallbackAndTranslation_coverA40AndChessComEcoUrl() {
        assertEquals("Дебют ферзевых пешек", OpeningDatabase.ecoFallbackName("A40"))
        assertEquals("Индийская система", OpeningDatabase.ecoFallbackName("A45"))
        assertEquals(
            "Дебют ферзевых пешек: защита Горвица",
            OpeningDatabase.parseEcoUrlOpeningName(
                "https://www.chess.com/openings/Queens-Pawn-Opening-Horwitz-Defense-2.c4-e6"
            )
        )
        assertEquals(
            "Лондонская система",
            OpeningDatabase.translateOpeningName("London System")
        )
        assertEquals(
            "Итальянская партия",
            OpeningDatabase.translateOpeningName("Итальянская партия")
        )
    }
}
