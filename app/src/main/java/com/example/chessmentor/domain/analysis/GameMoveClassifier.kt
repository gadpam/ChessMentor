// domain/analysis/GameMoveClassifier.kt
package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.opening.BookMoveMatch
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Единый классификатор качества ходов, точности партии и фаз игры.
 *
 * Основан на откалиброванной модели вероятности победы Win% (Lichess Stockfish NNUE):
 * - Классификация ходов по потере ожидаемых очков (ΔWin%), а не по «сырым» сантипешкам:
 *   технические размены в выигранных позициях (+8.0 -> +5.0) не считаются зевками,
 *   а сдача равной позиции (+0.2 -> -2.1) честно помечается грубой ошибкой.
 * - BOOK ставится строго при совпадении с деревом дебютной теории и без потери качества.
 * - BRILLIANT (!!) требует реальной жертвы материала (с учётом легальности взятия и защиты поля)
 *   в не выигранной заранее позиции и сохранения/получения перевеса.
 * - GREAT_MOVE (!) отмечает точное наказание ошибки соперника, нахождение нетривиального мата
 *   или критический защитный ресурс.
 * - Точность партии вычисляется как среднее гармонического и взвешенного по волатильности
 *   значений точности ходов (методика Lichess), отдельно для игрока, соперника и трёх фаз партии.
 */
object GameMoveClassifier {

    // Коэффициент логистической кривой Win% (калибровка Lichess по партиям Stockfish NNUE)
    private const val WIN_PROB_MULTIPLIER = -0.00368208
    private const val EVAL_CAP_CP = 1000

    // Пороги потери Win% (в процентных пунктах 0..100)
    const val BEST_WIN_LOSS_MAX = 0.5
    const val EXCELLENT_WIN_LOSS_MAX = 2.0
    const val GOOD_WIN_LOSS_MAX = 5.5
    const val INACCURACY_WIN_LOSS_MAX = 10.5
    const val MISTAKE_WIN_LOSS_MAX = 20.0

    // Пороги для BOOK / BRILLIANT / GREAT_MOVE / MISSED_WIN
    const val BOOK_MAX_FULL_MOVE = 15
    const val BOOK_TRANSPOSITION_MAX_MOVE = 12
    private const val SACRIFICE_MIN_NET_CP = 180
    private const val CRUSHING_EVAL_BEFORE_CP = 500
    private const val CRUSHING_EVAL_AFTER_CP = 450
    private const val DEAD_LOST_EVAL_CP = -600

    enum class GamePhase(val displayName: String) {
        OPENING("Дебют"),
        MIDDLEGAME("Миттельшпиль"),
        ENDGAME("Эндшпиль")
    }

    enum class SacrificeKind {
        DIRECT_PIECE,
        INDIRECT_HANGING
    }

    data class SacrificeInfo(
        val kind: SacrificeKind,
        val sacrificedPiece: PieceType,
        val netOfferedCp: Int
    ) {
        fun pieceNameGenitive(): String = when (sacrificedPiece) {
            PieceType.KNIGHT -> "коня"
            PieceType.BISHOP -> "слона"
            PieceType.ROOK -> "ладьи"
            PieceType.QUEEN -> "ферзя"
            PieceType.PAWN -> "пешки"
            else -> "фигуры"
        }
    }

    enum class GreatMoveReason {
        PUNISHED_OPPONENT_MISTAKE,
        FOUND_FORCED_MATE,
        CRITICAL_DEFENSE
    }

    data class MoveClassification(
        val quality: MoveQuality,
        val winBefore: Double,
        val winAfter: Double,
        val winPercentLoss: Double,
        val rawCpLoss: Int,
        val cappedCpLoss: Int,
        val moveAccuracy: Double,
        val sacrifice: SacrificeInfo? = null,
        val greatReason: GreatMoveReason? = null,
        val bookOpeningName: String? = null,
        val bookEco: String? = null,
        val unpunishedOpponentMistake: Boolean = false
    )

    data class SideAnalysisSummary(
        val color: ChessColor,
        val accuracy: Double,
        val averageCpLoss: Int,
        val moveCount: Int,
        val blunders: Int,
        val mistakes: Int,
        val missedWins: Int,
        val inaccuracies: Int,
        val qualityCounts: Map<MoveQuality, Int>
    )

    data class PhaseAccuracyBreakdown(
        val opening: Double?,
        val middlegame: Double?,
        val endgame: Double?,
        val openingMoves: Int,
        val middlegameMoves: Int,
        val endgameMoves: Int
    ) {
        val hasAnyPhase: Boolean
            get() = opening != null || middlegame != null || endgame != null
    }

    // ==================== ВЕРОЯТНОСТЬ ПОБЕДЫ И ТОЧНОСТЬ ХОДА ====================

    /**
     * Переводит оценку позиции [evalWhiteCp] (с точки зрения белых) в вероятность победы [0..100]
     * для стороны [perspective].
     */
    fun winProbability(evalWhiteCp: Int, perspective: ChessColor): Double {
        val moverEval = toMoverEval(evalWhiteCp, perspective)
        return winProbabilityFromMoverEval(moverEval)
    }

    fun winProbabilityFromMoverEval(moverEval: Int): Double {
        if (ChessEngine.isMateScore(moverEval)) {
            return if (moverEval > 0) 100.0 else 0.0
        }
        val capped = moverEval.coerceIn(-EVAL_CAP_CP, EVAL_CAP_CP)
        val raw = 50.0 + 50.0 * (2.0 / (1.0 + exp(WIN_PROB_MULTIPLIER * capped)) - 1.0)
        return raw.coerceIn(0.0, 100.0)
    }

