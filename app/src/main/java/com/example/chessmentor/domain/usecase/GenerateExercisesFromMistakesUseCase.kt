// domain/usecase/GenerateExercisesFromMistakesUseCase.kt
package com.example.chessmentor.domain.usecase

import android.util.Log
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.SemanticMistakeAnalyzer
import com.example.chessmentor.domain.analysis.TacticalMotif
import com.example.chessmentor.domain.clustering.ExerciseClusterer
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Use Case: Генерация тренировочных задач с кластеризацией из ошибок пользователя.
 */
class GenerateExercisesFromMistakesUseCase(
    private val mistakeRepository: MistakeRepository,
    @Suppress("unused")
    private val analyzedMoveRepository: AnalyzedMoveRepository,
    private val exerciseRepository: ExerciseRepository,
    private val gameRepository: GameRepository,
    private val chessEngine: ChessEngine
) {
    companion object {
        private const val TAG = "GenerateExercisesUC"
        private const val SOLUTION_DEPTH = 12
        private const val MAX_SOLUTION_PLIES = 5
    }

    private val semanticAnalyzer = SemanticMistakeAnalyzer(chessEngine)

    data class Input(
        val userId: Long,
        val maxExercises: Int = 50
    )

    sealed class Result {
        data class Success(
            val generatedCount: Int,
            val exercises: List<Exercise>
        ) : Result()

        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result = withContext(Dispatchers.IO) {
        if (input.userId <= 0L || input.maxExercises <= 0) {
            return@withContext Result.Success(0, emptyList())
        }

        try {
            val games = gameRepository.findByUserId(input.userId)
            val gameIds = games.mapNotNull { it.id }
            if (gameIds.isEmpty()) {
                return@withContext Result.Success(0, emptyList())
            }

            // Приоритет: сначала зевки и серьёзные ошибки, затем неточности
            val mistakes = mistakeRepository.findByGameIds(gameIds)
                .filter { !it.fenBefore.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<Mistake> { it.getSeverity() }
                        .thenByDescending { it.evaluationLoss }
                )

            if (mistakes.isEmpty()) {
                return@withContext Result.Success(0, emptyList())
            }

            val engineReady = runCatching { chessEngine.init() }.getOrDefault(false)
            val created = mutableListOf<Exercise>()

            for (mistake in mistakes) {
                coroutineContext.ensureActive()
                if (created.size >= input.maxExercises) break

                val fen = mistake.fenBefore?.trim().orEmpty()
                if (fen.isEmpty()) continue

                // Пропускаем уже созданные задачи по (gameId, moveNumber) или по FEN
                if (exerciseRepository.findBySourceGameAndMove(mistake.gameId, mistake.moveNumber) != null) {
                    continue
                }
                if (exerciseRepository.findByFen(fen) != null) {
                    continue
                }

                val exercise = buildExerciseFromMistake(mistake, fen, engineReady) ?: continue
                val saved = exerciseRepository.saveExercise(exercise)
                created.add(saved)
            }

            if (created.isNotEmpty()) {
                exerciseRepository.deleteDuplicates()
                Log.i(TAG, "Generated ${created.size} exercises for user ${input.userId}")
            }

            Result.Success(created.size, created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate exercises", e)
            Result.Error("Ошибка генерации упражнений: ${e.message}")
        }
    }

    private suspend fun buildExerciseFromMistake(
        mistake: Mistake,
        fen: String,
        engineReady: Boolean
    ): Exercise? {
        val board = try {
            Board().apply { loadFromFen(fen) }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping invalid FEN in mistake ${mistake.id}")
            return null
        }

        // Получаем проверенную линию решения в формате UCI
        val solutionMoves = resolveSolutionMoves(board, fen, mistake.bestMove, engineReady)
        if (solutionMoves.isEmpty()) return null

        val firstMoveUci = solutionMoves.first()
        val firstMove = ChessMoveUtils.parseUciMove(board, firstMoveUci) ?: return null
        val movingPiece = board.getPiece(firstMove.from)

        val motif = semanticAnalyzer.detectMotif(board, firstMoveUci)
        val pattern = classifyPattern(board, firstMove, movingPiece, motif, mistake)
        val theme = mapPatternToTheme(pattern)

        val difficulty = ExerciseClusterer.calculateDifficulty(
            board = board,
            pattern = pattern,
            solutionMoves = solutionMoves,
            materialGain = mistake.evaluationLoss.coerceAtMost(1200)
        )
        val clusterId = ExerciseClusterer.generateClusterId(
            board = board,
            pattern = pattern,
            solutionMove = firstMoveUci,
            difficultyRating = difficulty
        )
        val features = ExerciseClusterer.extractFeatures(
            board = board,
            pattern = pattern,
            solutionMove = firstMoveUci
        )

        // Переводим сложность 0..100 в ориентировочный рейтинг Эло (800..2400)
        val estimatedElo = (800 + difficulty * 16).coerceIn(800, 2400)

        return Exercise(
            fenPosition = fen,
            solutionMoves = solutionMoves,
            tacticalPattern = pattern,
            patternClusterId = clusterId,
            difficultyRating = difficulty,
            patternFeatures = features,
            theme = theme,
            rating = estimatedElo,
            createdFrom = ExerciseSource.MISTAKE,
            sourceGameId = mistake.gameId,
            sourceMoveNumber = mistake.moveNumber
        )
    }

    private suspend fun resolveSolutionMoves(
        board: Board,
        fen: String,
        fallbackBestMove: String,
        engineReady: Boolean
    ): List<String> {
        if (engineReady) {
            try {
                val line = chessEngine.getBestMoveWithLine(fen, SOLUTION_DEPTH)
                if (line != null && line.principalVariation.isNotEmpty()) {
                    // Оставляем нечётное число полуходов (чтобы задача заканчивалась ходом игрока)
                    val validated = validateUciSequence(board, line.principalVariation.take(MAX_SOLUTION_PLIES))
                    if (validated.isNotEmpty()) {
                        return trimToPlayerLastMove(validated)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Engine PV failed, falling back to saved bestMove", e)
            }
        }

        val fallbackUci = ChessMoveUtils.sanOrUciToUci(fallbackBestMove, fen)
        return if (fallbackUci != null) listOf(fallbackUci) else emptyList()
    }

    private fun validateUciSequence(startBoard: Board, moves: List<String>): List<String> {
        val sim = Board().apply { loadFromFen(startBoard.fen) }
        val result = mutableListOf<String>()
        for (raw in moves) {
            val move = ChessMoveUtils.parseUciMove(sim, raw) ?: break
            if (!sim.doMove(move)) break
            result.add(ChessMoveUtils.moveToUci(move))
        }
        return result
    }

    private fun trimToPlayerLastMove(moves: List<String>): List<String> {
        if (moves.size <= 1) return moves
        return if (moves.size % 2 == 0) moves.dropLast(1) else moves
    }

    private fun classifyPattern(
        board: Board,
        firstMove: com.github.bhlangonijr.chesslib.move.Move,
        movingPiece: Piece,
        motif: TacticalMotif?,
        mistake: Mistake
    ): TacticalPattern {
        if (firstMove.promotion != Piece.NONE) {
            return TacticalPattern.PAWN_BREAKTHROUGH
        }

        if (motif != null) {
            if (motif == TacticalMotif.FORK) {
                return when (movingPiece.pieceType) {
                    PieceType.KNIGHT -> TacticalPattern.FORK_KNIGHT
                    PieceType.BISHOP -> TacticalPattern.FORK_BISHOP
                    PieceType.ROOK -> TacticalPattern.FORK_ROOK
                    PieceType.QUEEN -> TacticalPattern.FORK_QUEEN
                    PieceType.PAWN -> TacticalPattern.FORK_PAWN
                    else -> TacticalPattern.FORK_KNIGHT
                }
            }
            return TacticalPattern.fromMotif(motif)
        }

        return when {
            ChessEngine.isMateScore(mistake.evaluationLoss) || mistake.evaluationLoss > 50000 ->
                TacticalPattern.BACK_RANK_MATE
            board.isKingAttacked -> TacticalPattern.DEFENSIVE_MOVE
            mistake.moveNumber >= 38 -> TacticalPattern.ENDGAME_TECHNIQUE
            mistake.mistakeType == MistakeType.BLUNDER -> TacticalPattern.CALCULATION
            mistake.mistakeType == MistakeType.INACCURACY -> TacticalPattern.POSITIONAL
            else -> TacticalPattern.MIXED
        }
    }

    private fun mapPatternToTheme(pattern: TacticalPattern): TacticalTheme {
        return when (pattern) {
            TacticalPattern.FORK_KNIGHT,
            TacticalPattern.FORK_BISHOP,
            TacticalPattern.FORK_ROOK,
            TacticalPattern.FORK_QUEEN,
            TacticalPattern.FORK_PAWN -> TacticalTheme.FORK

            TacticalPattern.PIN_ABSOLUTE,
            TacticalPattern.PIN_RELATIVE,
            TacticalPattern.SKEWER -> TacticalTheme.PIN

            TacticalPattern.DISCOVERED_ATTACK,
            TacticalPattern.DISCOVERED_CHECK,
            TacticalPattern.DOUBLE_CHECK -> TacticalTheme.DISCOVERED_ATTACK

            TacticalPattern.BACK_RANK_MATE,
            TacticalPattern.SMOTHERED_MATE,
            TacticalPattern.ANASTASIA_MATE,
            TacticalPattern.ARABIAN_MATE,
            TacticalPattern.BODEN_MATE -> TacticalTheme.MATE

            TacticalPattern.HANGING_PIECE,
            TacticalPattern.TRAPPED_PIECE,
            TacticalPattern.QUEEN_TRAP -> TacticalTheme.MATERIAL_LOSS

            TacticalPattern.DEFENSIVE_MOVE -> TacticalTheme.KING_SAFETY

            else -> TacticalTheme.TACTIC
        }
    }
}
