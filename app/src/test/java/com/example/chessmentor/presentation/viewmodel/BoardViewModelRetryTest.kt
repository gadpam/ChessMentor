package com.example.chessmentor.presentation.viewmodel

import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardViewModelRetryTest {

    private val blunderPgn = """
        [Event "Test"]
        [White "Opponent"]
        [Black "Hero"]
        [Result "*"]

        1. e4 e5 2. Bc4 Nf6 3. Bxf7+ Ke7 *
    """.trimIndent()

    private val blunderEvaluations = listOf(20, 20, 25, 25, 10, -340, 20)

    private fun testGame(): Game = Game(
        id = 10L,
        userId = 1L,
        pgnData = blunderPgn,
        playerColor = ChessColor.BLACK,
        playedAt = 1_700_000_000_000L,
        analysisStatus = AnalysisStatus.COMPLETED
    )

    @Test
    fun opponentBlunderRetry_startsAfterOpponentMove_withPlayerInCheckAndCorrectEvaluation() {
        val vm = BoardViewModel()
        val opponentBlunder = AnalyzedMove(
            gameId = 10L,
            moveIndex = 4,
            moveNumber = 3,
            color = ChessColor.WHITE,
            quality = MoveQuality.BLUNDER,
            san = "Bxf7+",
            bestMove = "Nf3",
            evalBefore = 10,
            evalAfter = -340,
            evalLoss = 350,
            comment = "Зевок слона. Сильнее: 3. Nf3. Опровержение: 3... Kxf7."
        )
        val heroMiss = AnalyzedMove(
            gameId = 10L,
            moveIndex = 5,
            moveNumber = 3,
            color = ChessColor.BLACK,
            quality = MoveQuality.BLUNDER,
            san = "Ke7",
            bestMove = "Kxf7",
            evalBefore = -340,
            evalAfter = 20,
            evalLoss = 360,
            comment = "Сильнее: 3... Kxf7."
        )

        vm.loadGame(
            game = testGame(),
            gameMistakes = emptyList(),
            gameAnalyzedMoves = listOf(opponentBlunder, heroMiss),
            gameEvaluations = blunderEvaluations
        )

        // Переходим на ход 3. Bxf7+ соперника (индекс 4)
        vm.goToMove(4)
        assertTrue(vm.canRetryCurrentMove())

        vm.startRetryCurrentMove()
        val retry = vm.retryState.value
        assertNotNull(retry)
        assertFalse(retry!!.isPlayerMistake)
        assertNotNull(retry.skillProfile)
        // После 3. Bxf7+?? у чёрных +3.40 — они наказывают зевок (атакующая/расчётная роль, не защита)
        assertFalse(retry.skillProfile!!.isDefensive)
        assertEquals(TacticalPattern.CALCULATION, retry.skillProfile!!.pattern)
        assertEquals(TacticalTheme.TACTIC, retry.skillProfile!!.theme)
        assertEquals(4, retry.targetMoveIndex)
        assertEquals(3, retry.moveNumber)
        assertEquals("Kxf7", retry.expectedBestSan)
        assertEquals("e8f7", retry.expectedBestRaw)
        assertEquals("Ke7", retry.playedMistakeNotation)
        // На доске уже применён ход соперника 3. Bxf7+, очередь чёрных и король под шахом
        assertEquals(Side.BLACK, vm.board.value.sideToMove)
        assertTrue(vm.board.value.isKingAttacked)
        assertEquals(Square.C4 to Square.F7, vm.lastMove.value)
        // Оценка позиции во время поиска ответа берётся ПОСЛЕ зевка соперника (-340), а не до (+10)
        assertEquals(-340, vm.getCurrentEvaluation())

        // Повтор ошибочного ответа 3... Ke7 не проходит
        val badVerdict = vm.submitRetryMove(Move(Square.E8, Square.E7))
        assertEquals(GameReviewHelper.RetryVerdict.SAME_AS_GAME_MISTAKE, badVerdict)
        assertFalse(vm.retryState.value!!.isSolved)

        // Нахождение опровержения 3... Kxf7 решает задание
        val goodVerdict = vm.submitRetryMove(Move(Square.E8, Square.F7))
        assertEquals(GameReviewHelper.RetryVerdict.EXACT_BEST, goodVerdict)
        assertTrue(vm.retryState.value!!.isSolved)
        assertEquals(Square.E8 to Square.F7, vm.lastMove.value)

        // Выход с продолжением переводит к следующему ходу партии (индекс 5)
        vm.exitRetryMode(advanceToNext = true)
        assertNull(vm.retryState.value)
        assertEquals(5, vm.currentMoveIndex.intValue)
    }

    @Test
    fun opponentMistakeWithoutVerifiedContinuation_disablesRetry() {
        val vm = BoardViewModel()
        val unverifiedOpponentInaccuracy = AnalyzedMove(
            gameId = 10L,
            moveIndex = 4,
            moveNumber = 3,
            color = ChessColor.WHITE,
            quality = MoveQuality.INACCURACY,
            san = "Bxf7+",
            bestMove = "Nf3",
            evalBefore = 10,
            evalAfter = -80,
            evalLoss = 90,
            comment = "Неточность (−0.9). Сильнее: 3. Nf3."
        )

        vm.loadGame(
            game = testGame(),
            gameMistakes = emptyList(),
            gameAnalyzedMoves = listOf(unverifiedOpponentInaccuracy),
            gameEvaluations = blunderEvaluations
        )

        vm.goToMove(4)
        assertFalse(vm.canRetryCurrentMove())
        vm.startRetryCurrentMove()
        assertNull(vm.retryState.value)
    }
}