    /**
     * Точность одного хода [0..100] по потере Win% (формула Lichess).
     */
    fun calculateMoveAccuracy(
        winBefore: Double,
        winAfter: Double,
        isBook: Boolean = false
    ): Double {
        if (isBook || winAfter >= winBefore) return 100.0
        val winDrop = (winBefore - winAfter).coerceIn(0.0, 100.0)
        val raw = 103.1668 * exp(-0.04354 * winDrop) - 3.1669
        return raw.coerceIn(0.0, 100.0)
    }

    fun calculateMoveAccuracy(move: AnalyzedMove): Double {
        if (move.quality == MoveQuality.BOOK) return 100.0
        val wb = winProbability(move.evalBefore, move.color)
        val wa = winProbability(move.evalAfter, move.color)
        return calculateMoveAccuracy(wb, wa, isBook = false)
    }

    /**
     * Потеря в сантипешках (неотрицательная) с ограничением матовых и разгромных оценок для ACPL.
     */
    fun calculateCappedCpLoss(evalBeforeWhite: Int, evalAfterWhite: Int, color: ChessColor): Int {
        val before = clampEvalForAcpl(toMoverEval(evalBeforeWhite, color))
        val after = clampEvalForAcpl(toMoverEval(evalAfterWhite, color))
        return (before - after).coerceIn(0, EVAL_CAP_CP)
    }

    /**
     * Реальная потеря в сантипешках для хранения в AnalyzedMove/Mistake (неотрицательная).
     */
    fun calculateRawCpLoss(evalBeforeWhite: Int, evalAfterWhite: Int, color: ChessColor): Int {
        val before = toMoverEval(evalBeforeWhite, color)
        val after = toMoverEval(evalAfterWhite, color)

        val beforeIsMate = ChessEngine.isMateScore(before)
        val afterIsMate = ChessEngine.isMateScore(after)

        return when {
            !beforeIsMate && afterIsMate && after < 0 -> 1000
            beforeIsMate && before > 0 && !afterIsMate -> (1000 - after.coerceAtMost(900)).coerceAtLeast(100)
            beforeIsMate && afterIsMate -> {
                when {
                    before > 0 && after < 0 -> 1500
                    before > 0 && after > 0 -> {
                        val mBefore = abs(ChessEngine.getMateInMoves(before))
                        val mAfter = abs(ChessEngine.getMateInMoves(after))
                        if (mAfter > mBefore) ((mAfter - mBefore) * 15).coerceAtMost(150) else 0
                    }
                    else -> 0
                }
            }
            else -> (before - after).coerceAtLeast(0)
        }
    }

    // ==================== КЛАССИФИКАЦИЯ ХОДА ====================

