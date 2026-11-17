package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.TacticalPattern
import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolutionReviewTest {

    @Test
    fun validateTriggerTransition_verifiesLegalOpponentMoveAndRejectsIllegal() {
        val preFen = "4kb1r/p2nqppp/8/4p1B1/4P3/1Q6/PPP2PPP/2KR4 b - - 0 1"
        val targetFen = "4kb1r/p2n1ppp/4q3/4p1B1/4P3/1Q6/PPP2PPP/2KR4 w - - 0 1"

        assertTrue(SolutionReviewHelper.validateTriggerTransition(preFen, "e7e6", targetFen))
        assertFalse(SolutionReviewHelper.validateTriggerTransition(preFen, "e7e5", targetFen))
        assertFalse(SolutionReviewHelper.validateTriggerTransition(null, "e7e6", targetFen))
    }

    @Test
    fun buildReviewSteps_andNumberedSanLine_coverStartAndEveryPly() {
        val operaFen = "4kb1r/p2n1ppp/4q3/4p1B1/4P3/1Q6/PPP2PPP/2KR4 w - - 0 1"
        val solution = listOf("b3b8", "d7b8", "d1d8")

        val steps = SolutionReviewHelper.buildReviewSteps(operaFen, solution, initialPrevMoveUci = "e7e6")
        assertEquals(4, steps.size)
        assertEquals("Старт", steps[0].moveLabel)
        assertEquals("e7e6", steps[0].lastMoveUci)
        assertFalse(steps[0].isPlayerMove)

        assertTrue(steps[1].isPlayerMove)
        assertEquals("b3b8", steps[1].lastMoveUci)
        assertFalse(steps[2].isPlayerMove)
        assertEquals("d7b8", steps[2].lastMoveUci)
        assertTrue(steps[3].isPlayerMove)
        assertEquals("d1d8", steps[3].lastMoveUci)

        val sanLine = SolutionReviewHelper.formatNumberedSanLine(operaFen, solution)
        assertTrue("Expected Qb8+ and Rd8# in '$sanLine'", sanLine.contains("Qb8+") && sanLine.contains("Rd8#"))
    }

    @Test
    fun buildPostSolveExplanation_andNeutralPrompt_coverAllPatternsWithoutSpoilers() {
        val prompt = SolutionReviewHelper.buildNeutralPrompt(
            playerSide = Side.WHITE,
            hasOpponentTriggerMove = true,
            isDefensiveGoal = false
        )
        assertFalse("Neutral prompt must not spoil move count", prompt.contains("в 2") || prompt.contains("мат"))

        for (pattern in TacticalPattern.entries) {
            val cue = SolutionReviewHelper.patternCoachingCue(pattern)
            assertTrue("Missing coaching cue for $pattern", cue.length >= 25)
        }
    }
}
