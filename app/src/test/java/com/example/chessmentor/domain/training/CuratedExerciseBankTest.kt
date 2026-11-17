package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedExerciseBankTest {

    @Test
    fun allCuratedPuzzles_haveUniqueIdsUniqueFensAndLegalOddSolutions() {
        val puzzles = CuratedExerciseBank.puzzles
        assertTrue("Expected at least 24 curated puzzles", puzzles.size >= 24)

        val ids = puzzles.map { it.id }
        val fens = puzzles.map { it.fen }
        assertEquals("All puzzle IDs must be unique", puzzles.size, ids.toSet().size)
        assertEquals("All puzzle FENs must be unique", puzzles.size, fens.toSet().size)

        for (puzzle in puzzles) {
            assertTrue("Missing title for ${puzzle.id}", puzzle.title.isNotBlank())
            assertTrue("Missing subtitle for ${puzzle.id}", puzzle.subtitle.isNotBlank())
            assertTrue(
                "Difficulty out of bounds for ${puzzle.id}: ${puzzle.difficultyRating}",
                puzzle.difficultyRating in 1..100
            )
            assertTrue("Empty solution for ${puzzle.id}", puzzle.solutionMoves.isNotEmpty())
            assertEquals(
                "Solution must have odd ply count (ending on player's move) for ${puzzle.id}",
                1,
                puzzle.solutionMoves.size % 2
            )

            val board = Board().apply { loadFromFen(puzzle.fen) }
            val startSide = board.sideToMove
            assertEquals(
                "isWhiteToMove mismatch for ${puzzle.id}",
                startSide == Side.WHITE,
                puzzle.isWhiteToMove
            )
            assertFalse(
                "Puzzle ${puzzle.id} cannot start in checkmate/stalemate",
                board.legalMoves().isEmpty()
            )

            for ((plyIndex, uci) in puzzle.solutionMoves.withIndex()) {
                val parsed = ChessMoveUtils.parseUciMove(board, uci)
                assertNotNull(
                    "Illegal move '$uci' at ply $plyIndex in puzzle '${puzzle.id}' (FEN: ${board.fen})",
                    parsed
                )
                assertTrue(
                    "Failed applying '$uci' at ply $plyIndex in puzzle '${puzzle.id}'",
                    board.doMove(parsed!!)
                )
            }

            // Финальный ход сделал игрок — очередь хода перешла к сопернику
            assertEquals(
                "Final position must be opponent's turn in ${puzzle.id}",
                startSide.flip(),
                board.sideToMove
            )

            if (puzzle.theme == TacticalTheme.MATE) {
                assertTrue(
                    "MATE puzzle '${puzzle.id}' must end in checkmate",
                    board.isMated
                )
            }

            if (puzzle.preFen != null || puzzle.prevMoveUci != null) {
                assertTrue(
                    "Invalid opponent trigger transition (${puzzle.preFen} -> ${puzzle.prevMoveUci}) for '${puzzle.id}'",
                    SolutionReviewHelper.validateTriggerTransition(
                        preFen = puzzle.preFen,
                        prevMoveUci = puzzle.prevMoveUci,
                        targetFen = puzzle.fen
                    )
                )
            }
        }
    }

    @Test
    fun curatedBank_coversAllSelectableThemesAndBothSides() {
        val puzzles = CuratedExerciseBank.puzzles
        val presentThemes = puzzles.map { it.theme }.toSet()

        for (theme in TrainingMode.selectableThemes) {
            assertTrue(
                "Missing curated puzzles for theme $theme",
                theme in presentThemes
            )
        }

        assertTrue("Expected White-to-move puzzles", puzzles.any { it.isWhiteToMove })
        assertTrue("Expected Black-to-move puzzles", puzzles.any { !it.isWhiteToMove })
    }

    @Test
    fun underpromotionPuzzle_promotesExplicitlyToKnight() {
        val puzzle = CuratedExerciseBank.findTemplateById("underpromotion_knight_fork")
        assertNotNull(puzzle)

        val board = Board().apply { loadFromFen(puzzle!!.fen) }
        val firstMove = ChessMoveUtils.parseUciMove(board, puzzle.solutionMoves.first())
        assertNotNull(firstMove)
        assertEquals(PieceType.KNIGHT, firstMove!!.promotion.pieceType)
    }

    @Test
    fun toExercise_createsSharedEntityWithMetadata() {
        val template = CuratedExerciseBank.puzzles.first()
        val exercise = template.toExercise()

        assertNull("Curated exercise must be shared (sourceGameId == null)", exercise.sourceGameId)
        assertNull(exercise.sourceMoveNumber)
        assertEquals(ExerciseSource.IMPORTED, exercise.createdFrom)
        assertEquals(template.id, ExerciseMetadata.extractCuratedId(exercise))
        assertEquals(template.title, ExerciseMetadata.extractTitle(exercise))
        assertEquals(
            TrainingRatingCalculator.difficultyToElo(template.difficultyRating),
            exercise.rating
        )
    }

    @Test
    fun getDailyPuzzle_isDeterministicAndCyclesWithoutDuplicates() {
        val total = CuratedExerciseBank.puzzles.size
        val startDay = 20_774L // 2026-11-17

        assertEquals(
            CuratedExerciseBank.getDailyPuzzle(startDay),
            CuratedExerciseBank.getDailyPuzzle(startDay)
        )

        val cycleIds = (0 until total)
            .map { offset -> CuratedExerciseBank.getDailyPuzzle(startDay + offset).id }
            .toSet()
        assertEquals("Daily puzzle should cover all $total puzzles in one cycle", total, cycleIds.size)

        // Безопасная работа даже для отрицательных значений epochDay
        assertNotNull(CuratedExerciseBank.getDailyPuzzle(-15L))
    }
}
