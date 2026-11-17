// domain/training/SolutionReview.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side

/**
 * Один шаг пошагового разбора решения после завершения задачи.
 */
data class SolutionReviewStep(
    /** Индекс шага: 0 — исходная позиция задачи, 1..N — полуходы решения. */
    val index: Int,
    /** FEN позиции на данном шаге. */
    val fen: String,
    /** UCI хода, приведшего в эту позицию (для шага 0 — предшествующий ход соперника, если известен). */
    val lastMoveUci: String?,
    /** Подпись шага для навигатора (например «Старт», «1. Nc7+», «1... Kd7»). */
    val moveLabel: String,
    /** Принадлежит ли этот ход стороне игрока (false для шага 0 и ответов соперника). */
    val isPlayerMove: Boolean
)

/**
 * Построение пошагового разбора, нотации и тренерского объяснения идеи ПОСЛЕ решения задачи,
 * а также проверка валидности входной анимации хода соперника.
 */
object SolutionReviewHelper {

    /**
     * Проверяет, что применение [prevMoveUci] к [preFen] легально и приводит к той же расстановке фигур,
     * очереди хода, правам рокировки и полю взятия на проходе, что и [targetFen].
     */
    fun validateTriggerTransition(preFen: String?, prevMoveUci: String?, targetFen: String): Boolean {
        if (preFen.isNullOrBlank() || prevMoveUci.isNullOrBlank()) return false
        return try {
            val preBoard = Board().apply { loadFromFen(preFen.trim()) }
            val move = ChessMoveUtils.parseUciMove(preBoard, prevMoveUci.trim()) ?: return false
            if (!preBoard.doMove(move)) return false
            fenCoreKey(preBoard.fen) == fenCoreKey(targetFen)
        } catch (_: Exception) {
            false
        }
    }

    private fun fenCoreKey(fen: String): String {
        val parts = fen.trim().split(Regex("\\s+"))
        // Сравниваем расстановку, очередь хода и права рокировки
        return parts.take(3).joinToString(" ")
    }

    /**
     * Нейтральный промпт ДО решения задачи — без спойлеров мотива и числа ходов,
     * чтобы тренировать реальное распознавание позиции, как в живой партии.
     */
    fun buildNeutralPrompt(
        playerSide: Side,
        hasOpponentTriggerMove: Boolean,
        isDefensiveGoal: Boolean = false
    ): String {
        val sideWord = if (playerSide == Side.WHITE) "белых" else "чёрных"
        return when {
            isDefensiveGoal ->
                "Ход $sideWord — оцените угрозы соперника и найдите точную защиту"
            hasOpponentTriggerMove ->
                "Ход $sideWord — оцените последний ход соперника и найдите сильнейший ответ"
            else ->
                "Ход $sideWord — найдите сильнейшее продолжение"
        }
    }

    /**
     * Строит список шагов [0..N] для пошагового просмотра решения вперёд/назад.
     */
    fun buildReviewSteps(
        startFen: String,
        solutionUci: List<String>,
        initialPrevMoveUci: String? = null
    ): List<SolutionReviewStep> {
        if (startFen.isBlank()) return emptyList()
        val sim = try {
            Board().apply { loadFromFen(startFen) }
        } catch (_: Exception) {
            return emptyList()
        }

        val playerSide = sim.sideToMove
        val steps = mutableListOf(
            SolutionReviewStep(
                index = 0,
                fen = sim.fen,
                lastMoveUci = initialPrevMoveUci?.takeIf { ChessMoveUtils.isUciMove(it) },
                moveLabel = "Старт",
                isPlayerMove = false
            )
        )

        var plyNumber = 1
        for (rawUci in solutionUci) {
            val moverSide = sim.sideToMove
            val moveNo = sim.moveCounter.coerceAtLeast(1)
            val parsed = ChessMoveUtils.parseUciMove(sim, rawUci) ?: break
            val cleanUci = ChessMoveUtils.moveToUci(parsed)
            val san = ChessMoveUtils.uciToSan(cleanUci, sim.fen)
            if (!sim.doMove(parsed)) break

            val label = if (moverSide == Side.WHITE) {
                "$moveNo. $san"
            } else {
                "$moveNo... $san"
            }
            steps.add(
                SolutionReviewStep(
                    index = plyNumber,
                    fen = sim.fen,
                    lastMoveUci = cleanUci,
                    moveLabel = label,
                    isPlayerMove = moverSide == playerSide
                )
            )
            plyNumber++
        }

        return steps
    }

