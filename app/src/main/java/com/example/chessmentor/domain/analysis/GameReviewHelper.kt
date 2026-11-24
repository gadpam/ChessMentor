// domain/analysis/GameReviewHelper.kt
package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.KeyMoment
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.PredefinedThemes
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.training.PuzzleQualityEvaluator
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Чистый JVM-помощник интерактивного разбора партии (без зависимостей от Android SDK):
 * - навигация и фильтрация по ключевым моментам (ошибкам, лучшим ходам, зевкам соперника);
 * - проверка попытки в режиме «Исправить ошибку» на доске разбора + двухступенчатые подсказки;
 * - преобразование главной линии Stockfish (UCI PV) в пошаговые состояния доски и SAN-нотацию;
 * - форматирование часов `{[%clk ...]}` и диагностика цейтнота на конкретном полуходе;
 * - подсчёт материального баланса и тренерское резюме по итогам партии.
 */
object GameReviewHelper {

    /** Порог жёсткого цейтнота (в секундах). */
    const val TIME_TROUBLE_SECONDS = 20

    /** Порог нехватки времени для мягкого предупреждения (в секундах). */
    const val LOW_TIME_SECONDS = 45

    /** Допуск в сантипешках для засчитывания равноценного альтернативного хода при исправлении ошибки. */
    const val EQUIVALENT_BEST_TOLERANCE_CP = 25

    private val BETTER_MARKER_REGEX = Regex("""(?:^|\s)Сильнее:\s*""")
    private val REFUTATION_MARKER_REGEX = Regex("""(?:^|\s)Опровержение:\s*""")

    /**
     * Структурированные части комментария к неудачному ходу:
     * причина без спойлера лучшего ответа, линия «Сильнее» и линия «Опровержение».
     */
    data class StructuredMoveComment(
        val reason: String?,
        val betterLine: String?,
        val refutationLine: String?,
        val isStructured: Boolean
    )

    fun parseMoveComment(comment: String?): StructuredMoveComment {
        val raw = comment?.trim().orEmpty()
        if (raw.isEmpty()) {
            return StructuredMoveComment(null, null, null, isStructured = false)
        }
        val betterMatch = BETTER_MARKER_REGEX.find(raw)
        val refMatch = REFUTATION_MARKER_REGEX.find(raw)
        if (betterMatch == null && refMatch == null) {
            return StructuredMoveComment(
                reason = raw,
                betterLine = null,
                refutationLine = null,
                isStructured = false
            )
        }

        val markers = listOfNotNull(
            betterMatch?.let { "better" to it },
            refMatch?.let { "ref" to it }
        ).sortedBy { it.second.range.first }

        val firstStart = markers.first().second.range.first
        val reason = raw.substring(0, firstStart).trim().takeIf { it.isNotEmpty() }

        var betterLine: String? = null
        var refutationLine: String? = null
        for ((idx, entry) in markers.withIndex()) {
            val contentStart = entry.second.range.last + 1
            val contentEnd = if (idx + 1 < markers.size) {
                markers[idx + 1].second.range.first
            } else {
                raw.length
            }
            val value = raw.substring(contentStart, contentEnd).trim().takeIf { it.isNotEmpty() }
            if (entry.first == "better") {
                betterLine = value
            } else {
                refutationLine = value
            }
        }

        return StructuredMoveComment(
            reason = reason,
            betterLine = betterLine,
            refutationLine = refutationLine,
            isStructured = true
        )
    }

    // ==================== ФИЛЬТРЫ И НАВИГАЦИЯ ПО КЛЮЧЕВЫМ МОМЕНТАМ ====================

    enum class MomentFilter(val label: String) {
        ALL("Все"),
        PLAYER_MISTAKES("Ваши ошибки"),
        HIGHLIGHTS("Сильные"),
        OPPONENT_MISTAKES("Соперник")
    }