    /**
     * Классифицирует ход с учётом Win%, форсированных матов, теории, жертв и контекста предыдущего хода.
     *
     * @param evalBefore оценка ДО хода (с точки зрения белых)
     * @param evalAfter оценка ПОСЛЕ хода (с точки зрения белых)
     * @param movingColor цвет стороны, сделавшей ход
     * @param moveNumber полный номер хода (1, 2, 3...)
     * @param playedEngineBest совпал ли ход с первой линией Stockfish
     * @param inBookSequence оставалась ли партия в дебютной книге до этого хода
     * @param bookMatch совпадение позиции после хода с базой дебютов (null если вне книги)
     * @param sacrifice обнаруженная реальная жертва материала (null если жертвы нет)
     * @param prevOpponentWinLoss потеря Win% соперника на предыдущем полуходе
     * @param isAutomaticRecapture является ли ход очевидным обратным взятием на том же поле
     * @param wasInCheckWithChoices находился ли король под шахом при >= 3 легальных ответах
     */
    fun classifyMove(
        evalBefore: Int,
        evalAfter: Int,
        movingColor: ChessColor,
        moveNumber: Int = 15,
        playedEngineBest: Boolean = false,
        inBookSequence: Boolean = false,
        bookMatch: BookMoveMatch? = null,
        sacrifice: SacrificeInfo? = null,
        prevOpponentWinLoss: Double = 0.0,
        isAutomaticRecapture: Boolean = false,
        wasInCheckWithChoices: Boolean = false
    ): MoveClassification {
        val moverBefore = toMoverEval(evalBefore, movingColor)
        val moverAfter = toMoverEval(evalAfter, movingColor)

        val winBefore = winProbabilityFromMoverEval(moverBefore)
        val winAfter = winProbabilityFromMoverEval(moverAfter)
        val winLoss = (winBefore - winAfter).coerceAtLeast(0.0)
        val rawCpLoss = calculateRawCpLoss(evalBefore, evalAfter, movingColor)
        val cappedCpLoss = calculateCappedCpLoss(evalBefore, evalAfter, movingColor)

        val wasWonMate = ChessEngine.isMateScore(moverBefore) && moverBefore > 0
        val wasLostMate = ChessEngine.isMateScore(moverBefore) && moverBefore < 0
        val isWonMate = ChessEngine.isMateScore(moverAfter) && moverAfter > 0
        val isLostMate = ChessEngine.isMateScore(moverAfter) && moverAfter < 0

        // 1. Честный теоретический ход (BOOK): только реальное совпадение с книгой без потери качества
        val eligibleForBook = bookMatch != null && !isLostMate && !wasWonMate && (
            (inBookSequence && moveNumber <= BOOK_MAX_FULL_MOVE) ||
                (bookMatch.isTabiya && moveNumber <= BOOK_TRANSPOSITION_MAX_MOVE)
            )
        if (eligibleForBook && winLoss <= GOOD_WIN_LOSS_MAX) {
            return MoveClassification(
                quality = MoveQuality.BOOK,
                winBefore = winBefore,
                winAfter = winAfter,
                winPercentLoss = winLoss,
                rawCpLoss = rawCpLoss,
                cappedCpLoss = cappedCpLoss,
                moveAccuracy = 100.0,
                bookOpeningName = bookMatch?.openingName,
                bookEco = bookMatch?.eco
            )
        }

        // 2. Переходы с форсированным матом
        if (!wasLostMate && isLostMate) {
            val mateQuality = when {
                winBefore >= 15.0 -> MoveQuality.BLUNDER
                winBefore >= 6.0 -> MoveQuality.MISTAKE
                else -> MoveQuality.INACCURACY
            }
            return buildResult(
                quality = mateQuality,
                winBefore = winBefore,
                winAfter = winAfter,
                winLoss = winLoss,
                rawCpLoss = rawCpLoss,
                cappedCpLoss = cappedCpLoss,
                unpunishedOpponentMistake = prevOpponentWinLoss >= 12.0 && winBefore >= 68.0
            )
        }

        if (wasLostMate && isLostMate) {
            // Позиция уже была форсированно проиграна — не раздуваем счётчик зевков
            val q = if (playedEngineBest) MoveQuality.BEST_MOVE else MoveQuality.GOOD
            return buildResult(q, winBefore, winAfter, winLoss, rawCpLoss, cappedCpLoss)
        }

        if (isWonMate) {
            val mateAfterMoves = abs(ChessEngine.getMateInMoves(moverAfter))
            val mateBeforeMoves = if (wasWonMate) abs(ChessEngine.getMateInMoves(moverBefore)) else Int.MAX_VALUE

            // Жертвенный форсированный мат (например, Bxh7+!! или мат Легаля Nxe5!!)
            if (sacrifice != null && winBefore < 92.0 && (playedEngineBest || mateAfterMoves <= mateBeforeMoves)) {
                return buildResult(
                    quality = MoveQuality.BRILLIANT,
                    winBefore = winBefore,
                    winAfter = winAfter,
                    winLoss = 0.0,
                    rawCpLoss = 0,
                    cappedCpLoss = 0,
                    sacrifice = sacrifice
                )
            }

            // Нахождение неочевидного форсированного мата в 2..10 ходов
            if (!wasWonMate && winBefore < 88.0 && mateAfterMoves in 2..10 && !isAutomaticRecapture) {
                return buildResult(
                    quality = MoveQuality.GREAT_MOVE,
                    winBefore = winBefore,
                    winAfter = winAfter,
                    winLoss = 0.0,
                    rawCpLoss = 0,
                    cappedCpLoss = 0,
                    greatReason = GreatMoveReason.FOUND_FORCED_MATE
                )
            }

            val q = when {
                playedEngineBest || !wasWonMate || mateAfterMoves <= mateBeforeMoves -> MoveQuality.BEST_MOVE
                mateAfterMoves <= mateBeforeMoves + 3 -> MoveQuality.EXCELLENT
                else -> MoveQuality.GOOD
            }
            return buildResult(q, winBefore, winAfter, winLoss, rawCpLoss, cappedCpLoss)
        }

        if (wasWonMate && !isWonMate) {
            val q = when {
                winAfter < 38.0 -> MoveQuality.BLUNDER
                winAfter < 84.0 -> MoveQuality.MISSED_WIN
                else -> MoveQuality.INACCURACY // Был мат, но позиция остаётся легко выигранной (+5.0 и выше)
            }
            return buildResult(
                quality = q,
                winBefore = winBefore,
                winAfter = winAfter,
                winLoss = winLoss,
                rawCpLoss = rawCpLoss,
                cappedCpLoss = cappedCpLoss,
                unpunishedOpponentMistake = q == MoveQuality.MISSED_WIN && prevOpponentWinLoss >= 12.0
            )
        }

        // 3. Блестящий ход (!!): точная жертва материала в не выигранной заранее позиции
        val isTopChoice = playedEngineBest || (winLoss <= 1.2 && rawCpLoss <= 15)
        if (sacrifice != null &&
            isTopChoice &&
            winBefore in 18.0..88.0 &&
            winAfter >= 46.0 &&
            winLoss <= 1.5
        ) {
            return buildResult(
                quality = MoveQuality.BRILLIANT,
                winBefore = winBefore,
                winAfter = winAfter,
                winLoss = winLoss,
                rawCpLoss = rawCpLoss,
                cappedCpLoss = cappedCpLoss,
                sacrifice = sacrifice
            )
        }

        // 4. Отличный ход (!): наказание ошибки соперника или точная защита под давлением
        val greatReason = detectGreatMoveReason(
            playedEngineBest = playedEngineBest,
            winBefore = winBefore,
            winAfter = winAfter,
            winLoss = winLoss,
            rawCpLoss = rawCpLoss,
            moveNumber = moveNumber,
            prevOpponentWinLoss = prevOpponentWinLoss,
            isAutomaticRecapture = isAutomaticRecapture,
            wasInCheckWithChoices = wasInCheckWithChoices
        )
        if (greatReason != null) {
            return buildResult(
                quality = MoveQuality.GREAT_MOVE,
                winBefore = winBefore,
                winAfter = winAfter,
                winLoss = winLoss,
                rawCpLoss = rawCpLoss,
                cappedCpLoss = cappedCpLoss,
                greatReason = greatReason
            )
        }

        // 5. Техническое упрощение в абсолютно выигранной позиции (+5.0 -> +4.5+) не является ошибкой
        if (moverBefore >= CRUSHING_EVAL_BEFORE_CP && moverAfter >= CRUSHING_EVAL_AFTER_CP) {
            val q = when {
                playedEngineBest || (winLoss <= BEST_WIN_LOSS_MAX && rawCpLoss <= 8) -> MoveQuality.BEST_MOVE
                winLoss <= EXCELLENT_WIN_LOSS_MAX -> MoveQuality.EXCELLENT
                else -> MoveQuality.GOOD
            }
            return buildResult(q, winBefore, winAfter, winLoss, rawCpLoss, cappedCpLoss)
        }

        // 6. Безнадёжно проигранная позиция (-6.0 -> -9.0) не создаёт ложных зевков
        if (moverBefore <= DEAD_LOST_EVAL_CP && moverAfter <= DEAD_LOST_EVAL_CP) {
            val q = if (playedEngineBest) MoveQuality.BEST_MOVE else MoveQuality.GOOD
            return buildResult(q, winBefore, winAfter, winLoss, rawCpLoss, cappedCpLoss)
        }

        // 7. Основная шкала качества по ΔWin%
        val unpunishedOppErr = prevOpponentWinLoss >= 12.0 && winBefore >= 68.0 && winLoss >= 15.0
        val quality = when {
            playedEngineBest || (winLoss <= BEST_WIN_LOSS_MAX && rawCpLoss <= 8) -> MoveQuality.BEST_MOVE
            winLoss <= EXCELLENT_WIN_LOSS_MAX -> MoveQuality.EXCELLENT
            winLoss <= GOOD_WIN_LOSS_MAX -> MoveQuality.GOOD
            winLoss <= INACCURACY_WIN_LOSS_MAX -> MoveQuality.INACCURACY
            winLoss <= MISTAKE_WIN_LOSS_MAX -> {
                if (winBefore >= 76.0 && winLoss >= 18.0 && winAfter >= 42.0) {
                    MoveQuality.MISSED_WIN
                } else {
                    MoveQuality.MISTAKE
                }
            }
            else -> {
                // Потеря > 20% Win%: если был выигранный перевес и позиция не стала проигранной — MISSED_WIN,
                // иначе полноценный грубый зевок BLUNDER
                if (winBefore >= 74.0 && winAfter >= 42.0) {
                    MoveQuality.MISSED_WIN
                } else {
                    MoveQuality.BLUNDER
                }
            }
        }

        return buildResult(
            quality = quality,
            winBefore = winBefore,
            winAfter = winAfter,
            winLoss = winLoss,
            rawCpLoss = rawCpLoss,
            cappedCpLoss = cappedCpLoss,
            unpunishedOpponentMistake = unpunishedOppErr &&
                (quality == MoveQuality.MISSED_WIN || quality == MoveQuality.MISTAKE || quality == MoveQuality.BLUNDER)
        )
    }

