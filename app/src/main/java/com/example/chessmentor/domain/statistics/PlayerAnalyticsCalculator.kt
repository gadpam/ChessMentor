// domain/statistics/PlayerAnalyticsCalculator.kt
package com.example.chessmentor.domain.statistics

import com.example.chessmentor.domain.analysis.GameMoveClassifier
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.ProblemTheme
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.training.AttemptQuality
import com.example.chessmentor.domain.training.HintUsage
import com.example.chessmentor.domain.training.PatternMastery
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.training.TrainingRatingCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Чистый JVM-движок аналитики партий, дебютного репертуара (ECO за белых и чёрных),
 * фаз партии, контролей времени, цейтнота, динамики точности/рейтинга и активности.
 *
 * Работает полностью поверх существующей схемы Room DB v5 без миграций и без зависимостей от Android SDK.
 */
object PlayerAnalyticsCalculator {

    private const val DAY_MS = 86_400_000L
    private const val OPENING_LAST_MOVE = 12
    private const val MIDDLEGAME_LAST_MOVE = 30
    private const val OPENING_EXIT_MIN_MOVE = 8
    private const val OPENING_EXIT_MAX_MOVE = 12
    private const val MAX_ACCURACY_TREND_POINTS = 20
    private const val ROLLING_WINDOW = 5
    private const val MAX_RATING_TREND_POINTS = 24
    private const val ACTIVITY_DAYS = 14
    private const val MAX_EXIT_EVAL_CP = 900
    private const val MAX_ACPL_CAP = 500

    private val DAY_LABEL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

    // ==================== ПЕРИОД И ТРЕНДЫ ====================

    enum class StatsPeriod(val displayName: String, val days: Int?) {
        ALL("Всё время", null),
        LAST_30_DAYS("30 дней", 30),
        LAST_7_DAYS("7 дней", 7);

        fun cutoffMillis(nowMs: Long): Long? =
            days?.let { nowMs - it.toLong() * DAY_MS }
    }

    enum class TrendDirection(val label: String) {
        IMPROVING("↑ Растёт"),
        STABLE("→ Стабильно"),
        DECLINING("↓ Снижается"),
        NOT_ENOUGH_DATA("Мало данных")
    }

    enum class GamePhaseKind(val displayName: String, val moveRangeHint: String) {
        OPENING("Дебют", "ходы 1–12"),
        MIDDLEGAME("Миттельшпиль", "ходы 13–30"),
        ENDGAME("Эндшпиль", "ходы 31+")
    }

    // ==================== МОДЕЛИ ОТЧЁТА ====================

    /** Сводка результатов и качества игры (общая, за белых или за чёрных). */
    data class ColorPerformance(
        val label: String,
        val color: ChessColor?,
        val gamesCount: Int = 0,
        val analyzedCount: Int = 0,
        val wins: Int = 0,
        val draws: Int = 0,
        val losses: Int = 0,
        val unknownResults: Int = 0,
        val scorePercent: Double? = null,
        val winRatePercent: Double? = null,
        val averageAccuracy: Double? = null,
        val averageAcpl: Int? = null,
        val blundersPerGame: Double? = null,
        val bestWinOpponentElo: Int? = null,
        val accuracySampleSize: Int = 0
    ) {
        val decidedGames: Int get() = wins + draws + losses

        fun wdlSummary(): String = "+$wins =$draws −$losses"
    }

    /** Агрегированная статистика по одному дебюту за конкретный цвет. */
    data class OpeningLineStat(
        val openingName: String,
        val primaryEco: String?,
        val ecoCodes: List<String>,
        val color: ChessColor,
        val gamesCount: Int,
        val analyzedCount: Int,
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val unknownResults: Int,
        val scorePercent: Double?,
        val averageAccuracy: Double?,
        val openingPhaseAccuracy: Double?,
        val averageBookMoves: Double?,
        val earlyMistakesCount: Int,
        val averageExitEvalCp: Int?,
        val keyIdea: String?,
        val commonMistakeTip: String?,
        val latestGameId: Long?,
        val latestPlayedAt: Long,
        val searchQuery: String
    ) {
        val decidedGames: Int get() = wins + draws + losses

        fun displayTitle(): String =
            if (!primaryEco.isNullOrBlank()) "$primaryEco · $openingName" else openingName

        /** Форматированная оценка выхода из дебюта со стороны игрока (`+0.6` / `-1.2`). */
        fun formattedExitEval(): String? {
            val cp = averageExitEvalCp ?: return null
            val pawns = cp / 100.0
            return if (pawns >= 0.0) {
                String.format(Locale.US, "+%.1f", pawns)
            } else {
                String.format(Locale.US, "%.1f", pawns)
            }
        }
    }

    /** Сводка по тому классификации ECO (`A`..`E`). */
    data class EcoFamilyStat(
        val code: Char,
        val title: String,
        val subtitle: String,
        val gamesCount: Int,
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val scorePercent: Double?,
        val averageAccuracy: Double?
    ) {
        val decidedGames: Int get() = wins + draws + losses
    }

    /** Полный дебютный репертуар игрока. */
    data class OpeningRepertoireSummary(
        val whiteOpenings: List<OpeningLineStat> = emptyList(),
        val blackOpenings: List<OpeningLineStat> = emptyList(),
        val ecoFamilies: List<EcoFamilyStat> = emptyList(),
        val bestWhite: OpeningLineStat? = null,
        val weakestWhite: OpeningLineStat? = null,
        val bestBlack: OpeningLineStat? = null,
        val weakestBlack: OpeningLineStat? = null,
        val recognizedGamesCount: Int = 0,
        val uniqueOpeningsCount: Int = 0,
        val averageBookMoves: Double? = null
    ) {
        val hasAnyOpenings: Boolean
            get() = whiteOpenings.isNotEmpty() || blackOpenings.isNotEmpty()
    }

    /** Показатели игрока в одной фазе партии. */
    data class PhasePerformance(
        val phase: GamePhaseKind,
        val accuracy: Double?,
        val movesCount: Int,
        val bookMoves: Int,
        val greatAndBrilliant: Int,
        val inaccuracies: Int,
        val mistakes: Int,
        val missedWins: Int,
        val blunders: Int
    ) {
        val totalErrors: Int get() = inaccuracies + mistakes + missedWins + blunders

        val errorRatePercent: Double
            get() = if (movesCount > 0) {
                roundOne((totalErrors * 100.0) / movesCount)
            } else {
                0.0
            }
    }

    /** Сравнение дебюта, миттельшпиля и эндшпиля. */
    data class PhaseOverview(
        val opening: PhasePerformance,
        val middlegame: PhasePerformance,
        val endgame: PhasePerformance,
        val strongest: PhasePerformance? = null,
        val weakest: PhasePerformance? = null,
        val gapPercent: Double? = null
    ) {
        val all: List<PhasePerformance> get() = listOf(opening, middlegame, endgame)
        val hasData: Boolean get() = all.any { it.movesCount > 0 }
    }