    /**
     * Отбирает и упорядочивает по ходу партии ключевые моменты для заданного фильтра.
     */
    fun filterKeyMoments(
        moments: List<KeyMoment>,
        filter: MomentFilter
    ): List<KeyMoment> {
        val filtered = when (filter) {
            MomentFilter.ALL -> moments.filter { m ->
                when (m.quality) {
                    MoveQuality.BRILLIANT,
                    MoveQuality.GREAT_MOVE,
                    MoveQuality.BLUNDER,
                    MoveQuality.MISSED_WIN,
                    MoveQuality.MISTAKE -> true
                    MoveQuality.INACCURACY -> m.isPlayerMove
                    else -> false
                }
            }
            MomentFilter.PLAYER_MISTAKES -> moments.filter { m ->
                m.isPlayerMove && m.quality in MISTAKE_QUALITIES
            }
            MomentFilter.HIGHLIGHTS -> moments.filter { m ->
                m.isPlayerMove && m.quality in HIGHLIGHT_QUALITIES
            }
            MomentFilter.OPPONENT_MISTAKES -> moments.filter { m ->
                !m.isPlayerMove && m.quality in OPPONENT_SIGNIFICANT_QUALITIES
            }
        }
        return filtered.sortedBy { it.moveIndex }
    }

    /**
     * Индексы полуходов для быстрой навигации «Предыдущий / Следующий ключевой момент» на доске.
     * По умолчанию включает все ошибки игрока, его блестящие/отличные ходы и грубые зевки соперника.
     */
    fun navigableMomentIndices(
        analyzedMoves: List<AnalyzedMove>,
        mistakes: List<Mistake>,
        playerColor: ChessColor,
        filter: MomentFilter = MomentFilter.ALL
    ): List<Int> {
        if (analyzedMoves.isNotEmpty()) {
            val moments = analyzedMoves.map { it.toKeyMoment(playerColor) }
            val primary = filterKeyMoments(moments, filter).map { it.moveIndex }.distinct().sorted()
            if (primary.isNotEmpty() || filter != MomentFilter.ALL) {
                return primary
            }
        }
        // Резерв для старых партий, где сохранены только записи mistakes без analyzed_moves
        if (mistakes.isEmpty()) return emptyList()
        return mistakes
            .map { m ->
                val base = (m.moveNumber - 1) * 2
                if (m.color == ChessColor.WHITE) base else base + 1
            }
            .filter { it >= 0 }
            .distinct()
            .sorted()
    }

    fun findNextMomentIndex(currentMoveIndex: Int, sortedIndices: List<Int>): Int? =
        sortedIndices.firstOrNull { it > currentMoveIndex }

    fun findPreviousMomentIndex(currentMoveIndex: Int, sortedIndices: List<Int>): Int? =
        sortedIndices.lastOrNull { it < currentMoveIndex }

    // ==================== РЕЖИМ «ИСПРАВИТЬ ОШИБКУ» НА ДОСКЕ ====================

    enum class RetryVerdict {
        /** Найден лучший ход движка. */
        EXACT_BEST,

        /** Повторён тот же ошибочный ход, что и в партии. */
        SAME_AS_GAME_MISTAKE,

        /** Другой легальный ход (не совпадает с лучшим ходом движка). */
        ALTERNATIVE_LEGAL,

        /** Нелегальный или некорректный ход. */
        ILLEGAL
    }

    data class RetryEvaluation(
        val verdict: RetryVerdict,
        val attemptedSan: String,
        val attemptedUci: String,
        val expectedBestSan: String,
        val expectedBestUci: String,
        val message: String
    )

    data class RetryHint(
        val level: Int,
        val fromSquare: String,
        val toSquare: String?,
        val bestSan: String,
        val bestUci: String,
        val message: String
    )

