package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTipProviderTest {

    private val user = User(id = 1L, nickname = "Tester", email = "t@test.local", passwordHash = "x", rating = 1400)

    @Test
    fun buildTip_prioritizesDueReviewsThenWeakRecentGameThenPendingGameThenDaily() {
        val pending = Game(
            id = 5L, userId = 1L, playedAt = 100L, playerColor = ChessColor.WHITE,
            pgnData = "1. e4 e5 *", analysisStatus = AnalysisStatus.PENDING
        )
        val weakAnalyzed = Game(
            id = 6L, userId = 1L, playedAt = 200L, playerColor = ChessColor.WHITE,
            pgnData = "1. e4 e5 *", analysisStatus = AnalysisStatus.COMPLETED,
            accuracy = 61.0, blundersCount = 2, totalMistakes = 4
        )

        val dueTip = CoachTipProvider.buildTip(
            user = user, games = listOf(weakAnalyzed, pending),
            dueReviewCount = 4, isDailySolved = false, epochDay = 20000L
        )
        assertEquals(CoachTipActionKind.REVIEW_WEAK_PATTERNS, dueTip.actionKind)

        val recentTip = CoachTipProvider.buildTip(
            user = user, games = listOf(weakAnalyzed, pending),
            dueReviewCount = 0, isDailySolved = false, epochDay = 20000L
        )
        assertEquals(CoachTipActionKind.PRACTICE_RECENT_GAME, recentTip.actionKind)
        assertEquals(6L, recentTip.targetGameId)

        val pendingTip = CoachTipProvider.buildTip(
            user = user, games = listOf(pending),
            dueReviewCount = 0, isDailySolved = false, epochDay = 20000L
        )
        assertEquals(CoachTipActionKind.ANALYZE_PENDING_GAME, pendingTip.actionKind)
        assertEquals(5L, pendingTip.targetGameId)

        val dailyTip = CoachTipProvider.buildTip(
            user = user, games = emptyList(),
            dueReviewCount = 0, isDailySolved = false, epochDay = 20000L
        )
        assertEquals(CoachTipActionKind.SOLVE_DAILY_PUZZLE, dailyTip.actionKind)

        val fallbackTip = CoachTipProvider.buildTip(
            user = user, games = emptyList(),
            dueReviewCount = 0, isDailySolved = true, epochDay = 20000L
        )
        assertEquals(CoachTipActionKind.NONE, fallbackTip.actionKind)
        assertTrue(fallbackTip.text.isNotBlank())
    }
}