    private fun detectGreatMoveReason(
        playedEngineBest: Boolean,
        winBefore: Double,
        winAfter: Double,
        winLoss: Double,
        rawCpLoss: Int,
        moveNumber: Int,
        prevOpponentWinLoss: Double,
        isAutomaticRecapture: Boolean,
        wasInCheckWithChoices: Boolean
    ): GreatMoveReason? {
        if (isAutomaticRecapture) return null
        val isNearExact = playedEngineBest || (winLoss <= 0.8 && rawCpLoss <= 10)
        if (!isNearExact) return null

        // Точное наказание ошибки/зевка соперника в переломный момент партии
        val prevPlayerWin = (winBefore - prevOpponentWinLoss).coerceIn(0.0, 100.0)
        if (prevOpponentWinLoss >= 12.0 && prevPlayerWin < 74.0 && winAfter >= 60.0) {
            return GreatMoveReason.PUNISHED_OPPONENT_MISTAKE
        }

        // Единственная точная защита под шахом в сложной позиции
        if (playedEngineBest && wasInCheckWithChoices && moveNumber >= 8 && winBefore in 25.0..60.0 && winLoss <= 0.5) {
            return GreatMoveReason.CRITICAL_DEFENSE
        }

        return null
    }

    private fun buildResult(
        quality: MoveQuality,
        winBefore: Double,
        winAfter: Double,
        winLoss: Double,
        rawCpLoss: Int,
        cappedCpLoss: Int,
        sacrifice: SacrificeInfo? = null,
        greatReason: GreatMoveReason? = null,
        unpunishedOpponentMistake: Boolean = false
    ): MoveClassification {
        return MoveClassification(
            quality = quality,
            winBefore = winBefore,
            winAfter = winAfter,
            winPercentLoss = winLoss,
            rawCpLoss = rawCpLoss,
            cappedCpLoss = cappedCpLoss,
            moveAccuracy = calculateMoveAccuracy(winBefore, winAfter, isBook = quality == MoveQuality.BOOK),
            sacrifice = sacrifice,
            greatReason = greatReason,
            unpunishedOpponentMistake = unpunishedOpponentMistake
        )
    }

    // ==================== ДЕТЕКТОР РЕАЛЬНОЙ ЖЕРТВЫ МАТЕРИАЛА ====================