    /**
     * Проверяет попытку пользователя исправить собственный ошибочный ход в позиции [fenBefore].
     * Корректно сопоставляет SAN и UCI, включая превращения пешек (`e8=Q+` ↔ `e7e8q`) и рокировки.
     */
    fun evaluateRetryAttempt(
        fenBefore: String,
        attemptedNotation: String,
        expectedBestNotation: String,
        playedMistakeNotation: String? = null
    ): RetryEvaluation {
        val board = Board()
        val loaded = runCatching { board.loadFromFen(fenBefore) }.isSuccess
        if (!loaded) {
            return RetryEvaluation(
                verdict = RetryVerdict.ILLEGAL,
                attemptedSan = attemptedNotation,
                attemptedUci = "",
                expectedBestSan = expectedBestNotation,
                expectedBestUci = "",
                message = "Не удалось загрузить позицию для проверки хода"
            )
        }

        val bestUci = ChessMoveUtils.sanOrUciToUci(expectedBestNotation, fenBefore).orEmpty()
        val bestSan = if (bestUci.isNotEmpty()) {
            ChessMoveUtils.uciToSan(bestUci, fenBefore)
        } else {
            expectedBestNotation
        }

        val attemptedMove = ChessMoveUtils.sanOrUciToMove(attemptedNotation, fenBefore)
        if (attemptedMove == null || !ChessMoveUtils.isStrictlyLegal(board, attemptedMove)) {
            return RetryEvaluation(
                verdict = RetryVerdict.ILLEGAL,
                attemptedSan = attemptedNotation,
                attemptedUci = "",
                expectedBestSan = bestSan,
                expectedBestUci = bestUci,
                message = "Этот ход невозможен по правилам"
            )
        }

        val attemptedUci = ChessMoveUtils.moveToUci(attemptedMove)
        val attemptedSan = ChessMoveUtils.uciToSan(attemptedUci, fenBefore)
        val mistakeUci = playedMistakeNotation
            ?.takeIf { it.isNotBlank() }
            ?.let { ChessMoveUtils.sanOrUciToUci(it, fenBefore) }

        return when {
            bestUci.isNotEmpty() && attemptedUci.equals(bestUci, ignoreCase = true) -> RetryEvaluation(
                verdict = RetryVerdict.EXACT_BEST,
                attemptedSan = attemptedSan,
                attemptedUci = attemptedUci,
                expectedBestSan = bestSan,
                expectedBestUci = bestUci,
                message = "Верно! $attemptedSan — сильнейший ход в этой позиции."
            )
            !mistakeUci.isNullOrEmpty() && attemptedUci.equals(mistakeUci, ignoreCase = true) -> RetryEvaluation(
                verdict = RetryVerdict.SAME_AS_GAME_MISTAKE,
                attemptedSan = attemptedSan,
                attemptedUci = attemptedUci,
                expectedBestSan = bestSan,
                expectedBestUci = bestUci,
                message = "Ход $attemptedSan был сыгран в партии и ведёт к потере. Поищите более сильное продолжение!"
            )
            else -> RetryEvaluation(
                verdict = RetryVerdict.ALTERNATIVE_LEGAL,
                attemptedSan = attemptedSan,
                attemptedUci = attemptedUci,
                expectedBestSan = bestSan,
                expectedBestUci = bestUci,
                message = "Ход $attemptedSan уступает сильнейшему решению. Попробуйте ещё раз или возьмите подсказку."
            )
        }
    }

    /**
     * Формирует подсказку для режима «Исправить ошибку»:
     * - `level == 1`: подсвечивает только фигуру (поле `from`), не раскрывая конечную клетку;
     * - `level >= 2`: показывает полную стрелку `from → to` и ход в SAN.
     */
    fun buildRetryHint(
        fenBefore: String,
        expectedBestNotation: String,
        level: Int
    ): RetryHint? {
        val uci = ChessMoveUtils.sanOrUciToUci(expectedBestNotation, fenBefore) ?: return null
        if (uci.length < 4) return null
        val from = uci.substring(0, 2).lowercase(Locale.ROOT)
        val to = uci.substring(2, 4).lowercase(Locale.ROOT)
        val san = ChessMoveUtils.uciToSan(uci, fenBefore)
        val normalizedLevel = level.coerceIn(1, 2)
        return if (normalizedLevel == 1) {
            RetryHint(
                level = 1,
                fromSquare = from,
                toSquare = null,
                bestSan = san,
                bestUci = uci,
                message = "Подсказка: обратите внимание на фигуру на поле $from."
            )
        } else {
            RetryHint(
                level = 2,
                fromSquare = from,
                toSquare = to,
                bestSan = san,
                bestUci = uci,
                message = "Сильнейший ход: $san ($from → $to)."
            )
        }
    }