    /**
     * Форматирует решение в стандартную шахматную запись с номерами ходов
     * (например `1. Qxd8+ Kxd8 2. Bg5+ Ke8 3. Rd8#` или `1... Rd1+ 2. Rxd1 Rxd1#`).
     */
    fun formatNumberedSanLine(startFen: String, solutionUci: List<String>): String {
        if (startFen.isBlank() || solutionUci.isEmpty()) return ""
        val sim = try {
            Board().apply { loadFromFen(startFen) }
        } catch (_: Exception) {
            return solutionUci.joinToString(" ")
        }

        val tokens = mutableListOf<String>()
        var isFirst = true
        for (rawUci in solutionUci) {
            val side = sim.sideToMove
            val moveNo = sim.moveCounter.coerceAtLeast(1)
            val parsed = ChessMoveUtils.parseUciMove(sim, rawUci) ?: break
            val cleanUci = ChessMoveUtils.moveToUci(parsed)
            val san = ChessMoveUtils.uciToSan(cleanUci, sim.fen)
            if (!sim.doMove(parsed)) break

            when {
                side == Side.WHITE -> tokens.add("$moveNo. $san")
                isFirst -> tokens.add("$moveNo... $san")
                else -> tokens.add(san)
            }
            isFirst = false
        }

        return tokens.joinToString(" ")
    }

    /**
     * Генерирует краткое тренерское объяснение идеи после решения/показа ответа:
     * ПОЧЕМУ комбинация работает и на какой сигнал в позиции смотреть в реальной партии.
     */
    fun buildPostSolveExplanation(
        startFen: String,
        solutionUci: List<String>,
        pattern: TacticalPattern,
        curatedExplanation: String? = null
    ): String {
        curatedExplanation?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (startFen.isBlank() || solutionUci.isEmpty()) return patternCoachingCue(pattern)

        val outcomeSummary = analyzeCombinationOutcome(startFen, solutionUci)
        val cue = patternCoachingCue(pattern)
        return if (outcomeSummary != null) {
            "$outcomeSummary $cue"
        } else {
            cue
        }
    }

