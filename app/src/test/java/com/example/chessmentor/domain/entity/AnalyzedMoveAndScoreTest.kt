package com.example.chessmentor.domain.entity

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.opening.BookMoveMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzedMoveAndScoreTest {

    @Test
    fun chessEngine_mateHelpersAndLocaleSafeFormatting() {
        val mateIn3White = ChessEngine.MATE_VALUE - 3 * 100
        val mateIn2Black = -(ChessEngine.MATE_VALUE - 2 * 100)

        assertTrue(ChessEngine.isMateScore(mateIn3White))
        assertTrue(ChessEngine.isMateScore(mateIn2Black))
        assertTrue(ChessEngine.isMateScore(ChessEngine.MATE_THRESHOLD))

        assertEquals(3, ChessEngine.getMateInMoves(mateIn3White))
        assertEquals(-2, ChessEngine.getMateInMoves(mateIn2Black))
        assertEquals("+M3", ChessEngine.formatEvaluation(mateIn3White))
        assertEquals("-M2", ChessEngine.formatEvaluation(mateIn2Black))
        assertEquals("+1.5", ChessEngine.formatEvaluation(150))
        assertEquals("-0.8", ChessEngine.formatEvaluation(-80))
    }

    @Test
    fun analyzedMove_playerEvalChangeRespectsBlackPerspective() {
        // Оценка изменилась с +50 до -150: для чёрных это улучшение на +200cp
        val blackGreatMove = AnalyzedMove(
            gameId = 1L,
            moveIndex = 11,
            moveNumber = 6,
            color = ChessColor.BLACK,
            quality = MoveQuality.GREAT_MOVE,
            san = "Nxe4",
            bestMove = null,
            evalBefore = 50,
            evalAfter = -150,
            evalLoss = 0
        )

        assertEquals(200, blackGreatMove.playerEvalChange)
        assertEquals(200, blackGreatMove.toKeyMoment().evaluationChange)
    }

    @Test
    fun analyzedMove_toMistakeReturnsNullWhenBestMoveMissingOrSame() {
        val noBestMove = AnalyzedMove(
            gameId = 1L,
            moveIndex = 4,
            moveNumber = 3,
            color = ChessColor.WHITE,
            quality = MoveQuality.BLUNDER,
            san = "Qh5",
            bestMove = null,
            evalBefore = 20,
            evalAfter = -350,
            evalLoss = 370
        )
        assertNull(noBestMove.toMistake())

        val validMistake = noBestMove.copy(bestMove = "Nf3").toMistake(
            themeId = PredefinedThemes.FORK.id!!,
            fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
        assertNotNull(validMistake)
        assertEquals(MistakeType.BLUNDER, validMistake?.mistakeType)
        assertEquals("Nf3", validMistake?.bestMove)
    }

    @Test
    fun predefinedThemes_allHaveUniqueNonNullIds() {
        val all = PredefinedThemes.getAll()
        val ids = all.mapNotNull { it.id }
        assertEquals(all.size, ids.size)
        assertEquals(all.size, ids.toSet().size)
    }

    @Test
    fun analyzedMove_toKeyMomentMarksPlayerVsOpponentAccurately() {
        val opponentBlunder = AnalyzedMove(
            gameId = 1L,
            moveIndex = 9,
            moveNumber = 5,
            color = ChessColor.BLACK,
            quality = MoveQuality.BLUNDER,
            san = "Nf6",
            bestMove = "Be7",
            evalBefore = 30,
            evalAfter = 340,
            evalLoss = 310
        )

        val forWhitePlayer = opponentBlunder.toKeyMoment(ChessColor.WHITE)
        val forBlackPlayer = opponentBlunder.toKeyMoment(ChessColor.BLACK)

        assertFalse(forWhitePlayer.isPlayerMove)
        assertTrue(forBlackPlayer.isPlayerMove)
        assertEquals(-310, forWhitePlayer.evaluationChange)
    }

    @Test
    fun gameMoveClassifier_returnsBookOnlyWhenMatchedInOpeningTree() {
        val matchedBook = GameMoveClassifier.classifyMove(
            evalBefore = 20,
            evalAfter = 15,
            movingColor = ChessColor.WHITE,
            moveNumber = 3,
            inBookSequence = true,
            bookMatch = BookMoveMatch(
                opening = null,
                eco = "C50",
                openingName = "Итальянская партия",
                isTabiya = true
            )
        )
        assertEquals(MoveQuality.BOOK, matchedBook.quality)
        assertEquals(100.0, matchedBook.moveAccuracy, 0.001)

        // Вне дебютной книги ход 3-го номера НЕ должен автоматически считаться теорией
        val offBookEarlyMove = GameMoveClassifier.classifyMove(
            evalBefore = 20,
            evalAfter = -55,
            movingColor = ChessColor.WHITE,
            moveNumber = 3,
            inBookSequence = false,
            bookMatch = null
        )
        assertNotEquals(MoveQuality.BOOK, offBookEarlyMove.quality)
        assertEquals(MoveQuality.INACCURACY, offBookEarlyMove.quality)
    }
}