    // ==================== ПРЕОБРАЗОВАНИЕ ЛИНИИ STOCKFISH (PV) И ВЕТОК ====================

    data class VariationStep(
        val uci: String,
        val san: String,
        val formattedNotation: String,
        val moveNumber: Int,
        val color: ChessColor,
        val fenBefore: String,
        val fenAfter: String
    )

    /**
     * Преобразует последовательность ходов (UCI или SAN) из позиции [startFen]
     * в проверенный список пошаговых состояний с корректной нумерацией ходов.
     */
    fun buildVariationSteps(
        startFen: String,
        moves: List<String>,
        maxPlies: Int = 8
    ): List<VariationStep> {
        if (startFen.isBlank() || moves.isEmpty() || maxPlies <= 0) return emptyList()
        val board = Board()
        if (runCatching { board.loadFromFen(startFen) }.isFailure) return emptyList()

        val result = ArrayList<VariationStep>(moves.size.coerceAtMost(maxPlies))
        for (raw in moves) {
            if (result.size >= maxPlies) break
            val fenBefore = board.fen
            val move = ChessMoveUtils.sanOrUciToMove(raw, fenBefore) ?: break
            if (!ChessMoveUtils.isStrictlyLegal(board, move)) break

            val isWhite = board.sideToMove == Side.WHITE
            val moveNo = board.moveCounter.coerceAtLeast(1)
            val uci = ChessMoveUtils.moveToUci(move)
            val san = ChessMoveUtils.uciToSan(uci, fenBefore)
            if (!board.doMove(move)) break

            val formatted = when {
                isWhite -> "$moveNo. $san"
                result.isEmpty() -> "$moveNo... $san"
                else -> san
            }
            result.add(
                VariationStep(
                    uci = uci,
                    san = san,
                    formattedNotation = formatted,
                    moveNumber = moveNo,
                    color = if (isWhite) ChessColor.WHITE else ChessColor.BLACK,
                    fenBefore = fenBefore,
                    fenAfter = board.fen
                )
            )
        }
        return result
    }

    /**
     * Собирает компактную читаемую строку главной линии движка, например:
     * `"14... Nxd5 15. exd5 Qxd5 16. Nf3"`.
     */
    fun formatVariationLine(steps: List<VariationStep>): String =
        steps.joinToString(" ") { it.formattedNotation }

    /**
     * Переводит оценку UCI (`score` со стороны ходящего в [fen]) в оценку со стороны белых.
     */
    fun sideToMoveScoreToWhite(scoreFromSideToMove: Int, fen: String): Int {
        val parts = fen.trim().split(' ')
        val isBlackToMove = parts.getOrNull(1) == "b"
        return if (isBlackToMove) -scoreFromSideToMove else scoreFromSideToMove
    }

    // ==================== ЧАСЫ, ЦЕЙТНОТ И МАТЕРИАЛЬНЫЙ БАЛАНС ====================