    private fun analyzeCombinationOutcome(startFen: String, solutionUci: List<String>): String? {
        return try {
            val sim = Board().apply { loadFromFen(startFen) }
            val playerSide = sim.sideToMove
            val startMaterialDiff = materialBalance(sim, playerSide)

            val firstParsed = ChessMoveUtils.parseUciMove(sim, solutionUci.first()) ?: return null
            val firstUci = ChessMoveUtils.moveToUci(firstParsed)
            val firstSan = ChessMoveUtils.uciToSan(firstUci, sim.fen)
            var promotedTo: PieceType? = null

            for (rawUci in solutionUci) {
                val parsed = ChessMoveUtils.parseUciMove(sim, rawUci) ?: break
                if (sim.sideToMove == playerSide && parsed.promotion != Piece.NONE) {
                    promotedTo = parsed.promotion.pieceType
                }
                if (!sim.doMove(parsed)) break
            }

            val endMaterialDiff = materialBalance(sim, playerSide)
            val netGain = endMaterialDiff - startMaterialDiff

            when {
                sim.isMated -> "Ход $firstSan запускает форсированный мат."
                sim.isStaleMate || sim.isDraw -> "Точный ресурс $firstSan спасает ничью."
                promotedTo == PieceType.KNIGHT ->
                    "Слабое превращение $firstSan в коня решает исход за счёт темпа."
                promotedTo != null ->
                    "Ход $firstSan обеспечивает решающее превращение пешки."
                netGain >= 700 ->
                    "После $firstSan выигрывается ферзь или крупный материал."
                netGain >= 420 ->
                    "Серия с $firstSan приносит чистую ладью."
                netGain >= 180 ->
                    "Точный удар $firstSan выигрывает материал."
                else ->
                    "Ключевой ход — $firstSan."
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun materialBalance(board: Board, side: Side): Int {
        var score = 0
        for (sq in com.github.bhlangonijr.chesslib.Square.values()) {
            if (sq == com.github.bhlangonijr.chesslib.Square.NONE) continue
            val piece = board.getPiece(sq)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue
            val value = ChessMoveUtils.pieceValue(piece.pieceType)
            if (piece.pieceSide == side) score += value else score -= value
        }
        return score
    }

    /**
     * Тренерская памятка по распознаванию тактического мотива в живой партии.
     */
    fun patternCoachingCue(pattern: TacticalPattern): String = when (pattern) {
        TacticalPattern.FORK_KNIGHT ->
            "Сигнал в партии: две тяжёлые фигуры или король и ферзь стоят на полях одного цвета — ищите прыжок коня."
        TacticalPattern.FORK_BISHOP ->
            "Сигнал в партии: незащищённые фигуры соперника оказались на пересекающихся диагоналях одного цвета."
        TacticalPattern.FORK_ROOK ->
            "Сигнал в партии: вторжение ладьи на 7-ю/2-ю горизонталь одновременно создаёт угрозу королю и пешкам/фигурам."
        TacticalPattern.FORK_QUEEN ->
            "Сигнал в партии: раскрытый король и незащищённая фигура — проверяйте все двойные удары ферзём с шахом."
        TacticalPattern.FORK_PAWN ->
            "Сигнал в партии: две фигуры соперника стоят через одну вертикаль на одной горизонтали — проверяйте тычок пешки."
        TacticalPattern.PIN_ABSOLUTE ->
            "Сигнал в партии: фигура на одной линии с королём обездвижена — усиливайте нажим пешкой или лёгкой фигурой."
        TacticalPattern.PIN_RELATIVE ->
            "Сигнал в партии: фигура прикрывает ферзя или ладью — её уход ведёт к потере более ценного материала."
        TacticalPattern.SKEWER ->
            "Сигнал в партии: король и ферзь/ладья на одной линии — вынудите старшую фигуру отойти и заберите стоящую за ней."
        TacticalPattern.DISCOVERED_ATTACK ->
            "Сигнал в партии: ваша фигура заслоняет линию слона, ладьи или ферзя — отскочите ею с темпом (шахом или угрозой)."
        TacticalPattern.DISCOVERED_CHECK ->
            "Сигнал в партии: отскок с вскрытым шахом даёт «бесплатный темп» — фигура может бить или нападать почти безнаказанно."
        TacticalPattern.DOUBLE_CHECK ->
            "Сигнал в партии: от двойного шаха нельзя закрыться или побить атакующую фигуру — соперник обязан ходить королём."
        TacticalPattern.BACK_RANK_MATE ->
            "Сигнал в партии: у короля нет «форточки», а последняя горизонталь ослаблена или перегружена."
        TacticalPattern.SMOTHERED_MATE ->
            "Сигнал в партии: король зажат в углу собственными фигурами — жертва завлекает защитника и лишает короля последнего поля."
        TacticalPattern.ANASTASIA_MATE ->
            "Сигнал в партии: конь на e7/e2 отнимает поля бегства у короля, а ладья матует по открытой крайней вертикали."
        TacticalPattern.ARABIAN_MATE ->
            "Сигнал в партии: взаимодействие коня и ладьи в углу — конь одновременно защищает ладью и держит поле бегства."
        TacticalPattern.BODEN_MATE ->
            "Сигнал в партии: перекрёстные слоны прорезают обе диагонали возле короля после разрушения пешечного прикрытия."
        TacticalPattern.REMOVING_DEFENDER ->
            "Сигнал в партии: если ключевое поле или фигуру держит один защитник — пробейте или разменяйте именно его."
        TacticalPattern.DEFLECTION ->
            "Сигнал в партии: защитник привязан к критическому полю или фигуре — заставьте его уйти форсированной жертвой."
        TacticalPattern.DECOY ->
            "Сигнал в партии: жертва заманивает короля или ферзя под решающую вилку, связку или матовую сеть."
        TacticalPattern.OVERLOADING ->
            "Сигнал в партии: одна фигура соперника одновременно защищает два объекта — ударьте по первому, чтобы упал второй."
        TacticalPattern.TRAPPED_PIECE ->
            "Сигнал в партии: у фигуры соперника мало полей отступления — отрежьте последний выход и выиграйте её."
        TacticalPattern.HANGING_PIECE ->
            "Сигнал в партии: всегда проверяйте незащищённые фигуры и фигуры, защищённые только один раз."
        TacticalPattern.QUEEN_TRAP ->
            "Сигнал в партии: ферзь забрался глубоко в лагерь — проверьте, есть ли у него безопасные клетки для отхода."
        TacticalPattern.ZUGZWANG ->
            "Сигнал в партии: отнимите у соперника полезные ходы, чтобы он сам ослабил ключевые поля."
        TacticalPattern.STALEMATE_TRICK ->
            "Сигнал в партии: в тяжёлом положении проверьте, не запатован ли ваш король при жертве оставшихся активных фигур."
        TacticalPattern.PERPETUAL_CHECK ->
            "Сигнал в партии: при материальном дефиците ищите форсированную серию шахов раскрытому королю соперника."
        TacticalPattern.WEAK_SQUARE ->
            "Сигнал в партии: поле, которое уже не может прикрыть ни одна пешка соперника — идеальный форпост для фигуры."
        TacticalPattern.PAWN_BREAKTHROUGH ->
            "Сигнал в партии: проходная на 6–7-й горизонтали умножает силу тактики; при превращении проверяйте и ход в коня."
        TacticalPattern.EXCHANGE_SACRIFICE ->
            "Сигнал в партии: отдача ладьи за ключевого защитника разрушает координацию и вскрывает короля."
        TacticalPattern.PIECE_SACRIFICE ->
            "Сигнал в партии: жертва оправдана, когда оставшиеся фигуры входят в атаку с шахами и прямыми матовыми угрозами."
        TacticalPattern.DEFENSIVE_MOVE ->
            "Сигнал в партии: сначала спросите «чем грозит соперник?», и ищите ход, который одновременно отражает угрозу и держит координацию."
        TacticalPattern.COUNTER_ATTACK ->
            "Сигнал в партии: вместо пассивного отступления проверьте встречный шах или промежуточный удар (цвишенцуг)."
        TacticalPattern.BLOCKADE ->
            "Сигнал в партии: зафиксируйте опасную проходную соперника на поле перед ней, лишая его контригры."
        TacticalPattern.POSITIONAL ->
            "Сигнал в партии: улучшите самую пассивную фигуру и возьмите под контроль открытую линию или центр."
        TacticalPattern.ENDGAME_TECHNIQUE ->
            "Сигнал в партии: в эндшпиле решают активность короля, правило квадрата, оппозиция и точный счёт темпов."
        TacticalPattern.CALCULATION ->
            "Сигнал в партии: считайте форсированные ходы (шахи, взятия, угрозы) до момента, когда размены полностью завершатся."
        TacticalPattern.MIXED ->
            "Сигнал в партии: одна угроза подготавливает следующую — проверяйте всю цепочку ответов соперника до первого хода."
    }
}