    /**
     * Определяет, жертвует ли ход [move] из позиции [boardBefore] реальный материал (от лёгкой фигуры):
     * - Прямая жертва: фигура ставится под легальный бой (незащищённой или под более дешёвую фигуру)
     *   с чистой отдачей >= 180 сП (например, Bxh7+!!, Nxf7!!, Rxe6!!, Qg6!!).
     * - Косвенная жертва: ход открывает под легальный выгодный бой собственную фигуру на другом поле
     *   (например, мат Легаля 5. Nxe5!! с отдачей ферзя на d1).
     *
     * Рутинные размены (Bxc6 bxc6, Qxd8+ Rxd8) и маты с защищённого поля (Qxf7#) исключаются.
     */
    fun detectMaterialSacrifice(boardBefore: Board, move: Move): SacrificeInfo? {
        val moverSide = boardBefore.sideToMove
        val oppSide = moverSide.flip()
        val movingPiece = boardBefore.getPiece(move.from)
        if (movingPiece == Piece.NONE || movingPiece.pieceSide != moverSide) return null

        val targetPiece = boardBefore.getPiece(move.to)
        val isEnPassant = movingPiece.pieceType == PieceType.PAWN &&
            move.from.file != move.to.file &&
            targetPiece == Piece.NONE
        val capturedValue = when {
            isEnPassant -> ChessMoveUtils.pieceValue(PieceType.PAWN)
            targetPiece != Piece.NONE && targetPiece.pieceSide == oppSide ->
                ChessMoveUtils.pieceValue(targetPiece.pieceType)
            else -> 0
        }

        val boardAfter = Board().apply {
            loadFromFen(boardBefore.fen)
            if (!doMove(move)) return null
        }

        val oppLegalMoves = runCatching { boardAfter.legalMoves() }.getOrNull().orEmpty()
        if (oppLegalMoves.isEmpty()) return null // Мат или пат — соперник не может ничего забрать

        // 1. Прямая жертва на поле назначения move.to
        var directSacrifice: SacrificeInfo? = null
        val placedPiece = boardAfter.getPiece(move.to)
        val placedType = placedPiece.pieceType
        if (placedPiece != Piece.NONE &&
            placedType != PieceType.PAWN &&
            placedType != PieceType.KING
        ) {
            val movedValue = ChessMoveUtils.pieceValue(placedType)
            val capturesOnDest = oppLegalMoves.filter { it.to == move.to }
            if (capturesOnDest.isNotEmpty()) {
                val minAttackerVal = capturesOnDest.minOf { cap ->
                    ChessMoveUtils.pieceValue(boardAfter.getPiece(cap.from).pieceType)
                }
                val isDefended = boardAfter.squareAttackedBy(move.to, moverSide) != 0L
                val netOffered = if (!isDefended) {
                    movedValue - capturedValue
                } else {
                    movedValue - capturedValue - minAttackerVal
                }

                if (netOffered >= SACRIFICE_MIN_NET_CP) {
                    directSacrifice = SacrificeInfo(
                        kind = SacrificeKind.DIRECT_PIECE,
                        sacrificedPiece = placedType,
                        netOfferedCp = netOffered
                    )
                }
            }
        }

        // 2. Косвенная (вскрытая) жертва: ход оставляет под новым выгодным боем другую свою фигуру
        // (если она крупнее прямой — как жертва ферзя в мате Легаля 5. Nxe5!! — выбираем её)
        var bestIndirect: SacrificeInfo? = null
        for (sq in Square.values()) {
            if (sq == Square.NONE || sq == move.to) continue
            val piece = boardAfter.getPiece(sq)
            if (piece == Piece.NONE || piece.pieceSide != moverSide) continue
            val pt = piece.pieceType
            if (pt == PieceType.PAWN || pt == PieceType.KING) continue

            val pieceVal = ChessMoveUtils.pieceValue(pt)
            val capsOnSq = oppLegalMoves.filter { it.to == sq }
            if (capsOnSq.isEmpty()) continue

            val minAttVal = capsOnSq.minOf { cap ->
                ChessMoveUtils.pieceValue(boardAfter.getPiece(cap.from).pieceType)
            }
            val isDefendedAfter = boardAfter.squareAttackedBy(sq, moverSide) != 0L
            val offeredAfter = if (!isDefendedAfter) pieceVal else pieceVal - minAttVal
            val netOffered = offeredAfter - capturedValue
            if (netOffered < SACRIFICE_MIN_NET_CP) continue

            // Проверяем, что именно этот ход вскрыл нападение или снял защиту с фигуры
            val wasExposedBefore = isSquareProfitablyAttacked(
                board = boardBefore,
                square = sq,
                ownerSide = moverSide,
                attackerSide = oppSide,
                pieceVal = pieceVal
            )
            if (!wasExposedBefore && (bestIndirect == null || netOffered > bestIndirect.netOfferedCp)) {
                bestIndirect = SacrificeInfo(
                    kind = SacrificeKind.INDIRECT_HANGING,
                    sacrificedPiece = pt,
                    netOfferedCp = netOffered
                )
            }
        }

        return when {
            directSacrifice != null && bestIndirect != null ->
                if (bestIndirect.netOfferedCp > directSacrifice.netOfferedCp) bestIndirect else directSacrifice
            directSacrifice != null -> directSacrifice
            else -> bestIndirect
        }
    }