    /** Распределение всех проанализированных ходов игрока по категориям качества. */
    data class MoveQualityDistribution(
        val totalPlayerMoves: Int = 0,
        val brilliant: Int = 0,
        val great: Int = 0,
        val best: Int = 0,
        val excellent: Int = 0,
        val good: Int = 0,
        val book: Int = 0,
        val inaccuracy: Int = 0,
        val mistake: Int = 0,
        val missedWin: Int = 0,
        val blunder: Int = 0,
        val opponentBlundersTotal: Int = 0,
        val opponentBlundersPunished: Int = 0
    ) {
        val cleanMoves: Int
            get() = brilliant + great + best + excellent + good + book

        val cleanMovePercent: Double
            get() = if (totalPlayerMoves > 0) {
                roundOne((cleanMoves * 100.0) / totalPlayerMoves)
            } else {
                0.0
            }

        val opponentBlunderPunishPercent: Int?
            get() = if (opponentBlundersTotal > 0) {
                ((opponentBlundersPunished * 100.0) / opponentBlundersTotal)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                null
            }
    }

    /** Статистика по категории контроля времени (Пуля / Блиц / Рапид / Классика / Заочные). */
    data class TimeControlPerformance(
        val category: PgnParser.TimeControlCategory,
        val gamesCount: Int,
        val analyzedCount: Int,
        val wins: Int,
        val draws: Int,
        val losses: Int,
        val scorePercent: Double?,
        val averageAccuracy: Double?,
        val blundersPerGame: Double?
    ) {
        val decidedGames: Int get() = wins + draws + losses
    }

    /** Влияние цейтнота (`< 20 сек` по `{[%clk]}`) на точность и частоту зевков. */
    data class TimeTroubleReport(
        val gamesWithClocks: Int = 0,
        val gamesWithTimeTrouble: Int = 0,
        val timeTroubleMovesCount: Int = 0,
        val normalMovesCount: Int = 0,
        val normalAccuracy: Double? = null,
        val timeTroubleAccuracy: Double? = null,
        val normalBlunderRatePercent: Double? = null,
        val timeTroubleBlunderRatePercent: Double? = null
    ) {
        val hasComparison: Boolean
            get() = normalAccuracy != null && timeTroubleAccuracy != null && timeTroubleMovesCount >= 2

        /** Насколько падает точность в цейтноте (`> 0` означает просадку). */
        val accuracyDropPercent: Double?
            get() {
                val norm = normalAccuracy ?: return null
                val tt = timeTroubleAccuracy ?: return null
                return roundOne(norm - tt)
            }
    }

    /** Точка на графике динамики точности проанализированных партий. */
    data class AccuracyTrendPoint(
        val gameId: Long?,
        val playedAt: Long,
        val dateLabel: String,
        val accuracy: Double,
        val rollingAccuracy: Double,
        val outcome: PgnParser.PlayerOutcome,
        val playerColor: ChessColor,
        val openingShort: String?,
        val opponentLabel: String,
        val blundersCount: Int
    )

    /** Точка на графике динамики тактического рейтинга. */
    data class RatingTrendPoint(
        val stepIndex: Int,
        val timestampMs: Long,
        val rating: Int,
        val delta: Int,
        val cleanSolve: Boolean
    )

    /** Активность пользователя за один календарный день (последние 14 дней). */
    data class DailyActivityPoint(
        val epochDay: Long,
        val dayLabel: String,
        val gamesCount: Int,
        val puzzlesSolved: Int
    ) {
        val totalActions: Int get() = gamesCount + puzzlesSolved
    }

    /** Конкретная тренерская рекомендация с действием в 1 нажатие. */
    data class CoachingAction(
        val title: String,
        val description: String,
        val actionLabel: String? = null,
        val trainingMode: TrainingMode? = null,
        val openingSearchQuery: String? = null,
        val gameIdToOpen: Long? = null
    )

    /** Обогащённая проблемная тема в партиях с привязкой к режиму тренировки. */
    data class ActionableProblemTheme(
        val themeId: Long,
        val themeName: String,
        val category: String,
        val mistakeCount: Int,
        val percentage: Double,
        val suggestedMode: TrainingMode
    )

    /** Единый аналитический отчёт по партиям и дебютам за выбранный [period]. */
    data class GameAnalyticsReport(
        val period: StatsPeriod = StatsPeriod.ALL,
        val overall: ColorPerformance = ColorPerformance(label = "Все партии", color = null),
        val asWhite: ColorPerformance = ColorPerformance(label = "Белыми", color = ChessColor.WHITE),
        val asBlack: ColorPerformance = ColorPerformance(label = "Чёрными", color = ChessColor.BLACK),
        val repertoire: OpeningRepertoireSummary = OpeningRepertoireSummary(),
        val phases: PhaseOverview = emptyPhaseOverview(),
        val moveQuality: MoveQualityDistribution = MoveQualityDistribution(),
        val timeControls: List<TimeControlPerformance> = emptyList(),
        val timeTrouble: TimeTroubleReport = TimeTroubleReport(),
        val accuracySeries: List<AccuracyTrendPoint> = emptyList(),
        val accuracyTrendDirection: TrendDirection = TrendDirection.NOT_ENOUGH_DATA,
        val accuracyTrendDelta: Double? = null
    )

    private data class EnrichedGame(
        val game: Game,
        val card: PgnParser.GameCardInfo,
        val moves: List<AnalyzedMove>,
        val playerMoves: List<AnalyzedMove>,
        val mistakes: List<Mistake>
    )

    // ==================== ГЛАВНЫЙ РАСЧЁТ АНАЛИТИКИ ПАРТИЙ ====================

    fun computeGameAnalytics(
        games: List<Game>,
        analyzedMovesByGameId: Map<Long, List<AnalyzedMove>>,
        mistakesByGameId: Map<Long, List<Mistake>>,
        period: StatsPeriod = StatsPeriod.ALL,
        nowMs: Long = System.currentTimeMillis()
    ): GameAnalyticsReport {
        val cutoff = period.cutoffMillis(nowMs)
        val filteredGames = if (cutoff != null) {
            games.filter { it.playedAt > 0L && it.playedAt >= cutoff }
        } else {
            games
        }
        if (filteredGames.isEmpty()) {
            return GameAnalyticsReport(period = period)
        }

        val enriched = filteredGames
            .sortedBy { it.playedAt }
            .map { game ->
                val gid = game.id
                val allMoves = if (gid != null) {
                    analyzedMovesByGameId[gid].orEmpty().sortedBy { it.moveIndex }
                } else {
                    emptyList()
                }
                val playerMoves = allMoves.filter { it.color == game.playerColor }
                val gameMistakes = if (gid != null) {
                    mistakesByGameId[gid].orEmpty()
                } else {
                    emptyList()
                }
                EnrichedGame(
                    game = game,
                    card = PgnParser.extractGameCardInfo(game),
                    moves = allMoves,
                    playerMoves = playerMoves,
                    mistakes = gameMistakes
                )
            }

        val overall = buildColorPerformance("Все партии", null, enriched)
        val asWhite = buildColorPerformance(
            "Белыми",
            ChessColor.WHITE,
            enriched.filter { it.game.playerColor == ChessColor.WHITE }
        )
        val asBlack = buildColorPerformance(
            "Чёрными",
            ChessColor.BLACK,
            enriched.filter { it.game.playerColor == ChessColor.BLACK }
        )

        val repertoire = buildOpeningRepertoire(enriched)
        val phases = buildPhaseOverview(enriched)
        val quality = buildMoveQualityDistribution(enriched)
        val timeControls = buildTimeControlStats(enriched)
        val timeTrouble = buildTimeTroubleReport(enriched)
        val accuracySeries = buildAccuracySeries(enriched)
        val (trendDir, trendDelta) = computeAccuracyTrend(enriched)

        return GameAnalyticsReport(
            period = period,
            overall = overall,
            asWhite = asWhite,
            asBlack = asBlack,
            repertoire = repertoire,
            phases = phases,
            moveQuality = quality,
            timeControls = timeControls,
            timeTrouble = timeTrouble,
            accuracySeries = accuracySeries,
            accuracyTrendDirection = trendDir,
            accuracyTrendDelta = trendDelta
        )
    }

