// domain/util/ChessMoveUtils.kt
package com.example.chessmentor.domain.util

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

/**
 * Утилиты для конвертации ходов (UCI / SAN), проверки превращения пешки и нормализации PGN.
 */
object ChessMoveUtils {

    private val UCI_REGEX = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$", RegexOption.IGNORE_CASE)
    private val FEN_TAG_REGEX = Regex("""\[\s*FEN\s+"([^"]+)"\s*\]""", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("""\[(?:\\.|[^"\]\\]|"(?:\\.|[^"\\])*")*\]""")
    private val NAG_REGEX = Regex("""\$\d+""")
    private val MOVE_SUFFIX_NAG_REGEX = Regex("""(?<=[a-h1-8OQRBN+#])[!?]+""")
    private val RESULT_END_REGEX = Regex("""(1-0|0-1|1/2-1/2|½-½|\*)\s*$""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    data class NormalizedPgn(
        val document: String,
        val movesText: String,
        val startFen: String?
    )

    fun isUciMove(text: String): Boolean = UCI_REGEX.matches(text.trim())

    fun moveToUci(move: Move): String = move.toString().lowercase()

    fun pieceValue(type: PieceType?): Int = when (type) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 300
        PieceType.BISHOP -> 320
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        PieceType.KING -> 20000
        else -> 0
    }

    /**
     * Строгая FIDE-проверка легальности хода (включая геометрию фигуры, блокировки,
     * рокировку через битое поле, взятие на проходе и превращение).
     * В отличие от `Board.isMoveLegal`, не ограничивается проверкой шаха своему королю.
     */
    fun isStrictlyLegal(board: Board, move: Move): Boolean {
        return try {
            board.legalMoves().contains(move)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Разбирает UCI-ход (включая превращение, например "e7e8q") с проверкой легальности.
     */
    fun parseUciMove(board: Board, uci: String): Move? {
        val trimmed = uci.trim().lowercase()
        if (!isUciMove(trimmed)) return null
        return try {
            val move = Move(trimmed, board.sideToMove)
            if (isStrictlyLegal(board, move)) move else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Разбирает клетки UCI-хода без проверки доски (для рисования стрелок).
     */
    fun parseUciSquares(uci: String): Pair<Square, Square>? {
        val trimmed = uci.trim()
        if (!isUciMove(trimmed)) return null
        return try {
            val from = Square.fromValue(trimmed.substring(0, 2).uppercase())
            val to = Square.fromValue(trimmed.substring(2, 4).uppercase())
            if (from == Square.NONE || to == Square.NONE) null else from to to
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Безопасно применяет UCI-ход (с учётом превращения пешки) к доске.
     */
    fun applyUciMove(board: Board, uci: String): Boolean {
        val move = parseUciMove(board, uci) ?: return false
        return try {
            board.doMove(move)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Конвертирует UCI-ход в SAN для заданной FEN-позиции (например, "g1f3" -> "Nf3").
     */
    fun uciToSan(uci: String, fen: String): String {
        val trimmed = uci.trim().lowercase()
        if (trimmed.isEmpty()) return ""
        return try {
            val board = Board()
            board.loadFromFen(fen)
            val move = parseUciMove(board, trimmed) ?: return uci
            val moveList = MoveList(fen)
            moveList.add(move)
            moveList.toSanArray().firstOrNull()?.takeIf { it.isNotBlank() } ?: trimmed
        } catch (e: Exception) {
            trimmed
        }
    }

    /**
     * Разбирает ход как в формате UCI ("g1f3", "e7e8q"), так и в SAN ("Nf3", "O-O", "e8=Q+").
     */
    fun sanOrUciToMove(notation: String, fen: String): Move? {
        val trimmed = notation.trim()
        if (trimmed.isEmpty() || fen.isBlank()) return null

        return try {
            val board = Board()
            board.loadFromFen(fen)

            if (isUciMove(trimmed)) {
                return parseUciMove(board, trimmed)
            }

            val moveList = MoveList(fen)
            moveList.addSanMove(trimmed, true, true)
            moveList.firstOrNull()?.takeIf { isStrictlyLegal(board, it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Конвертирует SAN или UCI в нормализованный UCI ("Nf3" -> "g1f3").
     */
    fun sanOrUciToUci(notation: String, fen: String): String? {
        return sanOrUciToMove(notation, fen)?.let(::moveToUci)
    }

    /**
     * Проверяет, является ли ход (from -> to) легальным превращением пешки.
     */
    fun isPawnPromotion(board: Board, from: Square, to: Square): Boolean {
        val piece = board.getPiece(from)
        if (piece == Piece.NONE || piece.pieceType != PieceType.PAWN) return false
        val isPromotionRank = (piece.pieceSide == Side.WHITE && to.rank.ordinal == 7) ||
            (piece.pieceSide == Side.BLACK && to.rank.ordinal == 0)
        if (!isPromotionRank) return false
        val queenPromo = Move(from, to, Piece.make(piece.pieceSide, PieceType.QUEEN))
        return isStrictlyLegal(board, queenPromo)
    }

    /**
     * Создаёт ход (обычный или с превращением пешки в выбранный тип фигуры).
     */
    fun createMove(
        from: Square,
        to: Square,
        side: Side,
        promotion: PieceType? = null
    ): Move {
        return if (promotion != null && promotion != PieceType.NONE) {
            Move(from, to, Piece.make(side, promotion))
        } else {
            Move(from, to)
        }
    }

    /**
     * Создаёт ход превращения пешки в выбранный тип фигуры.
     */
    fun createPromotionMove(side: Side, from: Square, to: Square, promotionType: PieceType): Move {
        return Move(from, to, Piece.make(side, promotionType))
    }

    /**
     * Очищает PGN от комментариев ({...}, ;...), вложенных вариантов ((...)), NAG ($N)
     * и сохраняет стартовый FEN, если он задан в заголовках.
     */
    fun normalizePgn(pgn: String): NormalizedPgn {
        val trimmed = pgn.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("PGN пустой")
        }

        val startFen = FEN_TAG_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }

        val withoutTags = stripCommentsAndVariations(
            trimmed.removePrefix("\uFEFF")
        ).replace(TAG_REGEX, " ")

        val movesOnly = withoutTags
            .replace(NAG_REGEX, " ")
            .replace("0-0-0", "O-O-O")
            .replace("0-0", "O-O")
            .replace('♔', 'K').replace('♚', 'K')
            .replace('♕', 'Q').replace('♛', 'Q')
            .replace('♖', 'R').replace('♜', 'R')
            .replace('♗', 'B').replace('♝', 'B')
            .replace('♘', 'N').replace('♞', 'N')
            .replace("e.p.", "")
            .replace(MOVE_SUFFIX_NAG_REGEX, "")
            .replace(RESULT_END_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (movesOnly.isEmpty()) {
            throw IllegalArgumentException("В PGN нет ходов")
        }

        val document = buildString {
            appendLine("""[Event "Imported Game"]""")
            appendLine("""[Site "ChessMentor"]""")
            appendLine("""[Date "????.??.??"]""")
            appendLine("""[Round "1"]""")
            appendLine("""[White "Player"]""")
            appendLine("""[Black "Opponent"]""")
            appendLine("""[Result "*"]""")
            if (startFen != null) {
                appendLine("""[SetUp "1"]""")
                appendLine("""[FEN "$startFen"]""")
            }
            appendLine()
            append(movesOnly)
            append(" *")
            appendLine()
        }

        return NormalizedPgn(
            document = document,
            movesText = movesOnly,
            startFen = startFen
        )
    }

    /**
     * Корректно удаляет фигурные комментарии {...}, строчные комментарии ;...
     * и произвольно вложенные скобки вариантов (... (...)).
     */
    private fun stripCommentsAndVariations(input: String): String {
        val out = StringBuilder(input.length)
        var inBraceComment = false
        var inLineComment = false
        var inQuotes = false
        var escapeNext = false
        var parenDepth = 0

        for (ch in input) {
            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false
                    out.append(' ')
                }
                continue
            }

            if (inBraceComment) {
                if (ch == '}') {
                    inBraceComment = false
                    out.append(' ')
                }
                continue
            }

            if (inQuotes) {
                out.append(ch)
                if (escapeNext) {
                    escapeNext = false
                } else if (ch == '\\') {
                    escapeNext = true
                } else if (ch == '"') {
                    inQuotes = false
                }
                continue
            }

            when (ch) {
                '"' -> {
                    inQuotes = true
                    out.append(ch)
                }
                '{' -> {
                    inBraceComment = true
                    out.append(' ')
                }
                ';' -> {
                    if (parenDepth == 0) {
                        inLineComment = true
                        out.append(' ')
                    }
                }
                '(' -> {
                    parenDepth++
                    out.append(' ')
                }
                ')' -> {
                    if (parenDepth > 0) parenDepth--
                    out.append(' ')
                }
                else -> {
                    if (parenDepth == 0) {
                        out.append(ch)
                    }
                }
            }
        }

        return out.toString()
    }
}
