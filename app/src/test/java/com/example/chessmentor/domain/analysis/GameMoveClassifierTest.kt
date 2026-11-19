package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.move.MoveList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMoveClassifierTest {

    // ==================== МОДЕЛЬ WIN% ====================

    @Test
    fun winProbability_isSymmetricAndMonotonicForBothColors() {
        assertEquals(50.0, GameMoveClassifier.winProbability(0, ChessColor.WHITE), 0.01)
        assertEquals(50.0, GameMoveClassifier.winProbability(0, ChessColor.BLACK), 0.01)

        val whitePlus200 = GameMoveClassifier.winProbability(200, ChessColor.WHITE)
        val blackPlus200 = GameMoveClassifier.winProbability(-200, ChessColor.BLACK)
        val blackMinus200 = GameMoveClassifier.winProbability(200, ChessColor.BLACK)

        assertEquals(whitePlus200, blackPlus200, 0.001)
        assertEquals(100.0, whitePlus200 + blackMinus200, 0.001)
        assertTrue(whitePlus200 in 65.0..70.0)

        val mateForWhite = ChessEngine.MATE_VALUE - 300
        assertEquals(100.0, GameMoveClassifier.winProbability(mateForWhite, ChessColor.WHITE), 0.001)
        assertEquals(0.0, GameMoveClassifier.winProbability(mateForWhite, ChessColor.BLACK), 0.001)
    }

    // ==================== КЛАССИФИКАЦИЯ ПО ΔWIN% ====================

    @Test
    fun classifyMove_technicalSimplificationInWinningPositionIsNotBlunder() {
        // Размен ферзей при +8.5 -> +5.2 (-330 сП): сырой порог 300 сП ошибочно ставил бы BLUNDER,
        // а модель Win% с защитой технического упрощения честно ставит GOOD.
        val res = GameMoveClassifier.classifyMove(
            evalBefore = 850,
            evalAfter = 520,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.GOOD, res.quality)
        assertTrue(res.rawCpLoss == 330)
    }

    @Test
    fun classifyMove_deadLostPositionDropDoesNotCreateFakeBlunder() {
        // Уже безнадёжные -6.5 -> -9.5 у белых не должны накручивать ложные зевки
        val res = GameMoveClassifier.classifyMove(
            evalBefore = -650,
            evalAfter = -950,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.GOOD, res.quality)
    }

    @Test
    fun classifyMove_distinguishesInaccuracyMistakeBlunderAndMissedWin() {
        val inaccuracy = GameMoveClassifier.classifyMove(
            evalBefore = 30,
            evalAfter = -55,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.INACCURACY, inaccuracy.quality)

        val mistake = GameMoveClassifier.classifyMove(
            evalBefore = 20,
            evalAfter = -120,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.MISTAKE, mistake.quality)

        // Зевок фигуры в равной позиции (0.0 -> -3.1) -> BLUNDER
        val blunder = GameMoveClassifier.classifyMove(
            evalBefore = 0,
            evalAfter = -310,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.BLUNDER, blunder.quality)

        // Упущенный выигрыш (+3.6 -> +0.4: было выиграно, стало равно, но не проиграно) -> MISSED_WIN
        val missedWin = GameMoveClassifier.classifyMove(
            evalBefore = 360,
            evalAfter = 40,
            movingColor = ChessColor.WHITE,
            prevOpponentWinLoss = 22.0
        )
        assertEquals(MoveQuality.MISSED_WIN, missedWin.quality)
        assertTrue(missedWin.unpunishedOpponentMistake)

        // Переворот выигранной позиции в проигранную (+3.2 -> -2.2) -> полноценный BLUNDER
        val thrownToLoss = GameMoveClassifier.classifyMove(
            evalBefore = 320,
            evalAfter = -220,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.BLUNDER, thrownToLoss.quality)
    }

    @Test
    fun classifyMove_handlesForcedMateTransitionsForBlackAndWhite() {
        val whiteMateIn4 = ChessEngine.MATE_VALUE - 400
        val whiteMateIn2 = ChessEngine.MATE_VALUE - 200
        val blackMateIn3 = -(ChessEngine.MATE_VALUE - 300)

        // Чёрные зевнули мат в 4 из равной позиции -> BLUNDER
        val allowedMate = GameMoveClassifier.classifyMove(
            evalBefore = 15,
            evalAfter = whiteMateIn4,
            movingColor = ChessColor.BLACK
        )
        assertEquals(MoveQuality.BLUNDER, allowedMate.quality)

        // Чёрные уже получали мат в 4, после хода мат в 2 -> не новый зевок (GOOD)
        val shortenedExistingMate = GameMoveClassifier.classifyMove(
            evalBefore = whiteMateIn4,
            evalAfter = whiteMateIn2,
            movingColor = ChessColor.BLACK
        )
        assertEquals(MoveQuality.GOOD, shortenedExistingMate.quality)

        // Белые упустили мат в 4 и выпустили до +0.8 -> MISSED_WIN
        val missedMateToEdge = GameMoveClassifier.classifyMove(
            evalBefore = whiteMateIn4,
            evalAfter = 80,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.MISSED_WIN, missedMateToEdge.quality)

        // Белые упустили мат в 4, но выиграли ферзя (+6.5) -> мягкая INACCURACY, не BLUNDER
        val missedMateStillCrushing = GameMoveClassifier.classifyMove(
            evalBefore = whiteMateIn4,
            evalAfter = 650,
            movingColor = ChessColor.WHITE
        )
        assertEquals(MoveQuality.INACCURACY, missedMateStillCrushing.quality)

        // Чёрные нашли неочевидный мат в 3 из острой позиции -> GREAT_MOVE
        val foundMate = GameMoveClassifier.classifyMove(
            evalBefore = -180,
            evalAfter = blackMateIn3,
            movingColor = ChessColor.BLACK,
            playedEngineBest = true
        )
        assertEquals(MoveQuality.GREAT_MOVE, foundMate.quality)
        assertEquals(GameMoveClassifier.GreatMoveReason.FOUND_FORCED_MATE, foundMate.greatReason)
    }

    // ==================== ЖЕРТВЫ МАТЕРИАЛА (BRILLIANT !!) И GREAT_MOVE (!) ====================

    @Test
    fun detectMaterialSacrifice_recognizesGreekGiftAndLegalsMateAndRejectsRoutineTrades() {
        // 1. Классическая жертва слона Bxh7+!! (слон не защищён, король g8 легально бьёт h7)
        val greekGiftBoard = Board().apply {
            loadFromFen("r1bq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 2 8")
        }
        val bxh7 = ChessMoveUtils.parseUciMove(greekGiftBoard, "d3h7")
        assertNotNull(bxh7)
        val greekSac = GameMoveClassifier.detectMaterialSacrifice(greekGiftBoard, bxh7!!)
        assertNotNull(greekSac)
        assertEquals(GameMoveClassifier.SacrificeKind.DIRECT_PIECE, greekSac!!.kind)
        assertEquals(PieceType.BISHOP, greekSac.sacrificedPiece)

        val brilliantClass = GameMoveClassifier.classifyMove(
            evalBefore = 210,
            evalAfter = 220,
            movingColor = ChessColor.WHITE,
            moveNumber = 8,
            playedEngineBest = true,
            sacrifice = greekSac
        )
        assertEquals(MoveQuality.BRILLIANT, brilliantClass.quality)

        // 2. Косвенная жертва ферзя в мате Легаля: 5. Nxe5!! открывает ферзя d1 под Bxd1
        val legalBoard = Board().apply {
            loadFromFen("rn1qkbnr/ppp2ppp/3p4/4p3/2B1P1b1/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 5")
        }
        val nxe5 = ChessMoveUtils.parseUciMove(legalBoard, "f3e5")
        assertNotNull(nxe5)
        val legalSac = GameMoveClassifier.detectMaterialSacrifice(legalBoard, nxe5!!)
        assertNotNull(legalSac)
        assertEquals(GameMoveClassifier.SacrificeKind.INDIRECT_HANGING, legalSac!!.kind)
        assertEquals(PieceType.QUEEN, legalSac.sacrificedPiece)

        // 3. Рутинный размен Bxc6 в испанской партии — НЕ жертва
        val ruyExchangeBoard = Board().apply {
            loadFromFen("r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4")
        }
        val bxc6 = ChessMoveUtils.parseUciMove(ruyExchangeBoard, "b5c6")
        assertNotNull(bxc6)
        assertNull(GameMoveClassifier.detectMaterialSacrifice(ruyExchangeBoard, bxc6!!))

        // 4. Детский мат Qxf7# (ферзь защищён слоном c4, мат на доске) — НЕ жертва
        val scholarBoard = Board().apply {
            loadFromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
        }
        val qxf7 = ChessMoveUtils.parseUciMove(scholarBoard, "h5f7")
        assertNotNull(qxf7)
        assertNull(GameMoveClassifier.detectMaterialSacrifice(scholarBoard, qxf7!!))
    }

    @Test
    fun classifyMove_greatMovePunishesOpponentMistakeUnlessAutomaticRecapture() {
        val punishment = GameMoveClassifier.classifyMove(
            evalBefore = 240,
            evalAfter = 235,
            movingColor = ChessColor.WHITE,
            moveNumber = 14,
            playedEngineBest = true,
            prevOpponentWinLoss = 18.0,
            isAutomaticRecapture = false
        )
        assertEquals(MoveQuality.GREAT_MOVE, punishment.quality)
        assertEquals(GameMoveClassifier.GreatMoveReason.PUNISHED_OPPONENT_MISTAKE, punishment.greatReason)

        // Очевидное взятие в ответ на том же поле — просто BEST_MOVE, без спама «!»
        val routineRecapture = GameMoveClassifier.classifyMove(
            evalBefore = 240,
            evalAfter = 235,
            movingColor = ChessColor.WHITE,
            moveNumber = 14,
            playedEngineBest = true,
            prevOpponentWinLoss = 18.0,
            isAutomaticRecapture = true
        )
        assertEquals(MoveQuality.BEST_MOVE, routineRecapture.quality)
    }

    // ==================== ДЕБЮТНАЯ КНИГА (BOOK) ====================

    @Test
    fun openingDatabase_matchBookMoveAcceptsMainlinesAndTranspositionsAndRejectsWaywardQueen() {
        val board = Board()
        val italianLine = listOf("e4", "e5", "Nf3", "Nc6", "Bc4")

        var lastMatchName: String? = null
        for (san in italianLine) {
            val m = ChessMoveUtils.sanOrUciToMove(san, board.fen)
            assertNotNull("Expected legal SAN '$san'", m)
            assertTrue(board.doMove(m!!))
            val match = OpeningDatabase.matchBookMove(board.fen)
            assertNotNull("Expected book match along Italian Game after $san", match)
            if (match?.openingName != null) {
                lastMatchName = match.openingName
            }
        }
        assertEquals("Итальянская партия", lastMatchName)

        // Перестановка ходов 1. Nf3 Nc6 2. e4 e5 3. Bc4 приводит в ту же итальянскую табию
        val transposed = Board()
        for (san in listOf("Nf3", "Nc6", "e4", "e5", "Bc4")) {
            val m = requireNotNull(ChessMoveUtils.sanOrUciToMove(san, transposed.fen))
            assertTrue(transposed.doMove(m))
        }
        val transMatch = OpeningDatabase.matchBookMove(transposed.fen)
        assertNotNull(transMatch)
        assertEquals("C50", transMatch?.eco)

        // Ранний выход ферзя 1. e4 e5 2. Qh5 НЕ должен считаться книжной теорией
        val wayward = Board()
        for (san in listOf("e4", "e5", "Qh5")) {
            val m = requireNotNull(ChessMoveUtils.sanOrUciToMove(san, wayward.fen))
            assertTrue(wayward.doMove(m))
        }
        assertNull(OpeningDatabase.matchBookMove(wayward.fen))
    }

    // ==================== ДВУСТОРОННЯЯ ТОЧНОСТЬ И ФАЗЫ ПАРТИИ ====================

    @Test
    fun summarizeSide_separatesPlayerAndOpponentAccuracyAndErrors() {
        val bilateralMoves = listOf(
            // Ход 1: оба в теории
            AnalyzedMove(1L, 1L, 0, 1, ChessColor.WHITE, MoveQuality.BOOK, "e4", null, 20, 20, 0),
            AnalyzedMove(2L, 1L, 1, 1, ChessColor.BLACK, MoveQuality.BOOK, "e5", null, 20, 20, 0),
            // Ход 2: белые точны, чёрные допускают грубый зевок (+25 -> +350)
            AnalyzedMove(3L, 1L, 2, 2, ChessColor.WHITE, MoveQuality.BEST_MOVE, "Nf3", null, 20, 25, 0),
            AnalyzedMove(4L, 1L, 3, 2, ChessColor.BLACK, MoveQuality.BLUNDER, "f6", "Nc6", 25, 350, 325),
            // Ход 3: белые наказывают, чёрные снова ошибаются (+345 -> +620)
            AnalyzedMove(5L, 1L, 4, 3, ChessColor.WHITE, MoveQuality.GREAT_MOVE, "Nxe5", null, 350, 345, 5),
            AnalyzedMove(6L, 1L, 5, 3, ChessColor.BLACK, MoveQuality.MISTAKE, "fxe5", "Qe7", 345, 620, 275),
            // Ход 4: белые играют сильнейший ход
            AnalyzedMove(7L, 1L, 6, 4, ChessColor.WHITE, MoveQuality.BEST_MOVE, "Qh5+", null, 620, 620, 0)
        )

        val whiteSummary = GameMoveClassifier.summarizeSide(bilateralMoves, ChessColor.WHITE)
        val blackSummary = GameMoveClassifier.summarizeSide(bilateralMoves, ChessColor.BLACK)

        assertTrue("White played near-perfectly: ${whiteSummary.accuracy}", whiteSummary.accuracy >= 98.0)
        assertEquals(0, whiteSummary.blunders)
        assertEquals(0, whiteSummary.mistakes)
        assertTrue(whiteSummary.averageCpLoss <= 5)

        assertTrue("Black blundered heavily: ${blackSummary.accuracy}", blackSummary.accuracy < 65.0)
        assertEquals(1, blackSummary.blunders)
        assertEquals(1, blackSummary.mistakes)
        assertTrue(blackSummary.averageCpLoss >= 250)
    }

    @Test
    fun detectGamePhases_transitionsMonotonicallyFromOpeningToMiddlegameToEndgame() {
        val openingFen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
        val middleFen = "r2q1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2Q1RK1 w - - 4 10"
        val endgameFen = "4r1k1/pp3ppp/2p5/3p4/3P4/2P2P2/PP4PP/4R1K1 w - - 0 26"
        val pawnEndgameFen = "6k1/pp3ppp/8/3p4/3P4/5P2/PP4PP/6K1 w - - 0 34"

        val fens = listOf(
            openingFen, openingFen, openingFen, openingFen,
            middleFen, middleFen, middleFen, middleFen,
            endgameFen, endgameFen, pawnEndgameFen, pawnEndgameFen
        )
        val qualities = listOf(
            MoveQuality.BOOK, MoveQuality.BOOK, MoveQuality.GOOD, MoveQuality.GOOD,
            MoveQuality.BEST_MOVE, MoveQuality.GOOD, MoveQuality.MISTAKE, MoveQuality.BEST_MOVE,
            MoveQuality.BEST_MOVE, MoveQuality.GOOD, MoveQuality.BLUNDER, MoveQuality.BEST_MOVE
        )

        val phases = GameMoveClassifier.detectGamePhases(fens, qualities)
        assertEquals(GameMoveClassifier.GamePhase.OPENING, phases[0])
        assertEquals(GameMoveClassifier.GamePhase.OPENING, phases[1])
        assertEquals(GameMoveClassifier.GamePhase.MIDDLEGAME, phases[6])
        assertEquals(GameMoveClassifier.GamePhase.ENDGAME, phases[8])
        assertEquals(GameMoveClassifier.GamePhase.ENDGAME, phases[11])

        // Монотонность: фаза никогда не откатывается назад
        for (i in 1 until phases.size) {
            assertTrue(phases[i].ordinal >= phases[i - 1].ordinal)
        }
        assertTrue(GameMoveClassifier.isPurePawnEndgame(pawnEndgameFen))
        assertNotEquals(true, GameMoveClassifier.isPurePawnEndgame(endgameFen))
    }
}