    private fun isSquareProfitablyAttacked(
        board: Board,
        square: Square,
        ownerSide: Side,
        attackerSide: Side,
        pieceVal: Int
    ): Boolean {
        val attackersMask = board.squareAttackedBy(square, attackerSide)
        if (attackersMask == 0L) return false

        val isDefended = board.squareAttackedBy(square, ownerSide) != 0L
        if (!isDefended) return true

        var minAttacker = Int.MAX_VALUE
        var bits = attackersMask
        while (bits != 0L) {
            val idx = java.lang.Long.numberOfTrailingZeros(bits)
            if (idx in 0..63) {
                val attSq = Square.squareAt(idx)
                val attPiece = board.getPiece(attSq)
                if (attPiece != Piece.NONE && attPiece.pieceSide == attackerSide) {
                    val v = ChessMoveUtils.pieceValue(attPiece.pieceType)
                    if (v < minAttacker) minAttacker = v
                }
            }
            bits = bits and (bits - 1L)
        }
        return minAttacker < Int.MAX_VALUE && (pieceVal - minAttacker) >= SACRIFICE_MIN_NET_CP
    }

    /**
     * Проверяет, является ли [move] очевидным автоматическим взятием на поле предыдущего взятия соперника.
     */
    fun isAutomaticRecapture(
        boardBefore: Board,
        move: Move,
        prevMove: Move?,
        prevCapturedValue: Int
    ): Boolean {
        if (prevMove == null || prevCapturedValue < 100) return false
        if (move.to != prevMove.to) return false
        val target = boardBefore.getPiece(move.to)
        if (target == Piece.NONE || target.pieceSide == boardBefore.sideToMove) return false
        val targetVal = ChessMoveUtils.pieceValue(target.pieceType)
        val moverVal = ChessMoveUtils.pieceValue(boardBefore.getPiece(move.from).pieceType)
        return targetVal >= 100 && moverVal <= targetVal + 220
    }

    // ==================== ТОЧНОСТЬ ПАРТИИ, ACPL И СВОДКА СТОРОНЫ ====================

    /**
     * Вычисляет итоговую точность стороны [color] по списку разобранных ходов (методика Lichess):
     * среднее между взвешенным по волатильности и гармоническим средними точности ходов.
     */
    fun calculateGameAccuracy(moves: List<AnalyzedMove>, color: ChessColor): Double {
        val sideMoves = moves
            .filter { it.color == color }
            .sortedBy { it.moveIndex }
        return calculateAccuracyForOrderedMoves(sideMoves)
    }

    /**
     * Резервный расчёт точности стороны напрямую по массиву оценок (для старых партий без полных AnalyzedMove).
     */
    fun calculateGameAccuracyFromEvaluations(
        evaluations: List<Int>,
        color: ChessColor,
        firstMoveIsWhite: Boolean = true
    ): Double {
        if (evaluations.size < 2) return 100.0
        val winPairs = mutableListOf<Pair<Double, Double>>()
        for (i in 1 until evaluations.size) {
            val moveIndex = i - 1
            val isWhite = if (firstMoveIsWhite) moveIndex % 2 == 0 else moveIndex % 2 != 0
            val moveColor = if (isWhite) ChessColor.WHITE else ChessColor.BLACK
            if (moveColor == color) {
                val wb = winProbability(evaluations[i - 1], color)
                val wa = winProbability(evaluations[i], color)
                winPairs.add(wb to calculateMoveAccuracy(wb, wa))
            }
        }
        return aggregateWeightedAndHarmonicAccuracy(winPairs)
    }

    private fun calculateAccuracyForOrderedMoves(orderedSideMoves: List<AnalyzedMove>): Double {
        if (orderedSideMoves.isEmpty()) return 100.0
        val pairs = orderedSideMoves.map { move ->
            val wb = winProbability(move.evalBefore, move.color)
            val acc = calculateMoveAccuracy(move)
            wb to acc
        }
        return aggregateWeightedAndHarmonicAccuracy(pairs)
    }

    private fun aggregateWeightedAndHarmonicAccuracy(winBeforeAndAcc: List<Pair<Double, Double>>): Double {
        val n = winBeforeAndAcc.size
        if (n == 0) return 100.0
        if (n < 3) {
            val mean = winBeforeAndAcc.sumOf { it.second } / n
            return roundOneDecimal(mean.coerceIn(0.0, 100.0))
        }

        val windowSize = (n / 10).coerceIn(2, 8).coerceAtMost(n)
        val windowStdDevs = DoubleArray(n - windowSize + 1) { start ->
            var sum = 0.0
            for (k in start until start + windowSize) {
                sum += winBeforeAndAcc[k].first
            }
            val mean = sum / windowSize
            var sqSum = 0.0
            for (k in start until start + windowSize) {
                val d = winBeforeAndAcc[k].first - mean
                sqSum += d * d
            }
            sqrt(sqSum / windowSize).coerceIn(0.5, 12.0)
        }

        var weightedSum = 0.0
        var totalWeight = 0.0
        var invAccSum = 0.0

        for (i in 0 until n) {
            val lo = (i - windowSize + 1).coerceAtLeast(0)
            val hi = i.coerceAtMost(windowStdDevs.lastIndex)
            var wSum = 0.0
            var wCnt = 0
            for (wIdx in lo..hi) {
                wSum += windowStdDevs[wIdx]
                wCnt++
            }
            val weight = if (wCnt > 0) wSum / wCnt else 1.0
            val acc = winBeforeAndAcc[i].second
            weightedSum += acc * weight
            totalWeight += weight
            invAccSum += 1.0 / acc.coerceAtLeast(1.0)
        }

        val weightedMean = if (totalWeight > 0.0) weightedSum / totalWeight else 100.0
        val harmonicMean = if (invAccSum > 0.0) n / invAccSum else 100.0
        val combined = ((weightedMean + harmonicMean) / 2.0).coerceIn(0.0, 100.0)
        return roundOneDecimal(combined)
    }

