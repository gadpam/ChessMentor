// domain/clustering/ExerciseClusterer.kt
package com.example.chessmentor.domain.clustering

import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Генерация идентификаторов кластеров для группировки похожих задач
 */
object ExerciseClusterer {

    /**
     * Создать ID кластера для упражнения
     *
     * Формат: "{pattern}_{pieceType}_{difficulty}_{feature}"
     * Пример: "FORK_KNIGHT_MEDIUM_WITH_CHECK"
     */
    fun generateClusterId(
        board: Board,
        pattern: TacticalPattern,
        solutionMove: String,
        difficultyRating: Int
    ): String {
        val parts = mutableListOf<String>()

        // 1. Основной паттерн
        parts.add(pattern.name)

        // 2. Тип фигуры (для вилок, связок)
        val pieceType = extractPieceType(board, solutionMove)
        if (pieceType != null && shouldIncludePieceType(pattern)) {
            parts.add(pieceType.name)
        }

        // 3. Сложность
        parts.add(getDifficultyBucket(difficultyRating))

        // 4. Особенности
        val features = extractFeatures(board, pattern, solutionMove)
        if (features.isNotEmpty()) {
            parts.add(features.first())
        }

        return parts.joinToString("_")
    }

    /**
     * Извлечь особенности позиции для более точной кластеризации
     */
    fun extractFeatures(
        board: Board,
        pattern: TacticalPattern,
        solutionMove: String
    ): List<String> {
        val features = mutableListOf<String>()

        if (solutionMove.length < 4) return features

        // Проверяем: есть ли шах в решении?
        val testBoard = board.clone()
        applyUciMove(testBoard, solutionMove)
        if (testBoard.isKingAttacked) {
            features.add("WITH_CHECK")
        }

        // Проверяем: есть ли взятие?
        val targetSquare = solutionMove.substring(2, 4)
        try {
            val capturedPiece = board.getPiece(Square.fromValue(targetSquare.uppercase()))
            if (capturedPiece != Piece.NONE) {
                features.add("WITH_CAPTURE")
                features.add("WINS_${capturedPiece.pieceType.name}")
            }
        } catch (e: Exception) {
            // Игнорируем ошибки парсинга
        }

        // Специфичные для паттерна
        when (pattern) {
            TacticalPattern.FORK_KNIGHT,
            TacticalPattern.FORK_BISHOP,
            TacticalPattern.FORK_ROOK,
            TacticalPattern.FORK_QUEEN,
            TacticalPattern.FORK_PAWN -> {
                // Проверяем семейную вилку (король + ферзь)
                if (isFamilyFork(testBoard, solutionMove)) {
                    features.add("FAMILY_FORK")
                }
            }

            TacticalPattern.PIN_RELATIVE,
            TacticalPattern.PIN_ABSOLUTE -> {
                val pinnedTo = detectPinnedTo(testBoard, solutionMove)
                if (pinnedTo != null) {
                    features.add("TO_${pinnedTo.name}")
                }
            }

            TacticalPattern.BACK_RANK_MATE,
            TacticalPattern.SMOTHERED_MATE -> {
                features.add("MATE_PATTERN")
            }

            else -> {}
        }

        return features
    }

    /**
     * Вычислить сложность задачи
     */
    fun calculateDifficulty(
        board: Board,
        pattern: TacticalPattern,
        solutionMoves: List<String>,
        materialGain: Int
    ): Int {
        var difficulty = 50 // Базовая сложность

        // 1. Длина решения
        difficulty += solutionMoves.size * 10

        // 2. Сложность паттерна
        difficulty += getPatternDifficulty(pattern)

        // 3. Материальный выигрыш (чем больше, тем очевиднее)
        difficulty -= getMaterialBonus(materialGain)

        // 4. Количество фигур на доске (чем больше, тем сложнее найти)
        val pieceCount = countPieces(board)
        difficulty += (pieceCount - 20) / 2

        return difficulty.coerceIn(0, 100)
    }

    // ================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ================================================================

    private fun getPatternDifficulty(pattern: TacticalPattern): Int {
        return when (pattern) {
            TacticalPattern.HANGING_PIECE -> -10
            TacticalPattern.FORK_PAWN -> -5
            TacticalPattern.FORK_KNIGHT -> 0
            TacticalPattern.FORK_BISHOP -> 5
            TacticalPattern.FORK_ROOK -> 5
            TacticalPattern.FORK_QUEEN -> 5
            TacticalPattern.PIN_RELATIVE -> 10
            TacticalPattern.PIN_ABSOLUTE -> 10
            TacticalPattern.SKEWER -> 10
            TacticalPattern.DISCOVERED_ATTACK -> 15
            TacticalPattern.DISCOVERED_CHECK -> 15
            TacticalPattern.DOUBLE_CHECK -> 20
            TacticalPattern.BACK_RANK_MATE -> 15
            TacticalPattern.SMOTHERED_MATE -> 25
            TacticalPattern.REMOVING_DEFENDER -> 15
            TacticalPattern.DEFLECTION -> 20
            TacticalPattern.DECOY -> 20
            TacticalPattern.OVERLOADING -> 20
            TacticalPattern.TRAPPED_PIECE -> 15
            TacticalPattern.QUEEN_TRAP -> 20
            else -> 5
        }
    }

    private fun getMaterialBonus(materialGain: Int): Int {
        return when {
            materialGain >= 900 -> 15  // Ферзь
            materialGain >= 500 -> 10  // Ладья
            materialGain >= 300 -> 5   // Лёгкая фигура
            materialGain >= 100 -> 2   // Пешка
            else -> 0
        }
    }

