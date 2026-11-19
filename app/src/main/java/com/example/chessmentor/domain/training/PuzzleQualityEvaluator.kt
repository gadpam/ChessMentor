// domain/training/PuzzleQualityEvaluator.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.abs

/**
 * Педагогический тип позиции для тренировки.
 */
enum class PuzzleKind {
    /** Позиция не подходит для тактической задачи (безнадёжна, уже выиграна или «мутная»). */
    REJECTED,

    /** Атакующая / выигрывающая тактика (мат, выигрыш материала, решающий прорыв). */
    WINNING_TACTIC,

    /** Защитная / профилактическая задача: отразить угрозу, найти контрудар или спасти ничью. */
    DEFENSIVE_RESOURCE
}

/**
 * Оценка качества тактических задач, классификация задач на защиту/профилактику
 * и умная обрезка главной линии (PV) в точке успокоения позиции (после завершения
 * форсированных взятий, жертв и шахов).
 */
object PuzzleQualityEvaluator {

    /** Позиция уже безнадёжна для игрока до хода (≤ −5.5 пешки). */
    const val HOPELESS_EVAL_THRESHOLD_CP = -550

    /** Позиция уже абсолютно выиграна (≥ +7.0 пешек) — берём только грубые зевки ≥ 400 сП. */
    const val ALREADY_CRUSHING_EVAL_CP = 700
    const val MIN_LOSS_IN_CRUSHING_POS_CP = 400

    /** Минимальная потеря для позиций без полных данных об оценке. */
    const val MIN_STANDALONE_LOSS_CP = 110

    /** Минимальный разрыв между 1-м и 2-м ходом (сП), чтобы решение было чётким, а не «угадайкой». */
    const val MIN_UNIQUENESS_GAP_CP = 75

    const val MAX_QUIESCENCE_PLIES = 7

    /**
     * Классифицирует ошибку из партии: подходит ли она для тренажёра, и если да —
     * это задача на выигрыш ([PuzzleKind.WINNING_TACTIC]) или на точную защиту
     * ([PuzzleKind.DEFENSIVE_RESOURCE]).
     */
    fun classifyMistake(
        mistake: Mistake,
        analyzedMove: AnalyzedMove?
    ): PuzzleKind {
        if (mistake.fenBefore.isNullOrBlank()) return PuzzleKind.REJECTED

        // Ранние дебютные неточности не дают содержательных тактических задач
        if (mistake.moveNumber <= 3 && mistake.evaluationLoss < 220) {
            return PuzzleKind.REJECTED
        }

        // Без данных evalBefore/evalAfter опираемся на масштаб ошибки
        if (analyzedMove == null) {
            if (mistake.mistakeType == MistakeType.INACCURACY &&
                mistake.evaluationLoss < MIN_STANDALONE_LOSS_CP
            ) {
                return PuzzleKind.REJECTED
            }
            return PuzzleKind.WINNING_TACTIC
        }

        val sign = if (mistake.color == ChessColor.WHITE) 1 else -1
        val evalBefore = analyzedMove.evalBefore * sign
        val evalAfter = analyzedMove.evalAfter * sign
        val loss = maxOf(mistake.evaluationLoss, analyzedMove.evalLoss)

        // 1. Игроку уже ставили форсированный мат до его хода -> задача бессмысленна
        if (ChessEngine.isMateScore(evalBefore) && evalBefore < 0) {
            return PuzzleKind.REJECTED
        }

        // 2. Упущен форсированный мат за игрока -> отличная матовая задача
        if (ChessEngine.isMateScore(evalBefore) && evalBefore > 0) {
            val stillWinningFastMate = ChessEngine.isMateScore(evalAfter) && evalAfter > 0 && loss < 200
            return if (stillWinningFastMate) PuzzleKind.REJECTED else PuzzleKind.WINNING_TACTIC
        }

        // 3. Безнадёжно проигранная позиция до хода -> отклоняем
        if (evalBefore <= HOPELESS_EVAL_THRESHOLD_CP) {
            return PuzzleKind.REJECTED
        }

        // 4. Уже абсолютно выигранная позиция -> берём только реальный зевок фигуры/мата/победы
        if (evalBefore >= ALREADY_CRUSHING_EVAL_CP) {
            return if (loss >= MIN_LOSS_IN_CRUSHING_POS_CP || evalAfter <= 150) {
                if (evalAfter <= -120) PuzzleKind.DEFENSIVE_RESOURCE else PuzzleKind.WINNING_TACTIC
            } else {
                PuzzleKind.REJECTED
            }
        }

        // 5. Игрок зевнул форсированный мат в игровой позиции -> первоклассная задача на защиту!
        if (ChessEngine.isMateScore(evalAfter) && evalAfter < 0) {
            return if (evalBefore >= 220) PuzzleKind.WINNING_TACTIC else PuzzleKind.DEFENSIVE_RESOURCE
        }

        // 6. Отсекаем «мутные» мелкие позиционные колебания (например +0.7 -> -0.1),
        // где обычно 4–5 равноценных ходов и нет чёткого тактического решения
        if (loss < 130) {
            return PuzzleKind.REJECTED
        }

        // 7. Защитная задача: позиция была равной/напряжённой (-220..+160 сП),
        // а ход игрока провалил её в тяжёлую/проигранную (<= -180 сП с потерей >= 160 сП)
        if (evalBefore in -220..160 && evalAfter <= -180 && loss >= 160) {
            return PuzzleKind.DEFENSIVE_RESOURCE
        }

        // 8. Выигрывающая тактика: у игрока был ощутимый тактический перевес (>= +165 сП),
        // который он упустил (потеря >= 150 сП)
        if (evalBefore >= 165 && loss >= 150) {
            return PuzzleKind.WINNING_TACTIC
        }

        // 9. Крупный зевок (BLUNDER >= 250 сП) в равной борьбе — тоже задача на защиту/бдительность
        if (loss >= 250 && evalAfter <= -120) {
            return PuzzleKind.DEFENSIVE_RESOURCE
        }

        return PuzzleKind.REJECTED
    }