    /**
     * Средняя потеря сантипешек (ACPL) для стороны [color].
     * Как на Lichess, исключает чисто теоретические ходы BOOK (если есть самостоятельные ходы)
     * и ограничивает каждую отдельную потерю 1000 сП, чтобы маты не искажали среднее.
     */
    fun calculateAverageCentipawnLoss(moves: List<AnalyzedMove>, color: ChessColor): Int {
        val sideMoves = moves.filter { it.color == color }
        if (sideMoves.isEmpty()) return 0
        val nonBook = sideMoves.filter { it.quality != MoveQuality.BOOK }
        val sample = nonBook.ifEmpty { sideMoves }
        val total = sample.sumOf { move ->
            calculateCappedCpLoss(move.evalBefore, move.evalAfter, color)
        }
        return total / sample.size
    }

    /**
     * Полная сводка качества игры для указанной стороны (игрока или соперника).
     */
    fun summarizeSide(moves: List<AnalyzedMove>, color: ChessColor): SideAnalysisSummary {
        val sideMoves = moves.filter { it.color == color }.sortedBy { it.moveIndex }
        val counts = sideMoves.groupingBy { it.quality }.eachCount()
        return SideAnalysisSummary(
            color = color,
            accuracy = calculateAccuracyForOrderedMoves(sideMoves),
            averageCpLoss = calculateAverageCentipawnLoss(sideMoves, color),
            moveCount = sideMoves.size,
            blunders = counts[MoveQuality.BLUNDER] ?: 0,
            mistakes = counts[MoveQuality.MISTAKE] ?: 0,
            missedWins = counts[MoveQuality.MISSED_WIN] ?: 0,
            inaccuracies = counts[MoveQuality.INACCURACY] ?: 0,
            qualityCounts = counts
        )
    }

    // ==================== ФАЗЫ ПАРТИИ И ТОЧНОСТЬ ПО ФАЗАМ ====================

    /**
     * Делит последовательность полуходов на монотонные фазы OPENING -> MIDDLEGAME -> ENDGAME
     * по реальному материалу на доске и степени развития лёгких фигур (по аналогии с Lichess Divider).
     */
    fun detectGamePhases(
        fensBefore: List<String>,
        qualities: List<MoveQuality?> = emptyList(),
        startFullMove: Int = 1,
        firstMoveIsWhite: Boolean = true
    ): List<GamePhase> {
        if (fensBefore.isEmpty()) return emptyList()

        var currentPhase = GamePhase.OPENING
        return fensBefore.mapIndexed { index, fen ->
            val moveNumber = startFullMove + ((index + if (firstMoveIsWhite) 0 else 1) / 2)
            val quality = qualities.getOrNull(index)
            val snapshot = inspectBoardPhaseSnapshot(fen)

            if (currentPhase != GamePhase.ENDGAME && snapshot.isEndgameMaterial) {
                currentPhase = GamePhase.ENDGAME
            } else if (currentPhase == GamePhase.OPENING && quality != MoveQuality.BOOK) {
                val leftOpening = moveNumber > 14 ||
                    (moveNumber > 6 && snapshot.undevelopedBackRankMinors <= 1) ||
                    (moveNumber > 4 && snapshot.majorAndMinorCount <= 10) ||
                    snapshot.mixedBackRank
                if (leftOpening) {
                    currentPhase = GamePhase.MIDDLEGAME
                }
            }
            currentPhase
        }
    }

    /**
     * Определяет фазу отдельной позиции по FEN и номеру хода (для тематической классификации ошибок).
     */
    fun detectSinglePositionPhase(fen: String, moveNumber: Int): GamePhase {
        val snap = inspectBoardPhaseSnapshot(fen)
        return when {
            snap.isEndgameMaterial -> GamePhase.ENDGAME
            moveNumber <= 12 && snap.undevelopedBackRankMinors >= 2 && snap.majorAndMinorCount >= 11 ->
                GamePhase.OPENING
            else -> GamePhase.MIDDLEGAME
        }
    }

    /**
     * Рассчитывает точность стороны [color] отдельно в дебюте, миттельшпиле и эндшпиле.
     * Если в конкретной фазе сторона сделала менее 2 ходов, возвращает null (честный прочерк).
     */
    fun calculatePhaseAccuracy(
        moves: List<AnalyzedMove>,
        color: ChessColor,
        fensBefore: List<String>,
        startFullMove: Int = 1,
        firstMoveIsWhite: Boolean = true
    ): PhaseAccuracyBreakdown {
        if (fensBefore.isEmpty() || moves.isEmpty()) {
            return PhaseAccuracyBreakdown(null, null, null, 0, 0, 0)
        }
        val moveByIndex = moves.associateBy { it.moveIndex }
        val qualities = List(fensBefore.size) { idx -> moveByIndex[idx]?.quality }
        val phases = detectGamePhases(fensBefore, qualities, startFullMove, firstMoveIsWhite)

        val byPhase = mutableMapOf<GamePhase, MutableList<AnalyzedMove>>()
        for (move in moves) {
            if (move.color != color) continue
            val phase = phases.getOrNull(move.moveIndex) ?: continue
            byPhase.getOrPut(phase) { mutableListOf() }.add(move)
        }

        val openingList = byPhase[GamePhase.OPENING].orEmpty().sortedBy { it.moveIndex }
        val middleList = byPhase[GamePhase.MIDDLEGAME].orEmpty().sortedBy { it.moveIndex }
        val endList = byPhase[GamePhase.ENDGAME].orEmpty().sortedBy { it.moveIndex }

        return PhaseAccuracyBreakdown(
            opening = if (openingList.size >= 2) calculateAccuracyForOrderedMoves(openingList) else null,
            middlegame = if (middleList.size >= 2) calculateAccuracyForOrderedMoves(middleList) else null,
            endgame = if (endList.size >= 2) calculateAccuracyForOrderedMoves(endList) else null,
            openingMoves = openingList.size,
            middlegameMoves = middleList.size,
            endgameMoves = endList.size
        )
    }