    // ==================== ЦВЕТА И ОБЩИЙ СЧЁТ ====================

    private fun buildColorPerformance(
        label: String,
        color: ChessColor?,
        items: List<EnrichedGame>
    ): ColorPerformance {
        if (items.isEmpty()) return ColorPerformance(label = label, color = color)

        var wins = 0
        var draws = 0
        var losses = 0
        var unknown = 0
        var bestWinElo: Int? = null

        for (item in items) {
            when (item.card.outcome) {
                PgnParser.PlayerOutcome.WIN -> {
                    wins++
                    val oppElo = item.card.opponentElo
                    if (oppElo != null && (bestWinElo == null || oppElo > bestWinElo)) {
                        bestWinElo = oppElo
                    }
                }
                PgnParser.PlayerOutcome.DRAW -> draws++
                PgnParser.PlayerOutcome.LOSS -> losses++
                PgnParser.PlayerOutcome.UNKNOWN -> unknown++
            }
        }

        val decided = wins + draws + losses
        val scorePct = computeScorePercent(wins, draws, losses)
        val winRatePct = if (decided > 0) roundOne((wins * 100.0) / decided) else null

        val analyzed = items.filter { it.game.isAnalyzed() }
        val accuracies = analyzed.mapNotNull { it.game.accuracy }
        val avgAcc = accuracies.takeIf { it.isNotEmpty() }?.let { roundOne(it.average()) }

        val acpls = analyzed.mapNotNull { item ->
            item.game.averageEvaluationLoss?.coerceIn(0, MAX_ACPL_CAP)
                ?: item.playerMoves.takeIf { it.isNotEmpty() }?.let {
                    GameMoveClassifier.calculateAverageCentipawnLoss(item.moves, item.game.playerColor)
                        .coerceIn(0, MAX_ACPL_CAP)
                }
        }
        val avgAcpl = acpls.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

        val blundersPerGame = analyzed.takeIf { it.isNotEmpty() }?.let { list ->
            val totalBlunders = list.sumOf { e ->
                if (e.playerMoves.isNotEmpty()) {
                    e.playerMoves.count { it.quality == MoveQuality.BLUNDER }
                } else {
                    e.game.blundersCount.coerceAtLeast(
                        e.mistakes.count { it.mistakeType == MistakeType.BLUNDER }
                    )
                }
            }
            roundOne(totalBlunders.toDouble() / list.size)
        }

        return ColorPerformance(
            label = label,
            color = color,
            gamesCount = items.size,
            analyzedCount = analyzed.size,
            wins = wins,
            draws = draws,
            losses = losses,
            unknownResults = unknown,
            scorePercent = scorePct,
            winRatePercent = winRatePct,
            averageAccuracy = avgAcc,
            accuracySampleSize = accuracies.size,
            averageAcpl = avgAcpl,
            blundersPerGame = blundersPerGame,
            bestWinOpponentElo = bestWinElo
        )
    }

    // ==================== ДЕБЮТНЫЙ РЕПЕРТУАР (ECO) ====================

    private fun buildOpeningRepertoire(items: List<EnrichedGame>): OpeningRepertoireSummary {
        val withOpening = items.mapNotNull { item ->
            val resolved = resolveOpeningIdentity(item.card) ?: return@mapNotNull null
            item to resolved
        }

        val whiteLines = aggregateOpeningLines(
            color = ChessColor.WHITE,
            entries = withOpening.filter { it.first.game.playerColor == ChessColor.WHITE }
        )
        val blackLines = aggregateOpeningLines(
            color = ChessColor.BLACK,
            entries = withOpening.filter { it.first.game.playerColor == ChessColor.BLACK }
        )
        val ecoFamilies = buildEcoFamilies(withOpening)

        val (bestWhite, weakestWhite) = pickBestAndWeakestOpening(whiteLines)
        val (bestBlack, weakestBlack) = pickBestAndWeakestOpening(blackLines)

        val gamesWithMoves = items.filter { it.playerMoves.isNotEmpty() }
        val avgBook = gamesWithMoves.takeIf { it.isNotEmpty() }?.let { list ->
            roundOne(list.map { g -> g.playerMoves.count { it.quality == MoveQuality.BOOK } }.average())
        }

        val uniqueCount = (whiteLines.map { it.openingName.lowercase(Locale.ROOT) } +
            blackLines.map { it.openingName.lowercase(Locale.ROOT) })
            .toSet()
            .size

        return OpeningRepertoireSummary(
            whiteOpenings = whiteLines,
            blackOpenings = blackLines,
            ecoFamilies = ecoFamilies,
            bestWhite = bestWhite,
            weakestWhite = weakestWhite,
            bestBlack = bestBlack,
            weakestBlack = weakestBlack,
            recognizedGamesCount = withOpening.size,
            uniqueOpeningsCount = uniqueCount,
            averageBookMoves = avgBook
        )
    }

    private data class OpeningIdentity(
        val groupKey: String,
        val displayName: String,
        val eco: String?
    )

    private fun resolveOpeningIdentity(card: PgnParser.GameCardInfo): OpeningIdentity? {
        val eco = card.eco?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 3 }
        val rawName = OpeningDatabase.translateOpeningName(card.openingName)