    /**
     * Проверяет, нет ли в позиции нескольких «банальных» альтернатив той же силы,
     * что делает задачу угадайкой (например, 3 разных отступления с одинаковой оценкой).
     *
     * Для экономии батареи на мобильном устройстве проверяются только до [maxAlternatives]
     * наиболее форсированных альтернативных ходов (взятия, шахи, превращения) на малой глубине.
     * Возвращает `true`, если решение достаточно чёткое для тренажёра.
     */
    suspend fun hasClearTacticalSolution(
        board: Board,
        bestMoveUci: String,
        bestScoreSideToMove: Int,
        puzzleKind: PuzzleKind,
        engine: ChessEngine,
        checkDepth: Int = 8,
        maxAlternatives: Int = 3
    ): Boolean {
        // Форсированный мат — всегда содержательная задача
        if (ChessEngine.isMateScore(bestScoreSideToMove) && bestScoreSideToMove > 0) {
            return true
        }

        // Если оценка движка нулевая и нет форсированных альтернатив — не блокируем генерацию
        val legalMoves = runCatching { board.legalMoves() }.getOrDefault(emptyList())
        if (legalMoves.size <= 1) return board.isKingAttacked

        val normalizedBest = bestMoveUci.trim().lowercase()
        val candidateAlternatives = legalMoves
            .asSequence()
            .filter { ChessMoveUtils.moveToUci(it) != normalizedBest }
            .sortedByDescending { moveForcingPriority(board, it) }
            .take(maxAlternatives)
            .toList()

        if (candidateAlternatives.isEmpty()) return true

        val requiredGap = if (puzzleKind == PuzzleKind.DEFENSIVE_RESOURCE) {
            // В защитной задаче важно, чтобы позиция реально требовала точности:
            // если 2+ других хода так же легко держат равенство — угрозы на самом деле не было
            MIN_UNIQUENESS_GAP_CP
        } else {
            MIN_UNIQUENESS_GAP_CP
        }

        var nearlyEqualCount = 0
        for (alt in candidateAlternatives) {
            val sim = Board().apply { loadFromFen(board.fen) }
            if (!sim.doMove(alt)) continue
            if (sim.isMated) {
                // В задаче два разных мата в 1 ход — слишком неоднозначно
                nearlyEqualCount++
                if (nearlyEqualCount >= 2) return false
                continue
            }

            val oppEval = runCatching { engine.evaluate(sim.fen, checkDepth) }.getOrNull() ?: continue
            // Защита от заглушек движка в тестах (когда bestScore большой, а evaluate всегда 0)
            if (oppEval == 0 && abs(bestScoreSideToMove) >= 350) continue

            val altPlayerEval = -oppEval
            val gap = bestScoreSideToMove - altPlayerEval
            if (gap < requiredGap) {
                nearlyEqualCount++
                // Допускаем максимум 1 близкую побочную перестановку ходов, но не 2+ («всё хорошо»)
                if (nearlyEqualCount >= 2) {
                    return false
                }
            }
        }

        return true
    }