    private data class BoardPhaseSnapshot(
        val queens: Int,
        val rooks: Int,
        val minors: Int,
        val nonPawnMaterial: Int,
        val majorAndMinorCount: Int,
        val undevelopedBackRankMinors: Int,
        val mixedBackRank: Boolean
    ) {
        // Стартовый нематериальный вес без пешек: 2*(9 + 10 + 12) = 62 (14 фигур).
        // Эндшпиль: <= 6 фигур (например, по ладье и две лёгкие / ладья + слон vs ладья + конь),
        // либо суммарный вес <= 22, либо без ферзей при весе <= 28 (например, две ладьи + лёгкая на сторону).
        val isEndgameMaterial: Boolean
            get() = majorAndMinorCount <= 6 ||
                nonPawnMaterial <= 22 ||
                (queens == 0 && nonPawnMaterial <= 28)
    }

    private fun inspectBoardPhaseSnapshot(fen: String): BoardPhaseSnapshot {
        val boardPart = fen.trim().substringBefore(' ')
        val ranks = boardPart.split('/')
        if (ranks.size != 8) {
            return BoardPhaseSnapshot(2, 4, 8, 62, 14, 8, false)
        }

        var queens = 0
        var rooks = 0
        var minors = 0
        var undevelopedMinors = 0
        var whiteCastled = false
        var blackCastled = false
        var whiteQueenDeveloped = true
        var blackQueenDeveloped = true

        for ((fenRankIdx, rankStr) in ranks.withIndex()) {
            // fenRankIdx = 0 -> 8-я горизонталь (чёрные), fenRankIdx = 7 -> 1-я горизонталь (белые)
            var file = 0
            for (ch in rankStr) {
                if (ch in '1'..'8') {
                    file += ch - '0'
                    continue
                }
                when (ch) {
                    'Q', 'q' -> queens++
                    'R', 'r' -> rooks++
                    'B', 'b', 'N', 'n' -> minors++
                }
                // Неразвитые кони и слоны на начальных полях b1,c1,f1,g1 / b8,c8,f8,g8
                if (fenRankIdx == 7 && (
                        (ch == 'N' && (file == 1 || file == 6)) ||
                            (ch == 'B' && (file == 2 || file == 5))
                        )
                ) {
                    undevelopedMinors++
                }
                if (fenRankIdx == 0 && (
                        (ch == 'n' && (file == 1 || file == 6)) ||
                            (ch == 'b' && (file == 2 || file == 5))
                        )
                ) {
                    undevelopedMinors++
                }
                if (fenRankIdx == 7 && ch == 'K' && (file == 6 || file == 2)) whiteCastled = true
                if (fenRankIdx == 7 && ch == 'Q' && file == 3) whiteQueenDeveloped = false
                if (fenRankIdx == 0 && ch == 'k' && (file == 6 || file == 2)) blackCastled = true
                if (fenRankIdx == 0 && ch == 'q' && file == 3) blackQueenDeveloped = false
                file++
            }
        }

        val nonPawnMaterial = queens * 9 + rooks * 5 + minors * 3
        val majorAndMinor = queens + rooks + minors
        // Дебют завершён, когда обе стороны рокировали и вывели все лёгкие фигуры
        // (либо вывели ферзей при не более чем 2 неразвитых лёгких фигурах)
        val developmentCompleted = whiteCastled && blackCastled && (
            undevelopedMinors == 0 ||
                (whiteQueenDeveloped && blackQueenDeveloped && undevelopedMinors <= 2)
            )

        return BoardPhaseSnapshot(
            queens = queens,
            rooks = rooks,
            minors = minors,
            nonPawnMaterial = nonPawnMaterial,
            majorAndMinorCount = majorAndMinor,
            undevelopedBackRankMinors = undevelopedMinors,
            mixedBackRank = developmentCompleted
        )
    }

    /**
     * Проверяет, есть ли в позиции [fen] исключительно пешки и короли (пешечный эндшпиль).
     */
    fun isPurePawnEndgame(fen: String): Boolean {
        val snap = inspectBoardPhaseSnapshot(fen)
        return snap.majorAndMinorCount == 0
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

    private fun toMoverEval(evalWhiteCp: Int, color: ChessColor): Int =
        if (color == ChessColor.WHITE) evalWhiteCp else -evalWhiteCp

    private fun clampEvalForAcpl(moverEval: Int): Int {
        if (ChessEngine.isMateScore(moverEval)) {
            return if (moverEval > 0) EVAL_CAP_CP else -EVAL_CAP_CP
        }
        return moverEval.coerceIn(-EVAL_CAP_CP, EVAL_CAP_CP)
    }

    private fun roundOneDecimal(value: Double): Double =
        round(value * 10.0) / 10.0
}