    /**
     * Форматирует оставшееся время в секундах: `"3:02"`, `"0:14"`, `"1:05:20"`.
     */
    fun formatClock(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun isTimeTrouble(seconds: Int?): Boolean =
        seconds != null && seconds in 0 until TIME_TROUBLE_SECONDS

    fun isLowTime(seconds: Int?): Boolean =
        seconds != null && seconds in 0 until LOW_TIME_SECONDS

    /**
     * Возвращает последнее известное время [color] на момент полухода [currentMoveIndex]
     * (или начальное показание часов, если игрок ещё не сделал ход).
     */
    fun clockForSideAtPly(
        clocksByPly: Map<Int, Int>,
        color: ChessColor,
        currentMoveIndex: Int,
        firstMoveIsWhite: Boolean = true
    ): Int? {
        if (clocksByPly.isEmpty()) return null
        fun isSidePly(ply: Int): Boolean {
            val isWhitePly = if (firstMoveIsWhite) ply % 2 == 0 else ply % 2 != 0
            return (color == ChessColor.WHITE) == isWhitePly
        }

        if (currentMoveIndex >= 0) {
            for (ply in currentMoveIndex downTo 0) {
                if (isSidePly(ply)) {
                    clocksByPly[ply]?.let { return it }
                }
            }
        }
        // На старте партии (до первого хода этой стороны) показываем первое зафиксированное время
        val firstPly = if ((color == ChessColor.WHITE) == firstMoveIsWhite) 0 else 1
        return clocksByPly[firstPly]
    }

    /**
     * Материальный перевес белых в пешках (положительный — у белых лишний материал,
     * отрицательный — у чёрных).
     */
    fun materialDiffWhitePawns(fen: String): Int {
        val placement = fen.substringBefore(' ')
        var diff = 0
        for (ch in placement) {
            diff += when (ch) {
                'P' -> 1
                'N', 'B' -> 3
                'R' -> 5
                'Q' -> 9
                'p' -> -1
                'n', 'b' -> -3
                'r' -> -5
                'q' -> -9
                else -> 0
            }
        }
        return diff
    }

    // ==================== ТРЕНЕРСКОЕ РЕЗЮМЕ ПАРТИИ И МОСТ В ТРЕНИРОВКУ ====================

    data class TurningPoint(
        val moveIndex: Int,
        val moveNumber: Int,
        val color: ChessColor,
        val san: String,
        val bestMoveSan: String?,
        val winPercentBefore: Int,
        val winPercentAfter: Int,
        val winPercentDrop: Int,
        val inTimeTrouble: Boolean
    ) {
        val formattedMove: String
            get() = if (color == ChessColor.WHITE) "$moveNumber. $san" else "$moveNumber... $san"
    }

    data class CoachReport(
        val headline: String,
        val bullets: List<String>,
        val turningPoint: TurningPoint?,
        val weakestPhaseLabel: String?,
        val strongestPhaseLabel: String?,
        val timeTroubleMistakes: Int,
        val totalPlayerMistakes: Int,
        val missedOpponentBlunders: Int,
        val trainableMistakesCount: Int
    )

    /**
     * Подсчитывает количество ошибок игрока в партии, пригодных для отработки в тренажёре.
     */
    fun countTrainableMistakes(
        mistakes: List<Mistake>,
        analyzedMoves: List<AnalyzedMove>,
        playerColor: ChessColor
    ): Int {
        if (mistakes.isEmpty()) return 0
        val byMoveAndColor = analyzedMoves.associateBy { it.moveNumber to it.color }
        return PuzzleQualityEvaluator.selectTrainableMistakes(
            mistakes = mistakes,
            analyzedByMoveAndColor = byMoveAndColor,
            playerColor = playerColor
        ).size
    }

    /**
     * Формирует осмысленный тренерский вывод по партии:
     * переломный момент (по падению `Win%`), слабейшая и сильнейшая фаза,
     * влияние цейтнота на ошибки и неиспользованные зевки соперника.
     */
    fun buildCoachReport(
        playerColor: ChessColor,
        analyzedMoves: List<AnalyzedMove>,
        mistakes: List<Mistake> = emptyList(),
        phaseAccuracy: GameMoveClassifier.PhaseAccuracyBreakdown? = null,
        clocksByPly: Map<Int, Int> = emptyMap(),
        outcome: PgnParser.PlayerOutcome = PgnParser.PlayerOutcome.UNKNOWN
    ): CoachReport? {
        if (analyzedMoves.isEmpty()) return null

        val sorted = analyzedMoves.sortedBy { it.moveIndex }
        val playerMoves = sorted.filter { it.color == playerColor }
        if (playerMoves.isEmpty()) return null

        val playerMistakeMoves = playerMoves.filter { it.quality in MISTAKE_QUALITIES }
        val seriousMistakes = playerMoves.filter {
            it.quality == MoveQuality.BLUNDER ||
                it.quality == MoveQuality.MISTAKE ||
                it.quality == MoveQuality.MISSED_WIN
        }

        // 1. Главный переломный момент игрока (максимальная потеря шансов Win%)
        var worstTurningPoint: TurningPoint? = null
        var maxDrop = 0.0
        for (m in playerMistakeMoves) {
            val wpBefore = GameMoveClassifier.winProbability(m.evalBefore, playerColor)
            val wpAfter = GameMoveClassifier.winProbability(m.evalAfter, playerColor)
            val drop = wpBefore - wpAfter
            if (drop > maxDrop && drop >= 10.0) {
                maxDrop = drop
                val clk = clocksByPly[m.moveIndex]
                worstTurningPoint = TurningPoint(
                    moveIndex = m.moveIndex,
                    moveNumber = m.moveNumber,
                    color = m.color,
                    san = m.san,
                    bestMoveSan = m.bestMove?.takeIf { it.isNotBlank() && it != m.san },
                    winPercentBefore = wpBefore.roundToInt().coerceIn(0, 100),
                    winPercentAfter = wpAfter.roundToInt().coerceIn(0, 100),
                    winPercentDrop = drop.roundToInt().coerceAtLeast(1),
                    inTimeTrouble = isTimeTrouble(clk)
                )
            }
        }

        // 2. Корреляция ошибок с цейтнотом
        val timeTroubleMistakes = if (clocksByPly.isNotEmpty()) {
            playerMistakeMoves.count { m -> isTimeTrouble(clocksByPly[m.moveIndex]) }
        } else {
            0
        }

        // 3. Неиспользованные ошибки соперника (соперник ошибся, и сразу следующим ходом игрок упустил перевес)
        var missedOppBlunders = 0
        for (i in 0 until sorted.lastIndex) {
            val cur = sorted[i]
            val next = sorted[i + 1]
            if (cur.color != playerColor &&
                next.color == playerColor &&
                next.moveIndex == cur.moveIndex + 1 &&
                cur.quality in setOf(MoveQuality.BLUNDER, MoveQuality.MISTAKE) &&
                next.quality in setOf(MoveQuality.BLUNDER, MoveQuality.MISTAKE, MoveQuality.MISSED_WIN)
            ) {
                missedOppBlunders++
            }
        }

        // 4. Сильнейшая и слабейшая фазы партии (среди фаз минимум с 3 ходами)
        val phases = buildList {
            if (phaseAccuracy != null) {
                if (phaseAccuracy.opening != null && phaseAccuracy.openingMoves >= 3) {
                    add("Дебют" to phaseAccuracy.opening)
                }
                if (phaseAccuracy.middlegame != null && phaseAccuracy.middlegameMoves >= 3) {
                    add("Миттельшпиль" to phaseAccuracy.middlegame)
                }
                if (phaseAccuracy.endgame != null && phaseAccuracy.endgameMoves >= 3) {
                    add("Эндшпиль" to phaseAccuracy.endgame)
                }
            }
        }
        val strongest = phases.maxByOrNull { it.second }
        val weakest = phases.minByOrNull { it.second }
            ?.takeIf { phases.size >= 2 && (strongest!!.second - it.second) >= 7.0 }

        val brilliantOrGreat = playerMoves.count {
            it.quality == MoveQuality.BRILLIANT || it.quality == MoveQuality.GREAT_MOVE
        }

        // 5. Формируем заголовок и конкретные рекомендации
        val headline = when {
            seriousMistakes.isEmpty() && playerMistakeMoves.size <= 1 ->
                "Очень чистая партия — практически без тактических промахов!"
            timeTroubleMistakes >= 2 && timeTroubleMistakes * 2 >= playerMistakeMoves.size ->
                "Главная причина потерь в этой партии — цейтнот"
            missedOppBlunders >= 2 ->
                "Соперник давал шансы, но реализация подвела"
            worstTurningPoint != null && worstTurningPoint.winPercentDrop >= 35 ->
                "Исход партии решил один критический эпизод на ${worstTurningPoint.moveNumber}-м ходу"
            weakest != null && weakest.second < 72.0 ->
                "Зона роста по этой партии — ${weakest.first.lowercase(Locale.ROOT)}"
            outcome == PgnParser.PlayerOutcome.WIN && seriousMistakes.isNotEmpty() ->
                "Победа достигнута, но в партии были опасные моменты"
            else ->
                "Разберите ключевые ошибки, чтобы закрепить правильные решения"
        }

        val bullets = buildList {
            worstTurningPoint?.let { tp ->
                val tpMistake = mistakes.find { it.moveNumber == tp.moveNumber && it.color == tp.color }
                val tpAnalyzed = sorted.find { it.moveIndex == tp.moveIndex }
                val parsed = parseMoveComment(tpMistake?.comment ?: tpAnalyzed?.comment)
                val themeName = tpMistake?.themeId?.let { tid ->
                    PredefinedThemes.all().find { it.id == tid }?.name
                }
                val reasonSuffix = when {
                    parsed.isStructured && !parsed.reason.isNullOrBlank() ->
                        " — ${parsed.reason.trimEnd('.')}"
                    !themeName.isNullOrBlank() ->
                        " (тема: ${themeName.lowercase(Locale.ROOT)})"
                    else -> ""
                }
                val clkPart = if (tp.inTimeTrouble) " в цейтноте" else ""
                add(
                    "Переломный момент: ${tp.formattedMove}$clkPart снизил шансы с ${tp.winPercentBefore}% до ${tp.winPercentAfter}%$reasonSuffix."
                )
            }
            if (timeTroubleMistakes > 0) {
                add(
                    "Ошибок в жёстком цейтноте (< $TIME_TROUBLE_SECONDS с): $timeTroubleMistakes из ${playerMistakeMoves.size}."
                )
            }
            if (missedOppBlunders > 0) {
                val word = if (missedOppBlunders == 1) "раз" else "раза"
                add(
                    "Не использован зевок соперника: $missedOppBlunders $word — обращайте внимание на ответные тактические удары."
                )
            }
            if (weakest != null && strongest != null) {
                add(
                    "Лучшая фаза — ${strongest.first.lowercase(Locale.ROOT)} (${strongest.second.roundToInt()}%), " +
                        "просела точность в фазе «${weakest.first}» (${weakest.second.roundToInt()}%)."
                )
            } else if (strongest != null && strongest.second >= 85.0) {
                add("Уверенная игра в фазе «${strongest.first}» (точность ${strongest.second.roundToInt()}%).")
            }
            if (brilliantOrGreat > 0) {
                add("Найдено сильных ключевых решений (!! / !): $brilliantOrGreat.")
            }
        }

        val trainableCount = countTrainableMistakes(mistakes, analyzedMoves, playerColor)

        return CoachReport(
            headline = headline,
            bullets = bullets,
            turningPoint = worstTurningPoint,
            weakestPhaseLabel = weakest?.let { "${it.first} (${it.second.roundToInt()}%)" },
            strongestPhaseLabel = strongest?.let { "${it.first} (${it.second.roundToInt()}%)" },
            timeTroubleMistakes = timeTroubleMistakes,
            totalPlayerMistakes = playerMistakeMoves.size,
            missedOpponentBlunders = missedOppBlunders,
            trainableMistakesCount = trainableCount
        )
    }

    /**
     * Форматирует изменение оценки (в пешках или мате) со знаком.
     */
    fun formatSignedEvalDelta(deltaCp: Int): String {
        if (ChessEngine.isMateScore(deltaCp) || abs(deltaCp) > 50_000) {
            return if (deltaCp > 0) "+Мат" else "-Мат"
        }
        val pawns = deltaCp / 100.0
        return when {
            deltaCp > 0 -> String.format(Locale.US, "+%.1f", pawns)
            deltaCp < 0 -> String.format(Locale.US, "%.1f", pawns)
            else -> "0.0"
        }
    }

    private val MISTAKE_QUALITIES = setOf(
        MoveQuality.BLUNDER,
        MoveQuality.MISSED_WIN,
        MoveQuality.MISTAKE,
        MoveQuality.INACCURACY
    )

    private val OPPONENT_SIGNIFICANT_QUALITIES = setOf(
        MoveQuality.BLUNDER,
        MoveQuality.MISSED_WIN,
        MoveQuality.MISTAKE
    )

    private val HIGHLIGHT_QUALITIES = setOf(
        MoveQuality.BRILLIANT,
        MoveQuality.GREAT_MOVE,
        MoveQuality.BEST_MOVE
    )
}
