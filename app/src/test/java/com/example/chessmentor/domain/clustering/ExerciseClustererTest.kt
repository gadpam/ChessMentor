package com.example.chessmentor.domain.clustering

import com.example.chessmentor.domain.entity.TacticalPattern.*
import com.github.bhlangonijr.chesslib.Board
import org.junit.Assert.*
import org.junit.Test

class ExerciseClustererTest {
    // Nd6+ attacks the king on e8 and the queen on b7, capturing a rook.
    private fun forkPosition() = Board().apply { loadFromFen("4k3/1q6/3r4/5N2/8/8/8/K7 w - - 0 1") }

    @Test fun checkingCaptureCanAlsoBeAFamilyFork() {
        val features = ExerciseClusterer.extractFeatures(forkPosition(), FORK_KNIGHT, "f5d6")
        assertEquals(setOf("WITH_CHECK", "WITH_CAPTURE", "WINS_ROOK", "FAMILY_FORK"), features.toSet())
    }

    @Test fun checkingOnlyTheKingIsNotAFamilyFork() {
        val board = Board().apply { loadFromFen("4k3/8/8/5N2/8/8/8/K7 w - - 0 1") }
        assertEquals(listOf("WITH_CHECK"), ExerciseClusterer.extractFeatures(board, FORK_KNIGHT, "f5d6"))
    }

    @Test fun analysisDoesNotPlayTheSolutionOnTheCallersBoard() {
        val board = forkPosition()
        val before = board.fen
        val cluster = ExerciseClusterer.generateClusterId(board, FORK_KNIGHT, "f5d6", 45)
        assertEquals("FORK_KNIGHT_KNIGHT_MEDIUM_WITH_CHECK", cluster)
        assertEquals(before, board.fen)
    }

    @Test fun quietMoveUsesDifficultyBoundariesWithoutCaptureOrCheckSuffixes() {
        val board = Board()
        for ((rating, bucket) in listOf(29 to "EASY", 30 to "MEDIUM", 59 to "MEDIUM", 60 to "HARD")) {
            assertEquals("HANGING_PIECE_$bucket", ExerciseClusterer.generateClusterId(board, HANGING_PIECE, "e2e4", rating))
        }
    }

    @Test fun largerMaterialRewardMakesOtherwiseIdenticalPuzzlesEasier() {
        val board = forkPosition()
        val scores = listOf(0, 100, 300, 500, 900).map {
            ExerciseClusterer.calculateDifficulty(board, FORK_KNIGHT, listOf("f5d6"), it)
        }
        assertTrue(scores.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun deeperLinesIncreaseDifficultyButNeverExceedTheRatingScale() {
        fun score(length: Int) = ExerciseClusterer.calculateDifficulty(Board(), FORK_KNIGHT, List(length) { "g1f3" }, 0)
        assertTrue(score(1) < score(2))
        assertEquals(100, score(20))
    }
}
