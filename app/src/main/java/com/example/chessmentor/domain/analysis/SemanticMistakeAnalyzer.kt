// domain/analysis/SemanticMistakeAnalyzer.kt
package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import java.lang.Long.bitCount
import kotlin.math.abs

/**
 * Семантический анализатор шахматных ошибок и тактических мотивов.
 *
 * Переводит сухие линии Stockfish в понятное игроку объяснение:
 * зевнутый мат, потеря материала, вилка, связка, сквозной удар, вскрытый шах,
 * упущенная тактика, ослабление короля или позиционная неточность.
 */
class SemanticMistakeAnalyzer(
    @Suppress("unused")
    private val chessEngine: ChessEngine
) {

    /**
     * Полный разбор ошибочного хода игрока в позиции [boardBefore].
     *
     * @param boardBefore позиция ДО хода игрока (очередь хода за игроком)
     * @param playerMove сделанный игроком ход
     * @param bestMoveLine лучшая линия движка из [boardBefore] (оценка со стороны игрока)
     * @param punishmentLine лучшая линия ответа соперника ПОСЛЕ [playerMove] (оценка со стороны соперника)
     */
    fun analyzeComprehensive(
        boardBefore: Board,
        playerMove: Move,
        bestMoveLine: AnalysisLine,
        punishmentLine: AnalysisLine
    ): MistakeAnalysis {
        val playerSide = boardBefore.sideToMove
        val opponentSide = playerSide.flip()
        val fenBefore = boardBefore.fen

        // Оценки с точки зрения игрока
        val evalBest = bestMoveLine.score
        val evalAfterPlayer = -punishmentLine.score

        val boardAfterPlayer = Board().apply {
            loadFromFen(fenBefore)
            doMove(playerMove)
        }

        val punishmentMoves = punishmentLine.principalVariation.take(6)
        val tags = mutableSetOf<String>()

        // 1. Проверка на форсированный мат от соперника
        val opponentMateIn = punishmentLine.mateIn
        if (opponentMateIn != null && opponentMateIn > 0) {
            val mateMotif = detectMateMotif(boardAfterPlayer, punishmentMoves, opponentSide)
            tags.add("MATE_THREAT")
            mateMotif?.let { tags.add(it.name) }

            val explanation = when (mateMotif) {
                TacticalMotif.SMOTHERED_MATE ->
                    "Допущен спёртый мат в $opponentMateIn ${pluralMoves(opponentMateIn)}!"
                TacticalMotif.BACK_RANK ->
                    "Слабость последней горизонтали — мат в $opponentMateIn ${pluralMoves(opponentMateIn)}!"
                else ->
                    "Этот ход допускает форсированный мат в $opponentMateIn ${pluralMoves(opponentMateIn)}!"
            }

            return MistakeAnalysis(
                category = MistakeCategory.MATE_THREAT,
                tacticalMotif = mateMotif,
                materialLoss = null,
                explanation = explanation,
                betterMoveInfo = buildBetterMoveInfo(
                    boardBefore = boardBefore,
                    bestMoveLine = bestMoveLine,
                    defaultExplanation = "Защищало от матовой угрозы"
                ),
                punishmentLine = punishmentMoves,
                patternTags = tags
            )
        }

        // 2. Проверяем тактику и материальные потери в линии наказания
        val materialLoss = detectMaterialLoss(boardBefore, boardAfterPlayer, playerMove, punishmentMoves)
        val punishmentMotif = detectTacticalMotifForMove(
            board = boardAfterPlayer,
            moveUci = punishmentLine.bestMove,
            materialLoss = materialLoss
        )

        // 3. Проверяем упущенную тактику или упущенный мат в лучшем ходе игрока
        val playerMissedMate = bestMoveLine.mateIn != null && bestMoveLine.mateIn > 0
        val bestMoveMotif = detectTacticalMotifForMove(
            board = boardBefore,
            moveUci = bestMoveLine.bestMove,
            materialLoss = null
        )

        if (playerMissedMate) {
            val mateMoves = bestMoveLine.mateIn!!
            val mateMotif = detectMateMotif(boardBefore, bestMoveLine.principalVariation, playerSide)
                ?: bestMoveMotif
            tags.add("MISSED_MATE")
            mateMotif?.let { tags.add(it.name) }

            return MistakeAnalysis(
                category = MistakeCategory.MISSED_TACTIC,
                tacticalMotif = mateMotif,
                materialLoss = materialLoss,
                explanation = "Упущен форсированный мат в $mateMoves ${pluralMoves(mateMoves)}!",
                betterMoveInfo = buildBetterMoveInfo(
                    boardBefore = boardBefore,
                    bestMoveLine = bestMoveLine,
                    defaultExplanation = "Вело к мату в $mateMoves ${pluralMoves(mateMoves)}"
                ),
                punishmentLine = punishmentMoves,
                patternTags = tags
            )
        }

        // 4. Потеря материала (зевок фигуры / тактический удар соперника)
        if (materialLoss != null && materialLoss.pieceValue >= 100) {
            val motif = punishmentMotif ?: if (materialLoss.wasHanging) {
                TacticalMotif.HANGING_PIECE
            } else if (materialLoss.wasTrapped) {
                if (materialLoss.pieceType == PieceType.QUEEN) TacticalMotif.QUEEN_TRAP
                else TacticalMotif.TRAPPED_PIECE
            } else {
                null
            }

            tags.add("MATERIAL_BLUNDER")
            tags.add("LOST_${materialLoss.pieceType.name}")
            motif?.let { tags.add(it.name) }

            val explanation = buildMaterialLossExplanation(materialLoss, motif)
            return MistakeAnalysis(
                category = MistakeCategory.MATERIAL_BLUNDER,
                tacticalMotif = motif,
                materialLoss = materialLoss,
                explanation = explanation,
                betterMoveInfo = buildBetterMoveInfo(
                    boardBefore = boardBefore,
                    bestMoveLine = bestMoveLine,
                    defaultExplanation = "Сохраняло материал (${materialLoss.pieceName()})"
                ),
                punishmentLine = punishmentMoves,
                patternTags = tags
            )
        }

        // 5. Упущенный тактический выигрыш игрока
        val cpDrop = (cappedCp(evalBest) - cappedCp(evalAfterPlayer)).coerceAtLeast(0)
        if (evalBest >= 200 && cpDrop >= 150) {
            val motif = bestMoveMotif ?: punishmentMotif
            tags.add("MISSED_TACTIC")
            motif?.let { tags.add(it.name) }

            val explanation = if (motif != null) {
                "Упущена тактическая возможность: ${motif.russianName.lowercase()}."
            } else {
                "Упущено сильное тактическое продолжение с выигрышем перевеса."
            }

            return MistakeAnalysis(
                category = MistakeCategory.MISSED_TACTIC,
                tacticalMotif = motif,
                materialLoss = null,
                explanation = explanation,
                betterMoveInfo = buildBetterMoveInfo(
                    boardBefore = boardBefore,
                    bestMoveLine = bestMoveLine,
                    motif = motif,
                    defaultExplanation = "Давало решающий перевес"
                ),
                punishmentLine = punishmentMoves,
                patternTags = tags
            )
        }

        // 6. Ослабление безопасности короля (серия шахов или вскрытие короля)
        if (givesCheckInLine(boardAfterPlayer, punishmentMoves) && cpDrop >= 100) {
            val motif = punishmentMotif
            tags.add("KING_SAFETY")
            motif?.let { tags.add(it.name) }

            return MistakeAnalysis(
                category = MistakeCategory.KING_SAFETY,
                tacticalMotif = motif,
                materialLoss = null,
                explanation = "Король попадает под опасную атаку соперника.",
                betterMoveInfo = buildBetterMoveInfo(
                    boardBefore = boardBefore,
                    bestMoveLine = bestMoveLine,
                    defaultExplanation = "Обеспечивало безопасность короля"
                ),
                punishmentLine = punishmentMoves,
                patternTags = tags
            )
        }

        // 7. Позиционная неточность (с учётом дебютного контекста)
        tags.add("POSITIONAL")
        val moveNumber = boardBefore.moveCounter.coerceAtLeast(1)
        val openingContext = OpeningDatabase.getOpeningContext(fenBefore, moveNumber)
        val explanation = if (openingContext.isKnownOpening && moveNumber <= 12) {
            "Неточность в дебюте «${openingContext.openingName}»: соперник перехватывает инициативу."
        } else {
            "Позиционная неточность: у соперника появляется удобная контригра."
        }

        return MistakeAnalysis(
            category = MistakeCategory.POSITIONAL,
            tacticalMotif = null,
            materialLoss = null,
            explanation = explanation,
            betterMoveInfo = buildBetterMoveInfo(
                boardBefore = boardBefore,
                bestMoveLine = bestMoveLine,
                defaultExplanation = "Поддерживало лучшую координацию фигур"
            ),
            punishmentLine = punishmentMoves,
            patternTags = tags
        )
    }

    /**
     * Определяет тактический мотив хода [moveUci] в позиции [board].
     * Используется и при анализе ошибок, и при генерации задач.
     */
    fun detectMotif(board: Board, moveUci: String): TacticalMotif? {
        return detectTacticalMotifForMove(board, moveUci, materialLoss = null)
    }

    // ================================================================
    // ДЕТЕКТОРЫ ТАКТИЧЕСКИХ МОТИВОВ
    // ================================================================

    private fun detectTacticalMotifForMove(
        board: Board,
        moveUci: String,
        materialLoss: MaterialLoss?
    ): TacticalMotif? = Companion.detectTacticalMotifForMove(board, moveUci, materialLoss)

    private fun detectMateMotif(
        startBoard: Board,
        pv: List<String>,
        attackerSide: Side
    ): TacticalMotif? {
        if (pv.isEmpty()) return null
        val sim = Board().apply { loadFromFen(startBoard.fen) }
        var lastMove: Move? = null

        for (uci in pv) {
            val move = ChessMoveUtils.parseUciMove(sim, uci) ?: break
            if (!sim.doMove(move)) break
            lastMove = move
            if (sim.isMated) break
        }

        if (!sim.isMated) return null
        val defenderSide = attackerSide.flip()
        val kingSq = sim.getKingSquare(defenderSide)
        if (kingSq == null || kingSq == Square.NONE) return null

        // Спёртый мат: мат поставил конь, а все соседние поля заняты своими фигурами
        if (lastMove != null && sim.getPiece(lastMove.to).pieceType == PieceType.KNIGHT) {
            val adjacentSquares = getAdjacentSquares(kingSq)
            val allBlockedByOwn = adjacentSquares.isNotEmpty() && adjacentSquares.all { sq ->
                val p = sim.getPiece(sq)
                p != Piece.NONE && p.pieceSide == defenderSide
            }
            if (allBlockedByOwn) return TacticalMotif.SMOTHERED_MATE
        }

        // Мат по последней горизонтали
        val backRank = if (defenderSide == Side.WHITE) 0 else 7
        val shieldRank = if (defenderSide == Side.WHITE) 1 else 6
        if (kingSq.rank.ordinal == backRank && lastMove != null) {
            val matingPiece = sim.getPiece(lastMove.to).pieceType
            if (matingPiece == PieceType.ROOK || matingPiece == PieceType.QUEEN) {
                val file = kingSq.file.ordinal
                val shieldSquares = (-1..1)
                    .map { file + it }
                    .filter { it in 0..7 }
                    .map { f -> Square.squareAt(shieldRank * 8 + f) }
                val blockedCount = shieldSquares.count { sq ->
                    val p = sim.getPiece(sq)
                    p != Piece.NONE && p.pieceSide == defenderSide
                }
                if (blockedCount >= 2) return TacticalMotif.BACK_RANK
            }
        }

        return null
    }

    private fun detectMaterialLoss(
        boardBefore: Board,
        boardAfterPlayer: Board,
        playerMove: Move,
        punishmentMoves: List<String>
    ): MaterialLoss? {
        if (punishmentMoves.isEmpty()) return null
        val playerSide = boardBefore.sideToMove
        val opponentSide = playerSide.flip()

        // Проверяем первый ход соперника (прямое взятие фигуры)
        val firstReply = ChessMoveUtils.parseUciMove(boardAfterPlayer, punishmentMoves.first())
            ?: return null
        val directCaptured = boardAfterPlayer.getPiece(firstReply.to)

        if (directCaptured != Piece.NONE && directCaptured.pieceSide == playerSide) {
            val capturedVal = ChessMoveUtils.pieceValue(directCaptured.pieceType)
            val attackerPiece = boardAfterPlayer.getPiece(firstReply.from)
            val attackerVal = ChessMoveUtils.pieceValue(attackerPiece.pieceType)

            val attackers = bitCount(boardAfterPlayer.squareAttackedBy(firstReply.to, opponentSide))
            val defenders = bitCount(boardAfterPlayer.squareAttackedBy(firstReply.to, playerSide))
            val wasHanging = defenders == 0
            val profitableCapture = wasHanging ||
                capturedVal > attackerVal ||
                attackers > defenders

            // Не считаем равноценный размен потерей материала
            val playerCaptured = boardBefore.getPiece(playerMove.to)
            val playerCapturedVal = if (playerCaptured != Piece.NONE && playerCaptured.pieceSide == opponentSide) {
                ChessMoveUtils.pieceValue(playerCaptured.pieceType)
            } else {
                0
            }

            if (profitableCapture && capturedVal > playerCapturedVal) {
                return MaterialLoss(
                    pieceType = directCaptured.pieceType,
                    pieceValue = capturedVal - playerCapturedVal,
                    wasHanging = wasHanging,
                    wasTrapped = false,
                    attackersCount = attackers,
                    defendersCount = defenders
                )
            }
        }

        // Проверяем суммарный баланс материала через несколько полуходов PV
        val balanceStart = materialBalance(boardAfterPlayer, playerSide)
        val sim = Board().apply { loadFromFen(boardAfterPlayer.fen) }
        var lostPieceType: PieceType? = null
        var maxPieceVal = 0

        for (uci in punishmentMoves.take(4)) {
            val m = ChessMoveUtils.parseUciMove(sim, uci) ?: break
            val target = sim.getPiece(m.to)
            if (target != Piece.NONE && target.pieceSide == playerSide) {
                val v = ChessMoveUtils.pieceValue(target.pieceType)
                if (v > maxPieceVal) {
                    maxPieceVal = v
                    lostPieceType = target.pieceType
                }
            }
            if (!sim.doMove(m)) break
        }

        val netLoss = balanceStart - materialBalance(sim, playerSide)
        if (netLoss >= 200 && lostPieceType != null) {
            val wasTrapped = lostPieceType == PieceType.QUEEN || lostPieceType == PieceType.BISHOP ||
                lostPieceType == PieceType.ROOK
            return MaterialLoss(
                pieceType = lostPieceType,
                pieceValue = netLoss,
                wasHanging = false,
                wasTrapped = wasTrapped && netLoss >= 300,
                attackersCount = 1,
                defendersCount = 0
            )
        }

        return null
    }

    private fun givesCheckInLine(startBoard: Board, moves: List<String>): Boolean {
        val sim = Board().apply { loadFromFen(startBoard.fen) }
        for (uci in moves.take(3)) {
            if (!ChessMoveUtils.applyUciMove(sim, uci)) break
            if (sim.isKingAttacked) return true
        }
        return false
    }

    private fun buildMaterialLossExplanation(loss: MaterialLoss, motif: TacticalMotif?): String {
        val pieceName = loss.pieceName()
        return when (motif) {
            TacticalMotif.FORK -> "Зевок вилки: теряется $pieceName."
            TacticalMotif.PIN -> "Фигура попадает под связку — теряется $pieceName."
            TacticalMotif.SKEWER -> "Сквозной удар соперника выигрывает материал ($pieceName)."
            TacticalMotif.DISCOVERED_CHECK -> "Вскрытый шах с выигрышем материала ($pieceName)."
            TacticalMotif.DISCOVERED_ATTACK -> "Вскрытое нападение — теряется $pieceName."
            TacticalMotif.OVERLOADING -> "Перегрузка защитника — теряется $pieceName."
            TacticalMotif.DEFLECTION -> "Отвлечение защитника — теряется $pieceName."
            TacticalMotif.REMOVING_DEFENDER -> "Уничтожение защитника — теряется $pieceName."
            TacticalMotif.QUEEN_TRAP -> "Ферзь попадает в ловушку!"
            TacticalMotif.TRAPPED_PIECE -> "Фигура ($pieceName) поймана и теряется."
            TacticalMotif.HANGING_PIECE -> "Незащищённая фигура: теряется $pieceName."
            else -> "Потеря материала (${loss.shortDescription()})."
        }
    }

    private fun buildBetterMoveInfo(
        boardBefore: Board,
        bestMoveLine: AnalysisLine,
        motif: TacticalMotif? = null,
        defaultExplanation: String
    ): BetterMoveInfo? {
        val uci = bestMoveLine.bestMove.trim()
        if (uci.length < 4) return null
        val san = ChessMoveUtils.uciToSan(uci, boardBefore.fen)
        val explanation = if (motif != null) {
            "$san — ${motif.russianName.lowercase()}"
        } else {
            "$san — ${defaultExplanation.replaceFirstChar { it.lowercase() }}"
        }
        return BetterMoveInfo(
            moveUci = uci,
            moveNotation = san,
            evaluationAfter = bestMoveLine.score,
            explanation = explanation,
            wasWinning = bestMoveLine.score >= 200 || (bestMoveLine.mateIn != null && bestMoveLine.mateIn > 0)
        )
    }

    private fun materialBalance(board: Board, side: Side): Int {
        var score = 0
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val p = board.getPiece(sq)
            if (p == Piece.NONE || p.pieceType == PieceType.KING) continue
            val value = ChessMoveUtils.pieceValue(p.pieceType)
            score += if (p.pieceSide == side) value else -value
        }
        return score
    }

    private fun cappedCp(score: Int): Int {
        if (ChessEngine.isMateScore(score)) {
            return if (score > 0) 1500 else -1500
        }
        return score.coerceIn(-1200, 1200)
    }

    private fun pluralMoves(n: Int): String {
        val absN = abs(n) % 100
        val last = absN % 10
        return when {
            absN in 11..19 -> "ходов"
            last == 1 -> "ход"
            last in 2..4 -> "хода"
            else -> "ходов"
        }
    }

    companion object {
        private val SLIDING_TYPES = setOf(PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)
        private val BISHOP_DIRS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        private val ROOK_DIRS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
        private val QUEEN_DIRS = BISHOP_DIRS + ROOK_DIRS
        private val KNIGHT_DIRS = listOf(
            -2 to -1, -2 to 1, -1 to -2, -1 to 2,
            1 to -2, 1 to 2, 2 to -1, 2 to 1
        )

        /**
         * Статический определитель тактического мотива хода [moveUci] в позиции [board]
         * (чистый chesslib без обращения к движку Stockfish).
         */
        fun detectStaticMotif(board: Board, moveUci: String): TacticalMotif? =
            detectTacticalMotifForMove(board, moveUci, materialLoss = null)

        fun isDoubleCheckMove(board: Board, moveUci: String): Boolean {
            val move = ChessMoveUtils.parseUciMove(board, moveUci) ?: return false
            val targetSide = board.sideToMove.flip()
            val moverSide = board.sideToMove
            val after = Board().apply {
                loadFromFen(board.fen)
                if (!doMove(move)) return false
            }
            if (!after.isKingAttacked) return false
            val kingSq = after.getKingSquare(targetSide)
            if (kingSq == null || kingSq == Square.NONE) return false
            val checkers = after.squareAttackedBy(kingSq, moverSide)
            return bitCount(checkers) >= 2
        }

        fun createsPinToKing(board: Board, move: Move, movingPiece: Piece): Boolean {
            val pt = movingPiece.pieceType
            if (pt != PieceType.BISHOP && pt != PieceType.ROOK && pt != PieceType.QUEEN) return false
            val after = Board().apply { loadFromFen(board.fen) }
            if (!after.doMove(move)) return false

            val enemySide = movingPiece.pieceSide.flip()
            val directions = buildList {
                if (pt == PieceType.BISHOP || pt == PieceType.QUEEN) addAll(BISHOP_DIRS)
                if (pt == PieceType.ROOK || pt == PieceType.QUEEN) addAll(ROOK_DIRS)
            }
            val startFile = move.to.file.ordinal
            val startRank = move.to.rank.ordinal
            for ((df, dr) in directions) {
                var f = startFile + df
                var r = startRank + dr
                var seenEnemyPiece = false
                while (f in 0..7 && r in 0..7) {
                    val p = after.getPiece(Square.squareAt(r * 8 + f))
                    if (p != Piece.NONE) {
                        if (p.pieceSide != enemySide) break
                        if (!seenEnemyPiece) {
                            if (p.pieceType == PieceType.KING) break
                            seenEnemyPiece = true
                        } else {
                            if (p.pieceType == PieceType.KING) return true
                            break
                        }
                    }
                    f += df
                    r += dr
                }
            }
            return false
        }

        private fun detectTacticalMotifForMove(
            board: Board,
            moveUci: String,
            materialLoss: MaterialLoss?
        ): TacticalMotif? {
            if (moveUci.length < 4) return null
            val moverSide = board.sideToMove
            val targetSide = moverSide.flip()

            val move = ChessMoveUtils.parseUciMove(board, moveUci) ?: return null
            val movingPiece = board.getPiece(move.from)
            if (movingPiece == Piece.NONE) return null

            val targetBefore = board.getPiece(move.to)

            val boardAfter = Board().apply {
                loadFromFen(board.fen)
                if (!doMove(move)) return null
            }

            val movedPieceAfter = boardAfter.getPiece(move.to)
            val givesCheck = boardAfter.isKingAttacked

            // 1. Вскрытый шах / вскрытое нападение
            if (isDiscoveredCheck(boardAfter, move.to, moverSide, targetSide)) {
                return TacticalMotif.DISCOVERED_CHECK
            }
            if (isDiscoveredAttack(board, boardAfter, move.from, move.to, moverSide, targetSide)) {
                return TacticalMotif.DISCOVERED_ATTACK
            }

            // 2. Вилка: ходившая фигура одновременно атакует >= 2 ценные цели
            if (movedPieceAfter != Piece.NONE && isFork(boardAfter, move.to, movedPieceAfter, targetSide)) {
                return TacticalMotif.FORK
            }

            // 3. Перегрузка / Отвлечение защитника (проверяем до линейного рентгена, чтобы жертва на линии вроде Qa8 под Rxa8/Re8# не принималась за связку)
            detectOverloadingOrDeflection(board, boardAfter, move, moverSide, targetSide)?.let {
                return it
            }

            // 4. Связка / сквозной удар для дальнобойных фигур
            if (movedPieceAfter.pieceType in SLIDING_TYPES) {
                val rayMotif = detectPinOrSkewer(boardAfter, move.to, moverSide, targetSide)
                if (rayMotif != null) return rayMotif
            }

            // 5. Взятие незащищённой фигуры
            if (targetBefore != Piece.NONE && targetBefore.pieceSide == targetSide) {
                val defendersAfter = bitCount(boardAfter.squareAttackedBy(move.to, targetSide))
                if (defendersAfter == 0 && ChessMoveUtils.pieceValue(targetBefore.pieceType) >= 300) {
                    return TacticalMotif.HANGING_PIECE
                }
                if (givesCheck && ChessMoveUtils.pieceValue(targetBefore.pieceType) >= 100) {
                    return TacticalMotif.REMOVING_DEFENDER
                }
            }

            // 6. Ловля фигуры
            if (materialLoss?.wasTrapped == true) {
                return if (materialLoss.pieceType == PieceType.QUEEN) {
                    TacticalMotif.QUEEN_TRAP
                } else {
                    TacticalMotif.TRAPPED_PIECE
                }
            }

            return null
        }

        private fun detectOverloadingOrDeflection(
            boardBefore: Board,
            boardAfter: Board,
            move: Move,
            moverSide: Side,
            targetSide: Side
        ): TacticalMotif? {
            if (boardAfter.isMated) return null

            val targetPieceBefore = boardBefore.getPiece(move.to)
            val isCaptureOnTarget = targetPieceBefore != Piece.NONE && targetPieceBefore.pieceSide == targetSide

            val replyCaptures = runCatching { boardAfter.legalMoves() }
                .getOrDefault(emptyList())
                .filter { it.to == move.to }

            for (reply in replyCaptures) {
                val defenderSq = reply.from
                val defenderPiece = boardAfter.getPiece(defenderSq)
                if (defenderPiece == Piece.NONE || defenderPiece.pieceSide != targetSide) continue

                val simAfterReply = Board().apply { loadFromFen(boardAfter.fen) }
                if (!simAfterReply.doMove(reply)) continue

                val mateOpened = hasMateInOne(simAfterReply, moverSide)

                var abandonedValuableSquare: Square? = null
                for (sq in Square.values()) {
                    if (sq == Square.NONE || sq == move.to || sq == defenderSq) continue
                    val p = simAfterReply.getPiece(sq)
                    if (p == Piece.NONE || p.pieceSide != targetSide || p.pieceType == PieceType.KING) continue
                    val pieceVal = ChessMoveUtils.pieceValue(p.pieceType)
                    if (pieceVal < 300) continue

                    val attackedAfter = simAfterReply.squareAttackedBy(sq, moverSide)
                    if (attackedAfter == 0L) continue

                    val defendedBeforeMove = (boardBefore.squareAttackedBy(sq, targetSide) and defenderSq.bitboard) != 0L
                    if (!defendedBeforeMove) continue

                    val defendersNow = bitCount(simAfterReply.squareAttackedBy(sq, targetSide))
                    val attackersNow = bitCount(attackedAfter)
                    if (defendersNow == 0 || attackersNow > defendersNow) {
                        abandonedValuableSquare = sq
                        break
                    }
                }

                if (!mateOpened && abandonedValuableSquare == null) continue

                if (isCaptureOnTarget && abandonedValuableSquare != null) {
                    val defendedTargetBefore = (boardBefore.squareAttackedBy(move.to, targetSide) and defenderSq.bitboard) != 0L
                    val otherProtectedValuables = Square.values().count { sq ->
                        if (sq == Square.NONE || sq == defenderSq) return@count false
                        val p = boardBefore.getPiece(sq)
                        p != Piece.NONE && p.pieceSide == targetSide &&
                            ChessMoveUtils.pieceValue(p.pieceType) >= 300 &&
                            (boardBefore.squareAttackedBy(sq, targetSide) and defenderSq.bitboard) != 0L
                    }
                    if (defendedTargetBefore && otherProtectedValuables >= 2) {
                        return TacticalMotif.OVERLOADING
                    }
                }

                return TacticalMotif.DEFLECTION
            }

            return null
        }

        private fun hasMateInOne(board: Board, attackerSide: Side): Boolean {
            if (board.sideToMove != attackerSide) return false
            val legal = runCatching { board.legalMoves() }.getOrDefault(emptyList())
            for (m in legal) {
                val sim = Board().apply { loadFromFen(board.fen) }
                if (sim.doMove(m) && sim.isMated) return true
            }
            return false
        }

        private fun isFork(
            board: Board,
            attackerSq: Square,
            attacker: Piece,
            targetSide: Side
        ): Boolean {
            val attackerVal = ChessMoveUtils.pieceValue(attacker.pieceType)
            val targets = getPieceAttacks(board, attackerSq, attacker.pieceType, attacker.pieceSide)
            var valuableTargets = 0

            for (sq in targets) {
                val target = board.getPiece(sq)
                if (target == Piece.NONE || target.pieceSide != targetSide) continue
                val targetVal = ChessMoveUtils.pieceValue(target.pieceType)
                val isUndefended = board.squareAttackedBy(sq, targetSide) == 0L
                val isValuable = target.pieceType == PieceType.KING ||
                    targetVal > attackerVal ||
                    (targetVal >= 300 && isUndefended)
                if (isValuable) {
                    valuableTargets++
                }
            }

            return valuableTargets >= 2
        }

        private fun detectPinOrSkewer(
            board: Board,
            attackerSq: Square,
            attackerSide: Side,
            @Suppress("UNUSED_PARAMETER") targetSide: Side
        ): TacticalMotif? {
            val pieceType = board.getPiece(attackerSq).pieceType
            val directions = when (pieceType) {
                PieceType.BISHOP -> BISHOP_DIRS
                PieceType.ROOK -> ROOK_DIRS
                PieceType.QUEEN -> QUEEN_DIRS
                else -> return null
            }

            for ((df, dr) in directions) {
                var f = attackerSq.file.ordinal + df
                var r = attackerSq.rank.ordinal + dr
                var first: Piece? = null
                var second: Piece? = null

                while (f in 0..7 && r in 0..7) {
                    val p = board.getPiece(Square.squareAt(r * 8 + f))
                    if (p != Piece.NONE) {
                        if (p.pieceSide == attackerSide) break
                        if (first == null) {
                            first = p
                        } else {
                            second = p
                            break
                        }
                    }
                    f += df
                    r += dr
                }

                if (first != null && second != null) {
                    val v1 = ChessMoveUtils.pieceValue(first.pieceType)
                    val v2 = ChessMoveUtils.pieceValue(second.pieceType)
                    if (v1 >= 300 && v2 > v1) {
                        return TacticalMotif.PIN
                    }
                    if (v1 > v2 && v2 >= 300) {
                        return TacticalMotif.SKEWER
                    }
                }
            }

            return null
        }

        private fun isDiscoveredCheck(
            boardAfter: Board,
            movedTo: Square,
            moverSide: Side,
            targetSide: Side
        ): Boolean {
            if (!boardAfter.isKingAttacked) return false
            val kingSq = boardAfter.getKingSquare(targetSide)
            if (kingSq == null || kingSq == Square.NONE) return false
            val checkersBitboard = boardAfter.squareAttackedBy(kingSq, moverSide)
            return (checkersBitboard and movedTo.bitboard.inv()) != 0L
        }

        private fun isDiscoveredAttack(
            boardBefore: Board,
            boardAfter: Board,
            movedFrom: Square,
            movedTo: Square,
            moverSide: Side,
            targetSide: Side
        ): Boolean {
            val movedToMask = movedTo.bitboard.inv()
            for (sq in Square.values()) {
                if (sq == Square.NONE || sq == movedTo || sq == movedFrom) continue
                val target = boardAfter.getPiece(sq)
                if (target == Piece.NONE || target.pieceSide != targetSide) continue
                if (ChessMoveUtils.pieceValue(target.pieceType) < 500) continue

                val beforeAttackers = boardBefore.squareAttackedBy(sq, moverSide)
                val afterOtherAttackers = boardAfter.squareAttackedBy(sq, moverSide) and movedToMask
                if (beforeAttackers == 0L && afterOtherAttackers != 0L) {
                    return true
                }
            }
            return false
        }

        private fun getAdjacentSquares(sq: Square): List<Square> {
            val result = mutableListOf<Square>()
            for ((df, dr) in QUEEN_DIRS) {
                val nf = sq.file.ordinal + df
                val nr = sq.rank.ordinal + dr
                if (nf in 0..7 && nr in 0..7) {
                    result.add(Square.squareAt(nr * 8 + nf))
                }
            }
            return result
        }

        private fun getPieceAttacks(
            board: Board,
            from: Square,
            type: PieceType,
            side: Side
        ): List<Square> {
            val result = mutableListOf<Square>()
            when (type) {
                PieceType.KNIGHT -> {
                    for ((df, dr) in KNIGHT_DIRS) {
                        val nf = from.file.ordinal + df
                        val nr = from.rank.ordinal + dr
                        if (nf in 0..7 && nr in 0..7) {
                            result.add(Square.squareAt(nr * 8 + nf))
                        }
                    }
                }
                PieceType.BISHOP -> addRayAttacks(board, from, BISHOP_DIRS, result)
                PieceType.ROOK -> addRayAttacks(board, from, ROOK_DIRS, result)
                PieceType.QUEEN -> addRayAttacks(board, from, QUEEN_DIRS, result)
                PieceType.PAWN -> {
                    val dir = if (side == Side.WHITE) 1 else -1
                    val nr = from.rank.ordinal + dir
                    val f = from.file.ordinal
                    if (nr in 0..7) {
                        if (f > 0) result.add(Square.squareAt(nr * 8 + f - 1))
                        if (f < 7) result.add(Square.squareAt(nr * 8 + f + 1))
                    }
                }
                PieceType.KING -> result.addAll(getAdjacentSquares(from))
                else -> {}
            }
            return result
        }

        private fun addRayAttacks(
            board: Board,
            from: Square,
            dirs: List<Pair<Int, Int>>,
            out: MutableList<Square>
        ) {
            for ((df, dr) in dirs) {
                var nf = from.file.ordinal + df
                var nr = from.rank.ordinal + dr
                while (nf in 0..7 && nr in 0..7) {
                    val sq = Square.squareAt(nr * 8 + nf)
                    out.add(sq)
                    if (board.getPiece(sq) != Piece.NONE) break
                    nf += df
                    nr += dr
                }
            }
        }
    }
}