    private fun moveForcingPriority(board: Board, move: Move): Int {
        var score = 0
        val captured = board.getPiece(move.to)
        val moving = board.getPiece(move.from)
        if (move.promotion != Piece.NONE) {
            score += 900 + ChessMoveUtils.pieceValue(move.promotion.pieceType)
        }
        if (captured != Piece.NONE) {
            // MVV-LVA: взятие ценной фигуры дешёвой проверяем первым
            score += 500 + ChessMoveUtils.pieceValue(captured.pieceType) -
                (ChessMoveUtils.pieceValue(moving.pieceType) / 10)
        }
        val sim = Board().apply { loadFromFen(board.fen) }
        if (sim.doMove(move) && sim.isKingAttacked) {
            score += 350
        }
        return score
    }

    /**
     * Умно обрезает главную линию (PV) до НЕЧЁТНОГО числа полуходов (1, 3, 5 или 7)
     * в точке успокоения позиции:
     * - останавливается немедленно при мате или пате;
     * - НЕ обрывает вариант посередине незавершённого размена (когда соперник следующим
     *   ходом бьёт фигуру игрока) или после подготовительного шаха/жертвы перед отыгрышем;
     * - НЕ тянет лишние «тихие» технические ходы после того, как материал уже выигран
     *   или угроза полностью отражена.
     */
    fun trimPvAtQuiescence(
        startFen: String,
        rawUciMoves: List<String>,
        maxPlies: Int = MAX_QUIESCENCE_PLIES
    ): List<String> {
        if (rawUciMoves.isEmpty() || maxPlies <= 0) return emptyList()
        val oddCap = if (maxPlies % 2 == 0) (maxPlies - 1).coerceAtLeast(1) else maxPlies

        val startBoard = try {
            Board().apply { loadFromFen(startFen) }
        } catch (_: Exception) {
            return TrainingRatingCalculator.trimPvToPlayerLastMove(rawUciMoves, oddCap)
        }

        data class PlyInfo(
            val uci: String,
            val from: Square,
            val to: Square,
            val isPlayerMove: Boolean,
            val capturedValue: Int,
            val movedValue: Int,
            val isPromotion: Boolean,
            val givesCheck: Boolean,
            val endsInMateOrDraw: Boolean,
            val playerMaterialDeltaFromStart: Int
        )

        val playerSide = startBoard.sideToMove
        val initialMat = materialBalance(startBoard, playerSide)
        val sim = Board().apply { loadFromFen(startFen) }
        val plies = mutableListOf<PlyInfo>()

        for (raw in rawUciMoves.take(oddCap)) {
            val mover = sim.sideToMove
            val move = ChessMoveUtils.parseUciMove(sim, raw) ?: break
            val movingPiece = sim.getPiece(move.from)
            val targetPiece = sim.getPiece(move.to)
            val isEp = movingPiece.pieceType == PieceType.PAWN &&
                move.from.file != move.to.file &&
                targetPiece == Piece.NONE
            val capturedVal = when {
                targetPiece != Piece.NONE -> ChessMoveUtils.pieceValue(targetPiece.pieceType)
                isEp -> 100
                else -> 0
            }
            val movedVal = ChessMoveUtils.pieceValue(movingPiece.pieceType)
            val isPromo = move.promotion != Piece.NONE
            val cleanUci = ChessMoveUtils.moveToUci(move)

            if (!sim.doMove(move)) break

            val terminal = sim.isMated || sim.isStaleMate || sim.isDraw
            val matDelta = materialBalance(sim, playerSide) - initialMat

            plies.add(
                PlyInfo(
                    uci = cleanUci,
                    from = move.from,
                    to = move.to,
                    isPlayerMove = mover == playerSide,
                    capturedValue = capturedVal,
                    movedValue = movedVal,
                    isPromotion = isPromo,
                    givesCheck = sim.isKingAttacked,
                    endsInMateOrDraw = terminal,
                    playerMaterialDeltaFromStart = matDelta
                )
            )

            if (terminal) break
        }

        if (plies.isEmpty()) return emptyList()

        // Проверяем каждую точку после хода игрока (индексы 0, 2, 4, 6 -> длины 1, 3, 5, 7)
        var bestLen = 1
        var idx = 0
        while (idx < plies.size) {
            val playerPly = plies[idx]
            val candidateLen = idx + 1
            bestLen = candidateLen

            if (playerPly.endsInMateOrDraw) {
                return plies.take(candidateLen).map { it.uci }
            }

            // Есть ли полная следующая пара полуходов [ответ соперника, следующий ход игрока]?
            if (idx + 2 >= plies.size) {
                break
            }

            val oppReply = plies[idx + 1]
            val nextPlayerPly = plies[idx + 2]

            // Признаки того, что комбинация ЕЩЁ НЕ ЗАКОНЧЕНА и обрывать на idx нельзя:
            // 1) Соперник немедленно бьёт на поле нашего хода или вообще отыгрывает фигуру/пешку
            val opponentCapturesBack = oppReply.to == playerPly.to || oppReply.capturedValue >= 100
            // 2) Наш ход был шахом (вилка, сквозной удар, вскрытый шах, завлечение),
            //    а следующий наш ход бьёт материал, превращает пешку или продолжает матовую сеть
            val checkLeadsToPayoff = playerPly.givesCheck && (
                nextPlayerPly.capturedValue >= 100 ||
                    nextPlayerPly.isPromotion ||
                    nextPlayerPly.givesCheck ||
                    nextPlayerPly.endsInMateOrDraw
                )
            // 3) Следующий наш ход — форсированный мат, превращение или крупный выигрыш материала
            val nextPlyWinsDecisively = nextPlayerPly.endsInMateOrDraw ||
                nextPlayerPly.isPromotion ||
                (nextPlayerPly.playerMaterialDeltaFromStart - playerPly.playerMaterialDeltaFromStart >= 250)
            // 4) Соперник ответил встречным шахом — игрок обязан доказать, что атака корректна
            val opponentCounterChecks = oppReply.givesCheck

            val mustContinue = opponentCapturesBack ||
                checkLeadsToPayoff ||
                nextPlyWinsDecisively ||
                opponentCounterChecks

            if (!mustContinue) {
                // Позиция успокоилась: форсированная тактика завершена, дальше тихая реализация
                break
            }

            idx += 2
        }

        return plies.take(bestLen).map { it.uci }
    }

    /**
     * Определяет, является ли защитный ход активной контратакой / промежуточным ударом (цвишенцугом).
     */
    fun isCounterAttackingDefense(board: Board, solutionMoves: List<String>): Boolean {
        val firstUci = solutionMoves.firstOrNull() ?: return false
        return try {
            val sim = Board().apply { loadFromFen(board.fen) }
            val move = ChessMoveUtils.parseUciMove(sim, firstUci) ?: return false
            val target = sim.getPiece(move.to)
            val capturesValuable = target != Piece.NONE && ChessMoveUtils.pieceValue(target.pieceType) >= 300
            if (!sim.doMove(move)) return false
            sim.isKingAttacked || capturesValuable
        } catch (_: Exception) {
            false
        }
    }

    private fun materialBalance(board: Board, side: Side): Int {
        var total = 0
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val p = board.getPiece(sq)
            if (p == Piece.NONE || p.pieceType == PieceType.KING) continue
            val v = ChessMoveUtils.pieceValue(p.pieceType)
            if (p.pieceSide == side) total += v else total -= v
        }
        return total
    }
}