        // Если в PGN был только код ECO без названия — берём единое русское имя семейства из OpeningDatabase
        val fullName = rawName ?: eco?.let {
            OpeningDatabase.ecoFallbackName(it) ?: OpeningDatabase.findByEco(it).firstOrNull()?.name
        } ?: return null
        // Группируем родственные подварианты вида "Сицилианская защита: вариант Найдорфа" в базовый дебют,
        // если название содержит двоеточие, сохраняя читаемость репертуара
        val familyName = fullName.substringBefore(':').trim().ifEmpty { fullName }
        val key = familyName.lowercase(Locale.ROOT)
        return OpeningIdentity(groupKey = key, displayName = familyName, eco = eco)
    }

    private fun aggregateOpeningLines(
        color: ChessColor,
        entries: List<Pair<EnrichedGame, OpeningIdentity>>
    ): List<OpeningLineStat> {
        if (entries.isEmpty()) return emptyList()

        return entries
            .groupBy { it.second.groupKey }
            .values
            .map { group ->
                val games = group.map { it.first }
                val identities = group.map { it.second }

                // Выбираем самый частый ECO-код в этой дебютной группе
                val ecoFreq = identities.mapNotNull { it.eco }.groupingBy { it }.eachCount()
                val primaryEco = ecoFreq.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull()
                    ?.key
                val ecoList = ecoFreq.keys.sorted()

                // Предпочитаем самое чистое отображаемое имя (русское, если встречается)
                val displayName = identities
                    .map { it.displayName }
                    .sortedByDescending { name -> name.count { ch -> ch in 'А'..'я' || ch == 'ё' || ch == 'Ё' } }
                    .first()

                var wins = 0
                var draws = 0
                var losses = 0
                var unknown = 0
                for (g in games) {
                    when (g.card.outcome) {
                        PgnParser.PlayerOutcome.WIN -> wins++
                        PgnParser.PlayerOutcome.DRAW -> draws++
                        PgnParser.PlayerOutcome.LOSS -> losses++
                        PgnParser.PlayerOutcome.UNKNOWN -> unknown++
                    }
                }

                val analyzed = games.filter { it.game.isAnalyzed() }
                val avgAcc = analyzed.mapNotNull { it.game.accuracy }
                    .takeIf { it.isNotEmpty() }
                    ?.let { roundOne(it.average()) }

                val openingMovesAll = games.flatMap { g ->
                    g.playerMoves.filter { it.moveNumber <= OPENING_LAST_MOVE }
                }
                val openingPhaseAcc = computeMovesAccuracy(openingMovesAll)

                val gamesWithAnalyzedMoves = games.filter { it.playerMoves.isNotEmpty() }
                val avgBook = gamesWithAnalyzedMoves.takeIf { it.isNotEmpty() }?.let { list ->
                    roundOne(
                        list.map { g -> g.playerMoves.count { it.quality == MoveQuality.BOOK } }.average()
                    )
                }

                val earlyErrors = games.sumOf { g ->
                    if (g.playerMoves.isNotEmpty()) {
                        g.playerMoves.count { it.moveNumber <= OPENING_LAST_MOVE && it.isMistake() }
                    } else {
                        g.mistakes.count { it.moveNumber <= OPENING_LAST_MOVE }
                    }
                }

                // Средняя оценка выхода из дебюта (последний ход в окне 8..12, с точки зрения игрока)
                val exitEvals = games.mapNotNull { g ->
                    extractOpeningExitEvalForPlayer(g)
                }
                val avgExitEval = exitEvals.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

                val dbRef = primaryEco?.let { OpeningDatabase.findByEco(it).firstOrNull() }
                val keyIdea = dbRef?.keyIdeas?.firstOrNull()
                val commonTip = dbRef?.commonMistakes?.firstOrNull()

                val latest = games.maxByOrNull { it.game.playedAt }
                val searchQuery = displayName.ifBlank { primaryEco.orEmpty() }

                OpeningLineStat(
                    openingName = displayName,
                    primaryEco = primaryEco,
                    ecoCodes = ecoList,
                    color = color,
                    gamesCount = games.size,
                    analyzedCount = analyzed.size,
                    wins = wins,
                    draws = draws,
                    losses = losses,
                    unknownResults = unknown,
                    scorePercent = computeScorePercent(wins, draws, losses),
                    averageAccuracy = avgAcc,
                    openingPhaseAccuracy = openingPhaseAcc,
                    averageBookMoves = avgBook,
                    earlyMistakesCount = earlyErrors,
                    averageExitEvalCp = avgExitEval,
                    keyIdea = keyIdea,
                    commonMistakeTip = commonTip,
                    latestGameId = latest?.game?.id,
                    latestPlayedAt = latest?.game?.playedAt ?: 0L,
                    searchQuery = searchQuery
                )
            }
            .sortedWith(
                compareByDescending<OpeningLineStat> { it.gamesCount }
                    .thenByDescending { it.scorePercent ?: -1.0 }
                    .thenByDescending { it.latestPlayedAt }
            )
    }

    private fun extractOpeningExitEvalForPlayer(item: EnrichedGame): Int? {
        val sign = if (item.game.playerColor == ChessColor.WHITE) 1 else -1
        if (item.moves.isNotEmpty()) {
            val exitMove = item.moves
                .filter { it.moveNumber in OPENING_EXIT_MIN_MOVE..OPENING_EXIT_MAX_MOVE }
                .maxByOrNull { it.moveIndex }
            if (exitMove != null) {
                return (exitMove.evalAfter * sign).coerceIn(-MAX_EXIT_EVAL_CP, MAX_EXIT_EVAL_CP)
            }
        }
        val evals = item.game.getEvaluations()
        // Индекс 19 соответствует позиции после 10-го полного хода (20 полуходов)
        val targetPly = (evals.size - 1).coerceAtMost(23)
        if (targetPly >= 15) {
            return (evals[targetPly] * sign).coerceIn(-MAX_EXIT_EVAL_CP, MAX_EXIT_EVAL_CP)
        }
        return null
    }

    /**
     * Выбирает коронный и самый проблемный дебют с байесовским сглаживанием к 50%,
     * чтобы случайная единичная партия не затмевала устойчивую статистику на дистанции.
     */
    private fun pickBestAndWeakestOpening(
        lines: List<OpeningLineStat>
    ): Pair<OpeningLineStat?, OpeningLineStat?> {
        if (lines.isEmpty()) return null to null

        val qualified = lines.filter { it.gamesCount >= 2 }.ifEmpty { lines }

        fun compositeStrength(line: OpeningLineStat): Double? {
            // Сглаженный % очков (приор: 1 ничья = 50% с весом 1.5 партии)
            val smoothedScore = if (line.decidedGames > 0) {
                val points = line.wins + 0.5 * line.draws
                ((points + 0.75) / (line.decidedGames + 1.5)) * 100.0
            } else {
                null
            }
            val acc = line.openingPhaseAccuracy ?: line.averageAccuracy
            return when {
                smoothedScore != null && acc != null -> smoothedScore * 0.65 + acc * 0.35
                smoothedScore != null -> smoothedScore
                acc != null -> acc
                else -> null
            }
        }

        val scored = qualified.mapNotNull { line -> compositeStrength(line)?.let { line to it } }
        if (scored.isEmpty()) return null to null

        val best = scored
            .filter { (line, score) ->
                score >= 52.0 &&
                    (line.scorePercent == null || line.scorePercent >= 50.0) &&
                    (line.openingPhaseAccuracy ?: line.averageAccuracy ?: 75.0) >= 65.0
            }
            .maxWithOrNull(
                compareBy<Pair<OpeningLineStat, Double>> { it.second }
                    .thenBy { it.first.gamesCount }
            )
            ?.first

        val weakest = scored
            .filter { (line, _) ->
                line != best && (
                    (line.scorePercent != null && line.scorePercent < 50.0) ||
                        ((line.openingPhaseAccuracy ?: line.averageAccuracy ?: 100.0) < 75.0) ||
                        ((line.averageExitEvalCp ?: 0) <= -80) ||
                        (line.earlyMistakesCount >= line.gamesCount && line.earlyMistakesCount > 0)
                    )
            }
            .minWithOrNull(
                compareBy<Pair<OpeningLineStat, Double>> { it.second }
                    .thenByDescending { it.first.gamesCount }
            )
            ?.first

        return best to weakest
    }

    private fun buildEcoFamilies(
        entries: List<Pair<EnrichedGame, OpeningIdentity>>
    ): List<EcoFamilyStat> {
        val byVolume = entries
            .mapNotNull { (game, id) ->
                val ch = id.eco?.firstOrNull()?.takeIf { it in 'A'..'E' } ?: return@mapNotNull null
                ch to game
            }
            .groupBy({ it.first }, { it.second })

        if (byVolume.isEmpty()) return emptyList()

        return listOf('A', 'B', 'C', 'D', 'E').mapNotNull { code ->
            val games = byVolume[code]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val (title, subtitle) = ecoVolumeMeta(code)
            val wins = games.count { it.card.outcome == PgnParser.PlayerOutcome.WIN }
            val draws = games.count { it.card.outcome == PgnParser.PlayerOutcome.DRAW }
            val losses = games.count { it.card.outcome == PgnParser.PlayerOutcome.LOSS }
            val avgAcc = games.mapNotNull { it.game.takeIf(Game::isAnalyzed)?.accuracy }
                .takeIf { it.isNotEmpty() }
                ?.let { roundOne(it.average()) }
            EcoFamilyStat(
                code = code,
                title = title,
                subtitle = subtitle,
                gamesCount = games.size,
                wins = wins,
                draws = draws,
                losses = losses,
                scorePercent = computeScorePercent(wins, draws, losses),
                averageAccuracy = avgAcc
            )
        }
    }

    private fun ecoVolumeMeta(code: Char): Pair<String, String> = when (code) {
        'A' -> "Фланговые дебюты" to "Английское, Рети, Голландская, Бенони"
        'B' -> "Полуоткрытые дебюты" to "Сицилианская, Каро-Канн, Пирц, Алехин, Скандинавская"
        'C' -> "Открытые дебюты и Французская" to "1.e4 e5 (Испанская, Итальянская) и 1.e4 e6"
        'D' -> "Закрытые дебюты и Грюнфельд" to "Ферзевый гамбит, Славянская, защита Грюнфельда"
        'E' -> "Индийские защиты" to "Староиндийская, Нимцовича, Новоиндийская, Каталон"
        else -> "Дебюты ECO $code" to ""
    }

    private fun ecoFallbackName(eco: String): String =
        OpeningDatabase.ecoFallbackName(eco) ?: "Дебют $eco"

    // ==================== ФАЗЫ ПАРТИИ И КАЧЕСТВО ХОДОВ ====================

    private fun buildPhaseOverview(items: List<EnrichedGame>): PhaseOverview {
        val openingMoves = mutableListOf<AnalyzedMove>()
        val middleMoves = mutableListOf<AnalyzedMove>()
        val endMoves = mutableListOf<AnalyzedMove>()

        for (item in items) {
            for (move in item.playerMoves) {
                when {
                    move.moveNumber <= OPENING_LAST_MOVE -> openingMoves.add(move)
                    move.moveNumber <= MIDDLEGAME_LAST_MOVE -> middleMoves.add(move)
                    else -> endMoves.add(move)
                }
            }
        }

        val opening = summarizePhase(GamePhaseKind.OPENING, openingMoves)
        val middlegame = summarizePhase(GamePhaseKind.MIDDLEGAME, middleMoves)
        val endgame = summarizePhase(GamePhaseKind.ENDGAME, endMoves)

        val comparable = listOf(opening, middlegame, endgame)
            .filter { it.accuracy != null && it.movesCount >= 4 }

        val strongest = comparable.maxByOrNull { it.accuracy ?: 0.0 }
        val weakest = comparable
            .filter { it.phase != strongest?.phase }
            .minByOrNull { it.accuracy ?: 100.0 }
        val gap = if (strongest?.accuracy != null && weakest?.accuracy != null) {
            roundOne((strongest.accuracy - weakest.accuracy).coerceAtLeast(0.0))
        } else {
            null
        }

        return PhaseOverview(
            opening = opening,
            middlegame = middlegame,
            endgame = endgame,
            strongest = strongest,
            weakest = weakest,
            gapPercent = gap
        )
    }

    private fun summarizePhase(
        kind: GamePhaseKind,
        moves: List<AnalyzedMove>
    ): PhasePerformance {
        if (moves.isEmpty()) {
            return PhasePerformance(
                phase = kind,
                accuracy = null,
                movesCount = 0,
                bookMoves = 0,
                greatAndBrilliant = 0,
                inaccuracies = 0,
                mistakes = 0,
                missedWins = 0,
                blunders = 0
            )
        }
        return PhasePerformance(
            phase = kind,
            accuracy = computeMovesAccuracy(moves),
            movesCount = moves.size,
            bookMoves = moves.count { it.quality == MoveQuality.BOOK },
            greatAndBrilliant = moves.count {
                it.quality == MoveQuality.BRILLIANT || it.quality == MoveQuality.GREAT_MOVE
            },
            inaccuracies = moves.count { it.quality == MoveQuality.INACCURACY },
            mistakes = moves.count { it.quality == MoveQuality.MISTAKE },
            missedWins = moves.count { it.quality == MoveQuality.MISSED_WIN },
            blunders = moves.count { it.quality == MoveQuality.BLUNDER }
        )
    }

    private fun computeMovesAccuracy(moves: List<AnalyzedMove>): Double? {
        if (moves.isEmpty()) return null
        val sum = moves.sumOf { move ->
            if (move.quality == MoveQuality.BOOK) {
                100.0
            } else {
                GameMoveClassifier.calculateMoveAccuracy(move)
            }
        }
        return roundOne((sum / moves.size).coerceIn(0.0, 100.0))
    }

    private fun buildMoveQualityDistribution(items: List<EnrichedGame>): MoveQualityDistribution {
        var total = 0
        var brilliant = 0
        var great = 0
        var best = 0
        var excellent = 0
        var good = 0
        var book = 0
        var inaccuracy = 0
        var mistake = 0
        var missedWin = 0
        var blunder = 0
        var oppBlunders = 0
        var oppPunished = 0

        for (item in items) {
            val playerColor = item.game.playerColor
            for (m in item.playerMoves) {
                total++
                when (m.quality) {
                    MoveQuality.BRILLIANT -> brilliant++
                    MoveQuality.GREAT_MOVE -> great++
                    MoveQuality.BEST_MOVE -> best++
                    MoveQuality.EXCELLENT -> excellent++
                    MoveQuality.GOOD -> good++
                    MoveQuality.BOOK -> book++
                    MoveQuality.INACCURACY -> inaccuracy++
                    MoveQuality.MISTAKE -> mistake++
                    MoveQuality.MISSED_WIN -> missedWin++
                    MoveQuality.BLUNDER -> blunder++
                }
            }

            // Проверяем, использовал ли игрок грубые зевки соперника на следующем же ходу
            val byIndex = item.moves.associateBy { it.moveIndex }
            for (m in item.moves) {
                if (m.color != playerColor && m.quality == MoveQuality.BLUNDER) {
                    val reply = byIndex[m.moveIndex + 1]
                    if (reply != null && reply.color == playerColor) {
                        oppBlunders++
                        if (!reply.isMistake()) {
                            oppPunished++
                        }
                    }
                }
            }
        }

        return MoveQualityDistribution(
            totalPlayerMoves = total,
            brilliant = brilliant,
            great = great,
            best = best,
            excellent = excellent,
            good = good,
            book = book,
            inaccuracy = inaccuracy,
            mistake = mistake,
            missedWin = missedWin,
            blunder = blunder,
            opponentBlundersTotal = oppBlunders,
            opponentBlundersPunished = oppPunished
        )
    }

    // ==================== КОНТРОЛИ ВРЕМЕНИ И ЦЕЙТНОТ ====================

    private fun buildTimeControlStats(items: List<EnrichedGame>): List<TimeControlPerformance> {
        val orderedCategories = listOf(
            PgnParser.TimeControlCategory.BULLET,
            PgnParser.TimeControlCategory.BLITZ,
            PgnParser.TimeControlCategory.RAPID,
            PgnParser.TimeControlCategory.CLASSICAL,
            PgnParser.TimeControlCategory.CORRESPONDENCE
        )
        val grouped = items.groupBy { it.card.speedCategory }
        return orderedCategories.mapNotNull { category ->
            val list = grouped[category]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val wins = list.count { it.card.outcome == PgnParser.PlayerOutcome.WIN }
            val draws = list.count { it.card.outcome == PgnParser.PlayerOutcome.DRAW }
            val losses = list.count { it.card.outcome == PgnParser.PlayerOutcome.LOSS }
            val analyzed = list.filter { it.game.isAnalyzed() }
            val avgAcc = analyzed.mapNotNull { it.game.accuracy }
                .takeIf { it.isNotEmpty() }
                ?.let { roundOne(it.average()) }
            val blundersPerGame = analyzed.takeIf { it.isNotEmpty() }?.let { ag ->
                roundOne(ag.sumOf { it.game.blundersCount }.toDouble() / ag.size)
            }
            TimeControlPerformance(
                category = category,
                gamesCount = list.size,
                analyzedCount = analyzed.size,
                wins = wins,
                draws = draws,
                losses = losses,
                scorePercent = computeScorePercent(wins, draws, losses),
                averageAccuracy = avgAcc,
                blundersPerGame = blundersPerGame
            )
        }
    }

    private fun buildTimeTroubleReport(items: List<EnrichedGame>): TimeTroubleReport {
        var gamesWithClocks = 0
        var gamesWithTt = 0
        val normalMoves = mutableListOf<AnalyzedMove>()
        val ttMoves = mutableListOf<AnalyzedMove>()
        var rawTtCount = 0

        for (item in items) {
            if (!item.card.hasClockData) continue
            gamesWithClocks++
            if (item.card.playerTimeTroubleMoves > 0) {
                gamesWithTt++
                rawTtCount += item.card.playerTimeTroubleMoves
            }
            if (item.playerMoves.isNotEmpty()) {
                val clocksByPly = PgnParser.extractClocksByPly(item.game.pgnData)
                for (move in item.playerMoves) {
                    val sec = clocksByPly[move.moveIndex] ?: continue
                    if (GameReviewHelper.isTimeTrouble(sec)) {
                        ttMoves.add(move)
                    } else {
                        normalMoves.add(move)
                    }
                }
            }
        }

        if (gamesWithClocks == 0) return TimeTroubleReport()

        val normalBlunderRate = normalMoves.takeIf { it.isNotEmpty() }?.let { list ->
            roundOne((list.count { it.quality == MoveQuality.BLUNDER } * 100.0) / list.size)
        }
        val ttBlunderRate = ttMoves.takeIf { it.isNotEmpty() }?.let { list ->
            roundOne((list.count { it.quality == MoveQuality.BLUNDER } * 100.0) / list.size)
        }

        return TimeTroubleReport(
            gamesWithClocks = gamesWithClocks,
            gamesWithTimeTrouble = gamesWithTt,
            timeTroubleMovesCount = if (ttMoves.isNotEmpty()) ttMoves.size else rawTtCount,
            normalMovesCount = normalMoves.size,
            normalAccuracy = computeMovesAccuracy(normalMoves),
            timeTroubleAccuracy = computeMovesAccuracy(ttMoves),
            normalBlunderRatePercent = normalBlunderRate,
            timeTroubleBlunderRatePercent = ttBlunderRate
        )
    }

    // ==================== ДИНАМИКА ТОЧНОСТИ, РЕЙТИНГА И АКТИВНОСТИ ====================

    private fun buildAccuracySeries(orderedItems: List<EnrichedGame>): List<AccuracyTrendPoint> {
        val analyzedWithAcc = orderedItems
            .filter { it.game.isAnalyzed() && it.game.accuracy != null }
            .takeLast(MAX_ACCURACY_TREND_POINTS)

        if (analyzedWithAcc.isEmpty()) return emptyList()

        return analyzedWithAcc.mapIndexed { idx, item ->
            val acc = roundOne(item.game.accuracy!!.coerceIn(0.0, 100.0))
            val windowStart = (idx - ROLLING_WINDOW + 1).coerceAtLeast(0)
            val windowAvg = roundOne(
                analyzedWithAcc.subList(windowStart, idx + 1)
                    .mapNotNull { it.game.accuracy }
                    .average()
            )
            val openingShort = resolveOpeningIdentity(item.card)?.let { id ->
                if (id.eco != null) "${id.eco} · ${id.displayName}" else id.displayName
            }
            val opponent = item.card.opponentName ?: "Соперник"
            AccuracyTrendPoint(
                gameId = item.game.id,
                playedAt = item.game.playedAt,
                dateLabel = item.card.dateLabel,
                accuracy = acc,
                rollingAccuracy = windowAvg,
                outcome = item.card.outcome,
                playerColor = item.game.playerColor,
                openingShort = openingShort,
                opponentLabel = opponent,
                blundersCount = item.game.blundersCount
            )
        }
    }

    private fun computeAccuracyTrend(orderedItems: List<EnrichedGame>): Pair<TrendDirection, Double?> {
        val accuracies = orderedItems
            .filter { it.game.isAnalyzed() }
            .mapNotNull { it.game.accuracy }
        if (accuracies.size < 4) {
            return TrendDirection.NOT_ENOUGH_DATA to null
        }

        // Сравниваем последние N партий (до 5) с предыдущими N партиями
        val window = (accuracies.size / 2).coerceIn(2, 5)
        val recent = accuracies.takeLast(window).average()
        val previous = accuracies.dropLast(window).takeLast(window).average()
        val delta = roundOne(recent - previous)
        val direction = when {
            delta >= 3.0 -> TrendDirection.IMPROVING
            delta <= -3.0 -> TrendDirection.DECLINING
            else -> TrendDirection.STABLE
        }
        return direction to delta
    }

    /**
     * Восстанавливает детерминированную кривую тактического рейтинга по последним
     * завершённым подходам (`ExerciseSitting`), заканчивающуюся точным [currentRating].
     */
    fun computeRatingHistory(
        currentRating: Int,
        attempts: List<ExerciseAttempt>,
        exercisesById: Map<Long, Exercise>,
        maxPoints: Int = MAX_RATING_TREND_POINTS
    ): List<RatingTrendPoint> {
        if (attempts.isEmpty() || maxPoints < 2) return emptyList()

        val sittings = attempts
            .groupBy { it.exerciseId }
            .flatMap { (exId, list) ->
                SpacedRepetitionScheduler.groupIntoSittings(list, exercisesById[exId])
            }
            .sortedBy { it.finishedAt }

        if (sittings.isEmpty()) return emptyList()

        val recent = sittings.takeLast(maxPoints)
        val baseExperience = (sittings.size - recent.size).coerceAtLeast(0)

        // Оцениваем дельту каждого подхода при текущем уровне и реконструируем траекторию к currentRating
        val estimatedDeltas = recent.mapIndexed { idx, sitting ->
            val exercise = exercisesById[sitting.exerciseId]
            val exp = baseExperience + idx
            if (exercise != null) {
                when (sitting.quality) {
                    AttemptQuality.CLEAN_FAST_SOLVE,
                    AttemptQuality.SLOW_CLEAN_SOLVE -> TrainingRatingCalculator.calculateSolveDelta(
                        playerElo = currentRating,
                        exercise = exercise,
                        hadMistake = false,
                        maxHintUsed = HintUsage.NONE,
                        solutionRevealed = false,
                        completedRatedSittings = exp,
                        mistakeCount = 0
                    )
                    AttemptQuality.HINT_SOLVE -> TrainingRatingCalculator.calculateSolveDelta(
                        playerElo = currentRating,
                        exercise = exercise,
                        hadMistake = false,
                        maxHintUsed = HintUsage.PIECE_HIGHLIGHT,
                        solutionRevealed = false,
                        completedRatedSittings = exp,
                        mistakeCount = 0
                    )
                    AttemptQuality.RETRY_SOLVE -> TrainingRatingCalculator.calculateSolveDelta(
                        playerElo = currentRating,
                        exercise = exercise,
                        hadMistake = true,
                        maxHintUsed = sitting.hintUsage,
                        solutionRevealed = sitting.hintUsage == HintUsage.FULL_SOLUTION,
                        completedRatedSittings = exp,
                        mistakeCount = sitting.mistakeCount.coerceAtLeast(1)
                    )
                    AttemptQuality.FAILED -> if (sitting.hintUsage == HintUsage.FULL_SOLUTION && sitting.mistakeCount == 0) {
                        0
                    } else {
                        TrainingRatingCalculator.calculateFailureDelta(
                            playerElo = currentRating,
                            exercise = exercise,
                            completedRatedSittings = exp
                        )
                    }
                }
            } else {
                // Запасная оценка по Эло на случай, если исходное упражнение было удалено
                val halfK = (TrainingRatingCalculator.kFactorForExperience(exp) * 0.5).roundToInt().coerceAtLeast(4)
                when (sitting.quality) {
                    AttemptQuality.CLEAN_FAST_SOLVE, AttemptQuality.SLOW_CLEAN_SOLVE -> halfK
                    AttemptQuality.HINT_SOLVE -> (halfK * 0.55).roundToInt().coerceAtLeast(2)
                    AttemptQuality.RETRY_SOLVE -> -(halfK / 2)
                    AttemptQuality.FAILED -> -halfK
                }
            }
        }

        // Идём в обратном порядке от точного currentRating, чтобы финальная точка всегда равнялась user.rating
        val ratingsAfter = IntArray(recent.size)
        var cursor = currentRating
        for (i in recent.indices.reversed()) {
            ratingsAfter[i] = cursor
            cursor = (cursor - estimatedDeltas[i]).coerceIn(
                TrainingRatingCalculator.MIN_RATING,
                TrainingRatingCalculator.MAX_RATING
            )
        }

        return recent.mapIndexed { idx, sitting ->
            val prev = if (idx == 0) cursor else ratingsAfter[idx - 1]
            val curr = ratingsAfter[idx]
            RatingTrendPoint(
                stepIndex = idx + 1,
                timestampMs = sitting.finishedAt,
                rating = curr,
                delta = curr - prev,
                cleanSolve = sitting.quality.isCleanFirstTry
            )
        }
    }

    /**
     * Строит 14-дневную шкалу активности (партии + решённые задачи) и считает серию активных дней подряд.
     */
    fun computeDailyActivity(
        games: List<Game>,
        attempts: List<ExerciseAttempt>,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        daysCount: Int = ACTIVITY_DAYS
    ): Pair<List<DailyActivityPoint>, Int> {
        val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val todayEpoch = today.toEpochDay()

        val gamesByDay = games
            .filter { it.playedAt > 0L }
            .groupingBy { g ->
                Instant.ofEpochMilli(g.playedAt).atZone(zoneId).toLocalDate().toEpochDay()
            }.eachCount()

        val solvedByDay = attempts
            .filter { it.solved }
            .groupingBy { a ->
                Instant.ofEpochMilli(a.attemptedAt).atZone(zoneId).toLocalDate().toEpochDay()
            }
            .eachCount()

        val activeEpochDays = (gamesByDay.keys + solvedByDay.keys).toSet()

        val series = (daysCount - 1 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            val epoch = d.toEpochDay()
            DailyActivityPoint(
                epochDay = epoch,
                dayLabel = d.format(DAY_LABEL_FMT),
                gamesCount = gamesByDay[epoch] ?: 0,
                puzzlesSolved = solvedByDay[epoch] ?: 0
            )
        }

        // Серия активных дней сохраняется, если была активность сегодня или вчера
        var streak = 0
        var checkDay = if (todayEpoch in activeEpochDays) todayEpoch else todayEpoch - 1L
        while (checkDay in activeEpochDays) {
            streak++
            checkDay--
        }

        return series to streak
    }

    // ==================== ПРИВЯЗКА ТЕМ И ПЕРСОНАЛЬНЫЙ ПЛАН ТРЕНЕРА ====================

    /** Сопоставляет ID темы ошибки из `PredefinedThemes` с самым подходящим режимом тренажёра. */
    fun mapThemeIdToTrainingMode(themeId: Long): TrainingMode = when (themeId) {
        1L -> TrainingMode.ByTheme(TacticalTheme.FORK)
        2L, 3L -> TrainingMode.ByTheme(TacticalTheme.PIN)
        4L -> TrainingMode.ByTheme(TacticalTheme.DISCOVERED_ATTACK)
        5L, 7L -> TrainingMode.ByTheme(TacticalTheme.MATE)
        6L -> TrainingMode.ByTheme(TacticalTheme.TACTIC)
        14L, 15L -> TrainingMode.DeepCalculation
        16L, 17L -> TrainingMode.Defense
        else -> TrainingMode.MyMistakes
    }

    /** Сопоставляет тактический паттерн из `PatternMastery` с целевым режимом тренировки. */
    fun mapPatternToTrainingMode(mastery: PatternMastery): TrainingMode {
        val name = mastery.pattern.name
        return when {
            "FORK" in name -> TrainingMode.ByTheme(TacticalTheme.FORK)
            "PIN" in name || "SKEWER" in name -> TrainingMode.ByTheme(TacticalTheme.PIN)
            "DISCOVERED" in name -> TrainingMode.ByTheme(TacticalTheme.DISCOVERED_ATTACK)
            "MATE" in name || "DOUBLE_CHECK" in name -> TrainingMode.ByTheme(TacticalTheme.MATE)
            "DEFENSIVE" in name || "COUNTER" in name || "PERPETUAL" in name || "STALEMATE" in name ->
                TrainingMode.Defense
            else -> TrainingMode.WeakPatterns
        }
    }

    /**
     * Формирует до 5 конкретных, основанных на цифрах тренерских шагов с прямым действием.
     */
    fun buildCoachingActions(
        report: GameAnalyticsReport,
        dueForReviewCount: Int,
        personalMistakeExercises: Int,
        impulsiveMistakeCount: Int,
        impulsiveMistakeRatePercent: Int,
        weakPatterns: List<PatternMastery>,
        problemThemes: List<ProblemTheme>
    ): List<CoachingAction> {
        val actions = mutableListOf<CoachingAction>()

        // 1. Самый уязвимый дебют в репертуаре
        val problemOpening = listOfNotNull(
            report.repertoire.weakestWhite,
            report.repertoire.weakestBlack
        ).minByOrNull { it.scorePercent ?: it.openingPhaseAccuracy ?: it.averageAccuracy ?: 100.0 }

        if (problemOpening != null) {
            val sideRu = if (problemOpening.color == ChessColor.WHITE) "за белых" else "за чёрных"
            val metrics = buildList {
                problemOpening.scorePercent?.let { add("очки ${it.roundToInt()}% в ${problemOpening.gamesCount} парт.") }
                problemOpening.openingPhaseAccuracy?.let { add("точность дебюта ${it.roundToInt()}%") }
                problemOpening.formattedExitEval()?.let {
                    if ((problemOpening.averageExitEvalCp ?: 0) <= -60) add("выход $it")
                }
            }.joinToString(", ")
            val tipSuffix = problemOpening.keyIdea?.let { " Ориентир: ${it.lowercase(Locale.ROOT)}." }.orEmpty()
            actions.add(
                CoachingAction(
                    title = "Подтянуть дебют: ${problemOpening.displayTitle()}",
                    description = "Слабое звено репертуара $sideRu ($metrics).$tipSuffix",
                    actionLabel = "Открыть партии",
                    openingSearchQuery = problemOpening.searchQuery,
                    gameIdToOpen = problemOpening.latestGameId
                )
            )
        }

        // 2. Просадка в конкретной фазе партии (разрыв >= 6%)
        val weakestPhase = report.phases.weakest
        val gap = report.phases.gapPercent
        if (weakestPhase?.accuracy != null && gap != null && gap >= 6.0) {
            val phaseMode = when (weakestPhase.phase) {
                GamePhaseKind.OPENING -> if (personalMistakeExercises > 0) TrainingMode.MyMistakes else TrainingMode.PatternVision
                GamePhaseKind.MIDDLEGAME -> if (personalMistakeExercises > 0) TrainingMode.MyMistakes else TrainingMode.SmartSession
                GamePhaseKind.ENDGAME -> TrainingMode.DeepCalculation
            }
            actions.add(
                CoachingAction(
                    title = "Зона роста: ${weakestPhase.phase.displayName} (${weakestPhase.accuracy.roundToInt()}%)",
                    description = "Точность на ${formatOne(gap)}% ниже вашей лучшей фазы " +
                        "(${report.phases.strongest?.phase?.displayName} ${report.phases.strongest?.accuracy?.roundToInt()}%), " +
                        "ошибок: ${weakestPhase.totalErrors} за ${weakestPhase.movesCount} ходов.",
                    actionLabel = "Тренировать",
                    trainingMode = phaseMode
                )
            )
        }

        // 3. Потери в цейтноте
        val ttDrop = report.timeTrouble.accuracyDropPercent
        if (report.timeTrouble.hasComparison && ttDrop != null && ttDrop >= 7.0) {
            actions.add(
                CoachingAction(
                    title = "Потери в цейтноте (−${formatOne(ttDrop)}% точности)",
                    description = "При остатке менее 20 сек точность падает с " +
                        "${report.timeTrouble.normalAccuracy!!.roundToInt()}% до " +
                        "${report.timeTrouble.timeTroubleAccuracy!!.roundToInt()}%. " +
                        "Тренируйте мгновенное распознавание мотивов и играйте с добавлением.",
                    actionLabel = "Режим «Зоркость»",
                    trainingMode = TrainingMode.PatternVision
                )
            )
        }

        // 4. Неиспользованные зевки соперника
        val punishPct = report.moveQuality.opponentBlunderPunishPercent
        if (punishPct != null && report.moveQuality.opponentBlundersTotal >= 3 && punishPct < 65) {
            actions.add(
                CoachingAction(
                    title = "Реализация зевков соперника: $punishPct%",
                    description = "Наказано ${report.moveQuality.opponentBlundersPunished} из " +
                        "${report.moveQuality.opponentBlundersTotal} грубых ошибок соперника. " +
                        "После каждого неожиданного хода проверяйте форсированные удары.",
                    actionLabel = "Отработать тактику",
                    trainingMode = if (personalMistakeExercises > 0) TrainingMode.MyMistakes else TrainingMode.SmartSession
                )
            )
        }

        // 5. Интервальный повтор, импульсивность и слабые тактические мотивы
        if (dueForReviewCount > 0) {
            actions.add(
                CoachingAction(
                    title = "Интервальный повтор ($dueForReviewCount задач)",
                    description = "Подошёл оптимальный момент закрепить ранее изученные тактические мотивы в долговременной памяти.",
                    actionLabel = "Повторить",
                    trainingMode = TrainingMode.WeakPatterns
                )
            )
        } else if (weakPatterns.isNotEmpty()) {
            val w = weakPatterns.first()
            actions.add(
                CoachingAction(
                    title = "Слабый мотив: «${w.pattern.displayName}» (${w.masteryPercent}%)",
                    description = "Решения с 1-й попытки: ${w.firstTryAccuracyPercent}%. Прицельная серия быстро поднимет распознавание.",
                    actionLabel = "Отработать тему",
                    trainingMode = mapPatternToTrainingMode(w)
                )
            )
        }

        if (impulsiveMistakeCount >= 2 && impulsiveMistakeRatePercent >= 30) {
            actions.add(
                CoachingAction(
                    title = "Контроль импульсивных ходов ($impulsiveMistakeRatePercent%)",
                    description = "$impulsiveMistakeCount ошибок сделаны в первые секунды без проверки контригры. Считайте вариант до конца перед касанием фигуры.",
                    actionLabel = "Режим «Расчёт»",
                    trainingMode = TrainingMode.DeepCalculation
                )
            )
        }

        if (actions.isEmpty() && problemThemes.isNotEmpty()) {
            val top = problemThemes.first()
            actions.add(
                CoachingAction(
                    title = "Повторяющаяся тема: «${top.themeName}»",
                    description = "Встретилась ${top.mistakeCount} раз (${top.percentage.roundToInt()}% всех ошибок в партиях).",
                    actionLabel = "Мои ошибки",
                    trainingMode = TrainingMode.MyMistakes
                )
            )
        }

        if (actions.isEmpty()) {
            actions.add(
                CoachingAction(
                    title = "Запустите умную тренировку",
                    description = if (report.overall.gamesCount == 0) {
                        "Загрузите PGN-партии для построения дебютного репертуара или пройдите сбалансированную сессию из 10 задач."
                    } else {
                        "Ровные показатели без явных провалов — поддерживайте форму ежедневной сессией из 10 задач."
                    },
                    actionLabel = "Сессия 10",
                    trainingMode = TrainingMode.SmartSession
                )
            )
        }

        return actions.take(5)
    }

    // ==================== УТИЛИТЫ ====================

    fun computeScorePercent(wins: Int, draws: Int, losses: Int): Double? {
        val decided = wins + draws + losses
        if (decided <= 0) return null
        return roundOne(((wins + 0.5 * draws) * 100.0) / decided)
    }

    fun emptyPhaseOverview(): PhaseOverview = PhaseOverview(
        opening = summarizePhase(GamePhaseKind.OPENING, emptyList()),
        middlegame = summarizePhase(GamePhaseKind.MIDDLEGAME, emptyList()),
        endgame = summarizePhase(GamePhaseKind.ENDGAME, emptyList())
    )

    private fun roundOne(value: Double): Double =
        (value * 10.0).roundToInt() / 10.0

    fun formatOne(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