    private fun shouldIncludePieceType(pattern: TacticalPattern): Boolean {
        return pattern in listOf(
            TacticalPattern.FORK_KNIGHT,
            TacticalPattern.FORK_BISHOP,
            TacticalPattern.FORK_ROOK,
            TacticalPattern.FORK_QUEEN,
            TacticalPattern.FORK_PAWN,
            TacticalPattern.PIN_RELATIVE,
            TacticalPattern.PIN_ABSOLUTE,
            TacticalPattern.SKEWER
        )
    }

    private fun getDifficultyBucket(rating: Int): String {
        return when {
            rating < 30 -> "EASY"
            rating < 60 -> "MEDIUM"
            else -> "HARD"
        }
    }

    private fun extractPieceType(board: Board, solutionMove: String): PieceType? {
        if (solutionMove.length < 4) return null
        return try {
            val fromSquare = Square.fromValue(solutionMove.substring(0, 2).uppercase())
            val piece = board.getPiece(fromSquare)
            if (piece != Piece.NONE) piece.pieceType else null
        } catch (e: Exception) {
            null
        }
    }

    private fun applyUciMove(board: Board, uci: String) {
        ChessMoveUtils.applyUciMove(board, uci)
    }

    /**
     * Проверка семейной вилки (атакованы король и ферзь)
     */
    private fun isFamilyFork(board: Board, move: String): Boolean {
        if (move.length < 4) return false

        try {
            val toSquare = Square.fromValue(move.substring(2, 4).uppercase())
            val piece = board.getPiece(toSquare)
            if (piece == Piece.NONE) return false

            val attackerSide = piece.pieceSide
            val opponentSide = attackerSide.flip()

            // Получаем атакованные поля
            val attackedSquares = getAttackedSquares(board, toSquare, piece.pieceType, attackerSide)

            var hasKing = false
            var hasQueen = false

            for (sq in attackedSquares) {
                val target = board.getPiece(sq)
                if (target != Piece.NONE && target.pieceSide == opponentSide) {
                    when (target.pieceType) {
                        PieceType.KING -> hasKing = true
                        PieceType.QUEEN -> hasQueen = true
                        else -> {}
                    }
                }
            }

            return hasKing && hasQueen
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Определить к какой фигуре связка
     */
    private fun detectPinnedTo(board: Board, move: String): PieceType? {
        // Упрощённая реализация: ищем ценную фигуру за связанной
        // Полная реализация требует анализа линий
        return null
    }

    /**
     * Подсчёт фигур на доске
     */
    private fun countPieces(board: Board): Int {
        var count = 0
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            if (board.getPiece(sq) != Piece.NONE) count++
        }
        return count
    }

    /**
     * Получить атакованные поля фигурой
     */
    private fun getAttackedSquares(
        board: Board,
        from: Square,
        pieceType: PieceType,
        side: com.github.bhlangonijr.chesslib.Side
    ): Set<Square> {
        val attacks = mutableSetOf<Square>()

        when (pieceType) {
            PieceType.KNIGHT -> {
                val offsets = listOf(
                    -2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1
                )
                for ((df, dr) in offsets) {
                    val newFile = from.file.ordinal + df
                    val newRank = from.rank.ordinal + dr
                    if (newFile in 0..7 && newRank in 0..7) {
                        attacks.add(Square.squareAt(newRank * 8 + newFile))
                    }
                }
            }

            PieceType.BISHOP -> {
                val directions = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
                attacks.addAll(getSlidingAttacks(board, from, directions))
            }

            PieceType.ROOK -> {
                val directions = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
                attacks.addAll(getSlidingAttacks(board, from, directions))
            }

            PieceType.QUEEN -> {
                val directions = listOf(
                    -1 to -1, -1 to 1, 1 to -1, 1 to 1,
                    0 to -1, 0 to 1, -1 to 0, 1 to 0
                )
                attacks.addAll(getSlidingAttacks(board, from, directions))
            }

            PieceType.PAWN -> {
                val direction = if (side == com.github.bhlangonijr.chesslib.Side.WHITE) 1 else -1
                val rank = from.rank.ordinal + direction
                val file = from.file.ordinal

                if (rank in 0..7) {
                    if (file > 0) attacks.add(Square.squareAt(rank * 8 + file - 1))
                    if (file < 7) attacks.add(Square.squareAt(rank * 8 + file + 1))
                }
            }

            PieceType.KING -> {
                val directions = listOf(
                    -1 to -1, -1 to 0, -1 to 1,
                    0 to -1, 0 to 1,
                    1 to -1, 1 to 0, 1 to 1
                )
                for ((df, dr) in directions) {
                    val newFile = from.file.ordinal + df
                    val newRank = from.rank.ordinal + dr
                    if (newFile in 0..7 && newRank in 0..7) {
                        attacks.add(Square.squareAt(newRank * 8 + newFile))
                    }
                }
            }

            else -> {}
        }

        return attacks
    }

    private fun getSlidingAttacks(
        board: Board,
        from: Square,
        directions: List<Pair<Int, Int>>
    ): Set<Square> {
        val attacks = mutableSetOf<Square>()

        for ((df, dr) in directions) {
            var file = from.file.ordinal + df
            var rank = from.rank.ordinal + dr

            while (file in 0..7 && rank in 0..7) {
                val sq = Square.squareAt(rank * 8 + file)
                attacks.add(sq)

                if (board.getPiece(sq) != Piece.NONE) break

                file += df
                rank += dr
            }
        }

        return attacks
    }
}