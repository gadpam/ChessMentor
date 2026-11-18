// domain/pgn/PgnParser.kt
package com.example.chessmentor.domain.pgn

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.opening.OpeningDatabase
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Полноценный парсер, валидатор и индексатор PGN-партий (Lichess, Chess.com, FIDE/TWIC, ручной SAN).
 *
 * Возможности:
 * - Разбиение мульти-PGN архивов на отдельные партии с корректным учётом кавычек,
 *   фигурных комментариев ({[%clk ...], [%eval ...]}), строчных комментариев (;...) и вариантов ((...)).
 * - Извлечение всех ключевых заголовков: игроки, рейтинги, звания, результат, причина окончания,
 *   дата/время (UTC и локальные), контроль времени и его категория (Пуля/Блиц/Рапид/Классика/Заочные),
 *   ECO/дебют, турнир, платформа (Lichess / Chess.com), ссылка на партию, стартовый FEN и вариант.
 * - Извлечение показаний часов {[%clk H:MM:SS]} по полуходам и диагностика цейтнота.
 * - Полная пошаговая проверка легальности ходов на доске и распознавание русского названия дебюта.
 * - Автоопределение цвета игрока по никнейму и вычисление отпечатка партии для защиты от дубликатов.
 */
object PgnParser {

    const val MAX_SINGLE_PGN_CHARS = 100_000
    const val MAX_ARCHIVE_CHARS = 1_500_000
    const val MAX_BATCH_GAMES = 50

    private const val STANDARD_START_BOARD =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    private val TAG_LINE_REGEX = Regex("""^\[\s*([A-Za-z0-9_]+)\s+"((?:\\.|[^"\\])*)"\s*\]$""")
    private val ANY_TAG_REGEX = Regex("""\[\s*([A-Za-z0-9_]+)\s+"((?:\\.|[^"\\])*)"\s*\]""")
    private val CLK_REGEX = Regex("""\[%clk\s+(\d+):(\d{1,2}):(\d{1,2})(?:\.\d+)?\]""", RegexOption.IGNORE_CASE)
    private val DATE_REGEX = Regex("""^(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})$""")
    private val TIME_REGEX = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val MOVE_NUMBER_PREFIX_REGEX = Regex("""^\d+\.+(.*)$""")
    private val PURE_MOVE_NUMBER_REGEX = Regex("""^\d+\.+$""")
    private val NAG_TOKEN_REGEX = Regex("""^\$\d+$""")
    private val TRAILING_ANNOTATION_REGEX = Regex("""[!?]+$""")
    private val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "½-½", "*")
    private val ECO_CODE_REGEX = Regex("""^[A-E]\d{2}$""", RegexOption.IGNORE_CASE)
    private val SECONDS_TC_REGEX = Regex("""^(\d+)(?:\+(\d+))?$""")
    private val STAGE_TC_REGEX = Regex("""^\d+/(\d+)(?:\+(\d+))?""")
    private val CORRESP_TC_REGEX = Regex("""^(\d+)/(\d+)$""")
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val ALNUM_REGEX = Regex("""[^a-zа-яё0-9]""")

    private val PLACEHOLDER_NAMES = setOf(
        "", "?", "-", "unknown", "anonymous", "anon",
        "player", "opponent", "white", "black", "игрок", "соперник"
    )
    private val PLACEHOLDER_EVENTS = setOf(
        "", "?", "-", "imported game", "casual game", "live chess", "let's play!"
    )
    private val SUPPORTED_VARIANTS = setOf(
        "", "standard", "chess", "normal", "from position"
    )

    private val DISPLAY_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    // ==================== МОДЕЛИ ДАННЫХ ====================

    enum class PgnResult(val token: String) {
        WHITE_WIN("1-0"),
        BLACK_WIN("0-1"),
        DRAW("1/2-1/2"),
        ONGOING("*");

        fun displaySymbol(): String = when (this) {
            WHITE_WIN -> "1–0"
            BLACK_WIN -> "0–1"
            DRAW -> "½–½"
            ONGOING -> "*"
        }

        fun forPlayer(color: ChessColor): PlayerOutcome = when (this) {
            WHITE_WIN -> if (color == ChessColor.WHITE) PlayerOutcome.WIN else PlayerOutcome.LOSS
            BLACK_WIN -> if (color == ChessColor.BLACK) PlayerOutcome.WIN else PlayerOutcome.LOSS
            DRAW -> PlayerOutcome.DRAW
            ONGOING -> PlayerOutcome.UNKNOWN
        }

        companion object {
            fun fromString(raw: String?): PgnResult = when (raw?.trim()) {
                "1-0" -> WHITE_WIN
                "0-1" -> BLACK_WIN
                "1/2-1/2", "½-½", "0.5-0.5" -> DRAW
                else -> ONGOING
            }
        }
    }

    enum class PlayerOutcome(val displayName: String, val shortBadge: String) {
        WIN("Победа", "В"),
        LOSS("Поражение", "П"),
        DRAW("Ничья", "Н"),
        UNKNOWN("Без результата", "•")
    }

    enum class TimeControlCategory(val displayName: String) {
        BULLET("Пуля"),
        BLITZ("Блиц"),
        RAPID("Рапид"),
        CLASSICAL("Классика"),
        CORRESPONDENCE("Заочные"),
        UNKNOWN("Без контроля")
    }

    enum class GamePlatform(val displayName: String) {
        LICHESS("Lichess"),
        CHESS_COM("Chess.com"),
        OTHER("")
    }

    data class TimeControlInfo(
        val raw: String,
        val shortLabel: String,
        val category: TimeControlCategory
    ) {
        val fullLabel: String
            get() = when {
                category == TimeControlCategory.UNKNOWN && shortLabel.isBlank() -> "Без контроля"
                category == TimeControlCategory.UNKNOWN -> shortLabel
                shortLabel.isBlank() -> category.displayName
                else -> "${category.displayName} $shortLabel"
            }
    }

    data class PgnMetadata(
        val tags: Map<String, String>,
        val event: String?,
        val site: String?,
        val gameUrl: String?,
        val platform: GamePlatform,
        val whiteName: String?,
        val blackName: String?,
        val whiteElo: Int?,
        val blackElo: Int?,
        val whiteTitle: String?,
        val blackTitle: String?,
        val result: PgnResult,
        val termination: String?,
        val terminationRu: String?,
        val eco: String?,
        val openingName: String?,
        val timeControl: TimeControlInfo?,
        val playedAtMillis: Long?,
        val dateLabel: String?,
        val startFen: String?,
        val variant: String?
    )

    data class ClockSummary(
        val secondsByPly: Map<Int, Int>,
        val whiteTimeTroubleMoves: Int,
        val blackTimeTroubleMoves: Int,
        val whiteMinSeconds: Int?,
        val blackMinSeconds: Int?
    ) {
        val hasData: Boolean get() = secondsByPly.isNotEmpty()

        fun timeTroubleMovesFor(color: ChessColor): Int =
            if (color == ChessColor.WHITE) whiteTimeTroubleMoves else blackTimeTroubleMoves

        fun minSecondsFor(color: ChessColor): Int? =
            if (color == ChessColor.WHITE) whiteMinSeconds else blackMinSeconds
    }

    data class ParsedPgnGame(
        val rawPgn: String,
        val enrichedPgn: String,
        val metadata: PgnMetadata,
        val sanMoves: List<String>,
        val plyCount: Int,
        val fullMoveCount: Int,
        val firstMoveIsWhite: Boolean,
        val detectedEco: String?,
        val detectedOpeningName: String?,
        val clocks: ClockSummary,
        val fingerprint: String
    ) {
        val effectiveEco: String?
            get() = detectedEco ?: metadata.eco

        val effectiveOpeningName: String?
            get() = detectedOpeningName ?: metadata.openingName

        val openingLabel: String?
            get() = formatOpeningLabel(effectiveEco, effectiveOpeningName)
    }

    sealed class ParseResult {
        data class Success(val game: ParsedPgnGame) : ParseResult()
        data class Failure(val errorMessage: String) : ParseResult()
    }

    /**
     * Лёгкая сводка метаданных партии для карточек списка, главного экрана и заголовка сводки.
     */
    data class GameCardInfo(
        val title: String,
        val whiteDisplay: String?,
        val blackDisplay: String?,
        val playerName: String?,
        val playerElo: Int?,
        val opponentName: String?,
        val opponentElo: Int?,
        val opponentTitle: String?,
        val result: PgnResult,
        val outcome: PlayerOutcome,
        val terminationRu: String?,
        val timeControlLabel: String,
        val speedCategory: TimeControlCategory,
        val eco: String?,
        val openingName: String?,
        val openingLabel: String?,
        val event: String?,
        val platform: GamePlatform,
        val gameUrl: String?,
        val dateLabel: String,
        val plyCount: Int,
        val fullMoveCount: Int,
        val hasClockData: Boolean,
        val playerTimeTroubleMoves: Int
    ) {
        /** Поисковая строка в нижнем регистре для мгновенной фильтрации библиотеки. */
        val searchBlob: String by lazy {
            listOfNotNull(
                title,
                opponentName,
                playerName,
                eco,
                openingName,
                event,
                timeControlLabel,
                speedCategory.displayName,
                platform.displayName,
                dateLabel,
                outcome.displayName
            ).joinToString(" ").lowercase(Locale.ROOT)
        }
    }

    // ==================== РАЗБИЕНИЕ МУЛЬТИ-PGN ====================

    /**
     * Разбивает произвольный PGN-текст (одну партию, голый список ходов или архив из десятков партий)
     * на отдельные PGN-блоки.
     */
    fun splitGames(rawText: String): List<String> {
        val cleaned = rawText
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (cleaned.isEmpty()) return emptyList()

        val games = mutableListOf<String>()
        val current = StringBuilder()
        var seenMovetextInCurrent = false
        var braceDepth = 0
        var parenDepth = 0

        for (rawLine in cleaned.split('\n')) {
            val trimmed = rawLine.trim()

            // Новый блок заголовков после уже начавшегося текста ходов (вне комментариев и вариантов)
            val isTopLevelTagLine = braceDepth == 0 &&
                parenDepth == 0 &&
                trimmed.startsWith('[') &&
                TAG_LINE_REGEX.matches(trimmed)

            if (isTopLevelTagLine && seenMovetextInCurrent) {
                val finished = current.toString().trim()
                if (finished.isNotEmpty()) {
                    games.add(finished)
                }
                current.setLength(0)
                seenMovetextInCurrent = false
            }

            current.append(rawLine).append('\n')

            if (!isTopLevelTagLine && trimmed.isNotEmpty() && !trimmed.startsWith('%')) {
                val hadRealMoves = scanLineStructure(trimmed, braceDepth, parenDepth) { newBrace, newParen ->
                    braceDepth = newBrace
                    parenDepth = newParen
                }
                if (hadRealMoves) {
                    seenMovetextInCurrent = true
                }
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty() && (seenMovetextInCurrent || games.isEmpty())) {
            games.add(tail)
        }

        return games
    }

    private inline fun scanLineStructure(
        line: String,
        initialBrace: Int,
        initialParen: Int,
        onStateUpdate: (Int, Int) -> Unit
    ): Boolean {
        var brace = initialBrace
        var paren = initialParen
        var inQuote = false
        var sawMovetextChar = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (brace > 0) {
                if (ch == '}') brace = 0
                i++
                continue
            }
            if (inQuote) {
                if (ch == '\\') {
                    i += 2
                    continue
                }
                if (ch == '"') inQuote = false
                i++
                continue
            }
            when (ch) {
                '{' -> brace = 1
                ';' -> if (paren == 0) break
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '"' -> inQuote = true
                else -> {
                    if (paren == 0 && !ch.isWhitespace()) {
                        sawMovetextChar = true
                    }
                }
            }
            i++
        }
        onStateUpdate(brace, paren)
        return sawMovetextChar
    }

    // ==================== ПОЛНЫЙ РАЗБОР И ВАЛИДАЦИЯ ПАРТИИ ====================

    /**
     * Полностью разбирает одну PGN-партию, проверяет легальность всех ходов,
     * извлекает заголовки, часы, дебют и отпечаток для дедупликации.
     */
    fun parseAndValidate(singlePgn: String): ParseResult {
        val cleaned = singlePgn
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (cleaned.isEmpty()) {
            return ParseResult.Failure("PGN не может быть пустым")
        }
        if (cleaned.length > MAX_SINGLE_PGN_CHARS) {
            return ParseResult.Failure("PGN одной партии слишком большой (максимум $MAX_SINGLE_PGN_CHARS символов)")
        }

        val (metadata, movetextRaw) = extractMetadataAndMovetext(cleaned)

        // Отсеиваем экзотические нешахматные варианты (Atomic, Crazyhouse, Horde и т.д.)
        val variantNorm = metadata.variant?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (variantNorm !in SUPPORTED_VARIANTS) {
            return ParseResult.Failure("Вариант «${metadata.variant}» не поддерживается (только классические шахматы)")
        }

        val extracted = extractMainlineTokensAndClocks(movetextRaw)
        if (extracted.sanTokens.isEmpty()) {
            return ParseResult.Failure("В PGN не найдено ни одного хода")
        }

        val board = Board()
        val startFen = metadata.startFen
        val isCustomFen = startFen != null && !startFen.startsWith(STANDARD_START_BOARD)
        if (startFen != null) {
            val loaded = runCatching { board.loadFromFen(startFen) }.isSuccess
            if (!loaded) {
                return ParseResult.Failure("Некорректный стартовый FEN в заголовке PGN")
            }
        }

        val firstMoveIsWhite = board.sideToMove == Side.WHITE
        val startFullMove = board.moveCounter.coerceAtLeast(1)
        val canonicalSans = ArrayList<String>(extracted.sanTokens.size)
        val canonicalUcis = ArrayList<String>(extracted.sanTokens.size)

        var detectedEco: String? = null
        var detectedOpeningName: String? = null

        for ((plyIdx, rawToken) in extracted.sanTokens.withIndex()) {
            val moveNumber = startFullMove + ((plyIdx + if (firstMoveIsWhite) 0 else 1) / 2)
            val normalizedSan = normalizeSanToken(rawToken)
            if (normalizedSan.isEmpty()) continue

            val fenBefore = board.fen
            val parsedMove = parseSingleMoveStrict(board, fenBefore, normalizedSan)
                ?: return ParseResult.Failure("Нелегальный или нераспознанный ход на $moveNumber-м ходу: «$rawToken»")

            val canonicalUci = ChessMoveUtils.moveToUci(parsedMove)
            val cleanSan = ChessMoveUtils.uciToSan(canonicalUci, fenBefore).ifBlank { normalizedSan }

            if (!board.doMove(parsedMove)) {
                return ParseResult.Failure("Не удалось выполнить ход на $moveNumber-м ходу: «$rawToken»")
            }

            canonicalSans.add(cleanSan)
            canonicalUcis.add(canonicalUci)

            if (!isCustomFen && plyIdx < 24) {
                val bookMatch = OpeningDatabase.matchBookMove(board.fen)
                if (bookMatch?.openingName != null) {
                    detectedOpeningName = bookMatch.openingName
                    if (!bookMatch.eco.isNullOrBlank()) {
                        detectedEco = bookMatch.eco
                    }
                }
            }
        }

        if (canonicalSans.isEmpty()) {
            return ParseResult.Failure("В PGN не найдено легальных ходов")
        }

        // Если результат в заголовках не был указан, но указан в конце текста ходов или на доске мат/пат
        val resolvedResult = when {
            metadata.result != PgnResult.ONGOING -> metadata.result
            extracted.trailingResult != PgnResult.ONGOING -> extracted.trailingResult
            board.isMated -> if (board.sideToMove == Side.WHITE) PgnResult.BLACK_WIN else PgnResult.WHITE_WIN
            board.isStaleMate -> PgnResult.DRAW
            else -> PgnResult.ONGOING
        }
        val finalMetadata = if (resolvedResult != metadata.result) {
            metadata.copy(result = resolvedResult)
        } else {
            metadata
        }

        val clockSummary = buildClockSummary(extracted.clocksSecondsByPly, firstMoveIsWhite)
        val plyCount = canonicalSans.size
        val fullMoveCount = (plyCount + if (firstMoveIsWhite) 1 else 2) / 2

        val fingerprint = computeCanonicalFingerprint(
            metadata = finalMetadata,
            startFen = if (isCustomFen) startFen else null,
            canonicalMoves = canonicalUcis
        )

        val enrichedPgn = buildEnrichedPgn(
            originalCleaned = cleaned,
            metadata = finalMetadata,
            detectedEco = detectedEco,
            detectedOpeningName = detectedOpeningName,
            resolvedResult = resolvedResult
        )

        return ParseResult.Success(
            ParsedPgnGame(
                rawPgn = cleaned,
                enrichedPgn = enrichedPgn,
                metadata = finalMetadata,
                sanMoves = canonicalSans,
                plyCount = plyCount,
                fullMoveCount = fullMoveCount,
                firstMoveIsWhite = firstMoveIsWhite,
                detectedEco = detectedEco,
                detectedOpeningName = detectedOpeningName,
                clocks = clockSummary,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseSingleMoveStrict(board: Board, fenBefore: String, sanOrUci: String): Move? {
        if (ChessMoveUtils.isUciMove(sanOrUci)) {
            return ChessMoveUtils.parseUciMove(board, sanOrUci)
        }
        return try {
            val ml = MoveList(fenBefore)
            ml.addSanMove(sanOrUci, true, true)
            val m = ml.firstOrNull() ?: return null
            if (board.isMoveLegal(m, true)) m else null
        } catch (_: Exception) {
            null
        }
    }

    // ==================== АВТООПРЕДЕЛЕНИЕ ЦВЕТА И ДЕДУПЛИКАЦИЯ ====================

    /**
     * Пытается определить цвет пользователя по списку его никнеймов (ник в приложении,
     * ник на Lichess/Chess.com, локальная часть email).
     * Возвращает null, если однозначного совпадения нет.
     */
    fun detectPlayerColor(
        metadata: PgnMetadata,
        candidateNames: List<String>
    ): ChessColor? {
        val white = metadata.whiteName?.trim()?.takeIf { it.isNotEmpty() }
        val black = metadata.blackName?.trim()?.takeIf { it.isNotEmpty() }
        if (white == null && black == null) return null

        val cleanCandidates = candidateNames
            .map { it.trim() }
            .filter { it.length >= 2 && it.lowercase(Locale.ROOT) !in PLACEHOLDER_NAMES }
            .distinct()

        if (cleanCandidates.isEmpty()) return null

        // 1. Точное совпадение без учёта регистра
        for (cand in cleanCandidates) {
            val matchW = white != null && white.equals(cand, ignoreCase = true)
            val matchB = black != null && black.equals(cand, ignoreCase = true)
            if (matchW && !matchB) return ChessColor.WHITE
            if (matchB && !matchW) return ChessColor.BLACK
        }

        // 2. Нормализованное совпадение (без пробелов, подчёркиваний и дефисов, например "gad_pam" == "gadpam")
        val whiteNorm = white?.lowercase(Locale.ROOT)?.replace(ALNUM_REGEX, "")
        val blackNorm = black?.lowercase(Locale.ROOT)?.replace(ALNUM_REGEX, "")
        for (cand in cleanCandidates) {
            val candNorm = cand.lowercase(Locale.ROOT).replace(ALNUM_REGEX, "")
            if (candNorm.length < 3) continue
            val matchW = whiteNorm == candNorm
            val matchB = blackNorm == candNorm
            if (matchW && !matchB) return ChessColor.WHITE
            if (matchB && !matchW) return ChessColor.BLACK
        }

        return null
    }

    /**
     * Вычисляет устойчивый отпечаток уже сохранённой партии (быстро, без полного перебора доски,
     * если партия длинная, но совместимо с [parseAndValidate] через каноническую последовательность ходов).
     */
    fun computeFingerprintForStoredPgn(pgnData: String): String? {
        val parsed = parseAndValidate(pgnData)
        if (parsed is ParseResult.Success) {
            return parsed.game.fingerprint
        }
        // Резервный отпечаток по нормализованному тексту ходов для нестандартных старых записей
        return runCatching {
            val (meta, movetext) = extractMetadataAndMovetext(pgnData)
            meta.gameUrl?.lowercase(Locale.ROOT)?.let { url ->
                return@runCatching sha256Hex("url:$url")
            }
            val tokens = extractMainlineTokensAndClocks(movetext).sanTokens
                .map { normalizeSanToken(it) }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) null else sha256Hex("san:${tokens.joinToString(" ")}")
        }.getOrNull()
    }

    private fun computeCanonicalFingerprint(
        metadata: PgnMetadata,
        startFen: String?,
        canonicalMoves: List<String>
    ): String {
        // Уникальный URL партии на Lichess / Chess.com однозначно идентифицирует партию
        val uniqueUrl = metadata.gameUrl?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT)
        if (uniqueUrl != null && isSpecificGameUrl(uniqueUrl)) {
            return sha256Hex("url:$uniqueUrl")
        }

        val fenKey = startFen?.trim().orEmpty()
        val movesKey = canonicalMoves.joinToString(" ")

        // Партии длиной >= 16 полуходов (8+ полных ходов) с идентичной последовательностью ходов
        // считаем дубликатами, если только оба имени игроков явно не различаются.
        // Для коротких миниатюр (< 16 полуходов) дополнительно учитываем игроков, дату и результат.
        val contextKey = if (canonicalMoves.size < 16) {
            val w = metadata.whiteName?.lowercase(Locale.ROOT).orEmpty()
            val b = metadata.blackName?.lowercase(Locale.ROOT).orEmpty()
            val d = metadata.dateLabel.orEmpty()
            "|short:$w|$b|$d|${metadata.result.token}"
        } else {
            ""
        }
        return sha256Hex("moves:$fenKey|$movesKey$contextKey")
    }

    private fun isSpecificGameUrl(lowerUrl: String): Boolean {
        if ("lichess.org/" in lowerUrl) {
            val path = lowerUrl.substringAfter("lichess.org/").substringBefore('?').trim('/')
            return path.isNotEmpty() && path.length >= 8 && path != "analysis" && path != "paste"
        }
        if ("chess.com/" in lowerUrl) {
            return "/game/" in lowerUrl || "/live/" in lowerUrl || "/daily/" in lowerUrl
        }
        return false
    }

    // ==================== БЫСТРАЯ СВОДКА ДЛЯ UI-КАРТОЧЕК ====================

    /**
     * Быстро извлекает человекочитаемую карточку партии без обращения к диску и БД.
     */
    fun extractGameCardInfo(game: Game): GameCardInfo {
        val cleaned = game.pgnData
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val (metadata, movetext) = extractMetadataAndMovetext(cleaned)
        val extracted = extractMainlineTokensAndClocks(movetext)

        val startSideIsWhite = metadata.startFen
            ?.trim()
            ?.split(' ')
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT) != "b"

        val plyCount = extracted.sanTokens.size
        val fullMoveCount = if (plyCount == 0) 0 else (plyCount + if (startSideIsWhite) 1 else 2) / 2

        val resolvedResult = if (metadata.result != PgnResult.ONGOING) {
            metadata.result
        } else {
            extracted.trailingResult
        }
        val outcome = resolvedResult.forPlayer(game.playerColor)

        val isWhite = game.playerColor == ChessColor.WHITE
        val playerName = if (isWhite) metadata.whiteName else metadata.blackName
        val playerElo = if (isWhite) metadata.whiteElo else metadata.blackElo
        val opponentName = if (isWhite) metadata.blackName else metadata.whiteName
        val opponentElo = if (isWhite) metadata.blackElo else metadata.whiteElo
        val opponentTitle = if (isWhite) metadata.blackTitle else metadata.whiteTitle

        val title = buildOpponentTitle(opponentTitle, opponentName, opponentElo)
            ?: if (game.id != null) "Партия #${game.id}" else "Партия"

        val whiteDisplay = buildPlayerDisplay(metadata.whiteTitle, metadata.whiteName, metadata.whiteElo)
        val blackDisplay = buildPlayerDisplay(metadata.blackTitle, metadata.blackName, metadata.blackElo)

        // Контроль времени: учитываем и поле game.timeControl, и заголовок [TimeControl]
        val tcInfo = game.timeControl
            ?.takeIf { it.isNotBlank() }
            ?.let { parseTimeControl(it) }
            ?: metadata.timeControl
        val speedCategory = tcInfo?.category ?: TimeControlCategory.UNKNOWN
        val timeControlLabel = tcInfo?.fullLabel ?: "Без контроля"

        // Дебют: сначала из тегов (у новых партий там уже русское название), иначе быстрый поиск по первым ходам
        var eco = metadata.eco
        var openingName = metadata.openingName
        if (openingName == null && metadata.startFen == null && extracted.sanTokens.isNotEmpty()) {
            val detected = detectOpeningFast(extracted.sanTokens)
            if (detected != null) {
                eco = eco ?: detected.first
                openingName = detected.second
            }
        }
        val openingLabel = formatOpeningLabel(eco, openingName)

        val clockSummary = buildClockSummary(extracted.clocksSecondsByPly, startSideIsWhite)

        val dateLabel = metadata.dateLabel ?: formatEpochMillisDate(game.playedAt)

        return GameCardInfo(
            title = title,
            whiteDisplay = whiteDisplay,
            blackDisplay = blackDisplay,
            playerName = playerName,
            playerElo = playerElo,
            opponentName = opponentName,
            opponentElo = opponentElo,
            opponentTitle = opponentTitle,
            result = resolvedResult,
            outcome = outcome,
            terminationRu = metadata.terminationRu,
            timeControlLabel = timeControlLabel,
            speedCategory = speedCategory,
            eco = eco,
            openingName = openingName,
            openingLabel = openingLabel,
            event = metadata.event,
            platform = metadata.platform,
            gameUrl = metadata.gameUrl,
            dateLabel = dateLabel,
            plyCount = plyCount,
            fullMoveCount = fullMoveCount,
            hasClockData = clockSummary.hasData,
            playerTimeTroubleMoves = clockSummary.timeTroubleMovesFor(game.playerColor)
        )
    }

    private fun detectOpeningFast(sanTokens: List<String>): Pair<String?, String>? {
        return runCatching {
            val board = Board()
            var best: Pair<String?, String>? = null
            for (raw in sanTokens.take(16)) {
                val san = normalizeSanToken(raw)
                if (san.isEmpty()) continue
                val fenNow = board.fen
                val move = parseSingleMoveStrict(board, fenNow, san) ?: break
                if (!board.doMove(move)) break
                val m = OpeningDatabase.matchBookMove(board.fen)
                if (m?.openingName != null) {
                    best = m.eco to m.openingName
                }
            }
            best
        }.getOrNull()
    }

    // ==================== ПАРСИНГ КОНТРОЛЯ ВРЕМЕНИ ====================

    /**
     * Разбирает контроль времени в формате PGN-секунд ("180+2", "600", "900+10", "1/86400")
     * или человекочитаемых минут ("3+2", "5+0", "10+5", "Блиц 3+2").
     */
    fun parseTimeControl(rawInput: String?, fromPgnTag: Boolean = false): TimeControlInfo? {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isEmpty() || raw == "-" || raw == "?" || raw == "*") return null

        // Если строка уже содержит русское название категории ("Блиц 3+2")
        for (cat in TimeControlCategory.entries) {
            if (cat != TimeControlCategory.UNKNOWN &&
                raw.startsWith(cat.displayName, ignoreCase = true)
            ) {
                val rest = raw.substring(cat.displayName.length).trim()
                return TimeControlInfo(raw = raw, shortLabel = rest, category = cat)
            }
        }

        // Заочные партии: "1/86400", "1/259200"
        CORRESP_TC_REGEX.matchEntire(raw)?.let { m ->
            val moves = m.groupValues[1].toLongOrNull() ?: 1L
            val secs = m.groupValues[2].toLongOrNull() ?: 0L
            if (secs >= 86_400L && moves in 1L..5L) {
                val days = (secs / (86_400L * moves)).coerceAtLeast(1L)
                return TimeControlInfo(
                    raw = raw,
                    shortLabel = "$days дн./ход",
                    category = TimeControlCategory.CORRESPONDENCE
                )
            }
        }

        if (raw.equals("correspondence", ignoreCase = true) || raw.equals("daily", ignoreCase = true)) {
            return TimeControlInfo(raw, "", TimeControlCategory.CORRESPONDENCE)
        }

        // Турнирный контроль по фазам: "40/5400+30"
        STAGE_TC_REGEX.find(raw)?.let { m ->
            val baseSec = m.groupValues[1].toIntOrNull() ?: return@let
            val incSec = m.groupValues[2].toIntOrNull() ?: 0
            if (baseSec >= 60) {
                val mins = baseSec / 60
                val label = if (incSec > 0) "$mins+$incSec" else "$mins+0"
                val cat = classifyEstimatedSeconds(baseSec + 40 * incSec)
                return TimeControlInfo(raw, label, cat)
            }
        }

        // Стандартный формат "X+Y" или "X"
        SECONDS_TC_REGEX.matchEntire(raw)?.let { m ->
            val first = m.groupValues[1].toIntOrNull() ?: return null
            val inc = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val hasPlus = '+' in raw

            // В заголовке PGN [TimeControl] единица всегда секунды по стандарту PGN;
            // при ручном вводе учитываем популярные форматы в минутах ("3+2", "10+0", "15+10", "90+30").
            val treatAsSeconds = if (fromPgnTag) {
                true
            } else {
                when {
                    first in setOf(60, 90, 120) && inc >= 10 -> false // классика 60+30, 90+30, 120+30
                    first >= 180 && (first % 15 == 0) -> true
                    first in setOf(60, 90, 120) && inc <= 2 -> true   // пуля 60+0, 90+1, 120+1
                    !hasPlus && first >= 60 -> true
                    first in setOf(15, 30, 45) && inc <= 2 -> true
                    first == 0 && inc > 0 -> true
                    else -> false
                }
            }

            val baseSeconds = if (treatAsSeconds) first else first * 60
            val label = formatMinutesAndIncrement(baseSeconds, inc)
            val category = classifyEstimatedSeconds(baseSeconds + 40 * inc)
            return TimeControlInfo(raw = raw, shortLabel = label, category = category)
        }

        return TimeControlInfo(raw = raw, shortLabel = raw, category = TimeControlCategory.UNKNOWN)
    }

    private fun formatMinutesAndIncrement(baseSeconds: Int, incSeconds: Int): String {
        val basePart = when {
            baseSeconds == 15 -> "¼"
            baseSeconds == 30 -> "½"
            baseSeconds == 45 -> "¾"
            baseSeconds == 90 -> "1½"
            baseSeconds % 60 == 0 -> "${baseSeconds / 60}"
            else -> "${baseSeconds}с"
        }
        return "$basePart+$incSeconds"
    }

    /**
     * Классификация контроля по формуле Lichess: estimatedDuration = baseSeconds + 40 * incSeconds.
     */
    private fun classifyEstimatedSeconds(estimatedSeconds: Int): TimeControlCategory = when {
        estimatedSeconds <= 0 -> TimeControlCategory.UNKNOWN
        estimatedSeconds < 180 -> TimeControlCategory.BULLET
        estimatedSeconds < 480 -> TimeControlCategory.BLITZ
        estimatedSeconds < 1500 -> TimeControlCategory.RAPID
        else -> TimeControlCategory.CLASSICAL
    }

    // ==================== ИЗВЛЕЧЕНИЕ ЗАГОЛОВКОВ, ХОДОВ И ЧАСОВ ====================

    fun extractMetadata(pgn: String): PgnMetadata = extractMetadataAndMovetext(pgn).first

    private fun extractMetadataAndMovetext(cleanedPgn: String): Pair<PgnMetadata, String> {
        val tags = LinkedHashMap<String, String>()
        val movetext = StringBuilder()
        var inHeaderRegion = true

        for (line in cleanedPgn.split('\n')) {
            val trimmed = line.trim()
            if (inHeaderRegion) {
                if (trimmed.isEmpty() || trimmed.startsWith('%')) continue
                val match = TAG_LINE_REGEX.matchEntire(trimmed)
                if (match != null) {
                    val key = match.groupValues[1]
                    val value = unescapeTagValue(match.groupValues[2])
                    tags[key] = value
                    continue
                }
                // Строка с несколькими тегами подряд: [White "A"] [Black "B"] 1. e4 e5
                if (trimmed.startsWith('[')) {
                    var lastEnd = 0
                    for (m in ANY_TAG_REGEX.findAll(trimmed)) {
                        tags[m.groupValues[1]] = unescapeTagValue(m.groupValues[2])
                        lastEnd = m.range.last + 1
                    }
                    val remainder = trimmed.substring(lastEnd).trim()
                    if (remainder.isNotEmpty()) {
                        inHeaderRegion = false
                        movetext.append(remainder).append('\n')
                    }
                    continue
                }
                inHeaderRegion = false
            }
            movetext.append(line).append('\n')
        }

        fun tag(name: String): String? =
            tags.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

        val rawEvent = tag("Event")
        val event = rawEvent?.takeIf { it.lowercase(Locale.ROOT) !in PLACEHOLDER_EVENTS }

        val site = tag("Site")?.takeIf { it.isNotEmpty() && it != "?" && it != "ChessMentor" }
        val link = tag("Link")?.takeIf { it.isNotEmpty() && it != "?" }
        val gameUrl = listOfNotNull(link, site)
            .firstNotNullOfOrNull { URL_REGEX.find(it)?.value }

        val platformSource = listOfNotNull(site, link, rawEvent).joinToString(" ").lowercase(Locale.ROOT)
        val platform = when {
            "lichess.org" in platformSource || "lichess" in platformSource -> GamePlatform.LICHESS
            "chess.com" in platformSource -> GamePlatform.CHESS_COM
            else -> GamePlatform.OTHER
        }

        val whiteName = cleanPlayerName(tag("White"))
        val blackName = cleanPlayerName(tag("Black"))
        val whiteElo = parseElo(tag("WhiteElo"))
        val blackElo = parseElo(tag("BlackElo"))
        val whiteTitle = cleanTitle(tag("WhiteTitle"))
        val blackTitle = cleanTitle(tag("BlackTitle"))

        val result = PgnResult.fromString(tag("Result"))
        val termination = tag("Termination")?.takeIf {
            it.isNotEmpty() && it != "?" && !it.equals("unterminated", ignoreCase = true)
        }
        val terminationRu = localizeTermination(termination)

        val eco = tag("ECO")
            ?.uppercase(Locale.ROOT)
            ?.takeIf { ECO_CODE_REGEX.matches(it) }

        val openingBase = tag("Opening")?.takeIf { it.isNotEmpty() && it != "?" }
        val variation = tag("Variation")?.takeIf { it.isNotEmpty() && it != "?" }
        val openingName = when {
            openingBase != null && variation != null && !openingBase.contains(variation, ignoreCase = true) ->
                "$openingBase: $variation"
            else -> openingBase
        }

        val timeControl = parseTimeControl(tag("TimeControl"), fromPgnTag = true)

        val utcDate = parsePgnDate(tag("UTCDate"))
        val localDate = parsePgnDate(tag("Date")) ?: parsePgnDate(tag("EndDate"))
        val effectiveDate = utcDate ?: localDate
        val utcTime = parsePgnTime(tag("UTCTime")) ?: parsePgnTime(tag("StartTime")) ?: parsePgnTime(tag("EndTime"))

        val playedAtMillis = effectiveDate?.let { date ->
            val time = utcTime ?: LocalTime.of(12, 0)
            if (utcDate != null || tag("UTCTime") != null) {
                date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        val dateLabel = effectiveDate?.format(DISPLAY_DATE_FMT)

        val startFen = tag("FEN")?.takeIf { it.isNotEmpty() && it != "?" }
        val variant = tag("Variant")?.takeIf { it.isNotEmpty() && it != "?" }

        val metadata = PgnMetadata(
            tags = tags,
            event = event,
            site = site,
            gameUrl = gameUrl,
            platform = platform,
            whiteName = whiteName,
            blackName = blackName,
            whiteElo = whiteElo,
            blackElo = blackElo,
            whiteTitle = whiteTitle,
            blackTitle = blackTitle,
            result = result,
            termination = termination,
            terminationRu = terminationRu,
            eco = eco,
            openingName = openingName,
            timeControl = timeControl,
            playedAtMillis = playedAtMillis,
            dateLabel = dateLabel,
            startFen = startFen,
            variant = variant
        )

        return metadata to movetext.toString()
    }

    private data class ExtractedMovetext(
        val sanTokens: List<String>,
        val clocksSecondsByPly: Map<Int, Int>,
        val trailingResult: PgnResult
    )

    /**
     * Однопроходный лексер главной линии PGN:
     * извлекает SAN-токены главной линии, привязывает комментарии {[%clk ...]} к последнему полуходу
     * и игнорирует вложенные варианты (...) и NAG ($1..$255).
     */
    private fun extractMainlineTokensAndClocks(movetext: String): ExtractedMovetext {
        val tokens = ArrayList<String>()
        val clocks = LinkedHashMap<Int, Int>()
        var trailingResult = PgnResult.ONGOING

        val currentToken = StringBuilder()
        val currentComment = StringBuilder()
        var inBrace = false
        var inLineComment = false
        var parenDepth = 0

        fun flushToken() {
            if (currentToken.isEmpty()) return
            val raw = currentToken.toString()
            currentToken.setLength(0)

            if (raw in RESULT_TOKENS) {
                trailingResult = PgnResult.fromString(raw)
                return
            }
            if (PURE_MOVE_NUMBER_REGEX.matches(raw) || NAG_TOKEN_REGEX.matches(raw)) {
                return
            }
            // Склеенная запись номера и хода: "1.e4", "12...Nxd5"
            val stripped = MOVE_NUMBER_PREFIX_REGEX.matchEntire(raw)?.groupValues?.get(1) ?: raw
            if (stripped.isEmpty() || stripped in RESULT_TOKENS || NAG_TOKEN_REGEX.matches(stripped)) {
                if (stripped in RESULT_TOKENS) trailingResult = PgnResult.fromString(stripped)
                return
            }
            tokens.add(stripped)
        }

        for (ch in movetext) {
            if (inLineComment) {
                if (ch == '\n') inLineComment = false
                continue
            }
            if (inBrace) {
                if (ch == '}') {
                    inBrace = false
                    if (parenDepth == 0 && tokens.isNotEmpty()) {
                        parseClockSeconds(currentComment.toString())?.let { secs ->
                            clocks[tokens.lastIndex] = secs
                        }
                    }
                    currentComment.setLength(0)
                } else {
                    currentComment.append(ch)
                }
                continue
            }

            when {
                ch == '{' -> {
                    if (parenDepth == 0) flushToken()
                    inBrace = true
                    currentComment.setLength(0)
                }
                ch == ';' -> {
                    if (parenDepth == 0) flushToken()
                    inLineComment = true
                }
                ch == '(' -> {
                    if (parenDepth == 0) flushToken()
                    parenDepth++
                }
                ch == ')' -> {
                    if (parenDepth > 0) parenDepth--
                }
                ch.isWhitespace() -> {
                    if (parenDepth == 0) flushToken()
                }
                else -> {
                    if (parenDepth == 0) currentToken.append(ch)
                }
            }
        }
        if (parenDepth == 0) flushToken()

        return ExtractedMovetext(tokens, clocks, trailingResult)
    }

    private fun parseClockSeconds(comment: String): Int? {
        val m = CLK_REGEX.find(comment) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val sec = m.groupValues[3].toIntOrNull() ?: return null
        return h * 3600 + min * 60 + sec
    }

    private fun buildClockSummary(
        clocksByPly: Map<Int, Int>,
        firstMoveIsWhite: Boolean,
        timeTroubleThresholdSec: Int = 20
    ): ClockSummary {
        if (clocksByPly.isEmpty()) {
            return ClockSummary(emptyMap(), 0, 0, null, null)
        }
        var wTrouble = 0
        var bTrouble = 0
        var wMin: Int? = null
        var bMin: Int? = null

        for ((plyIdx, secs) in clocksByPly) {
            val isWhite = if (firstMoveIsWhite) plyIdx % 2 == 0 else plyIdx % 2 != 0
            if (isWhite) {
                if (secs < timeTroubleThresholdSec) wTrouble++
                wMin = if (wMin == null) secs else minOf(wMin, secs)
            } else {
                if (secs < timeTroubleThresholdSec) bTrouble++
                bMin = if (bMin == null) secs else minOf(bMin, secs)
            }
        }
        return ClockSummary(clocksByPly, wTrouble, bTrouble, wMin, bMin)
    }

    private fun normalizeSanToken(token: String): String {
        return token
            .trim()
            .replace(TRAILING_ANNOTATION_REGEX, "")
            .replace("0-0-0", "O-O-O")
            .replace("0-0", "O-O")
            // Поддержка фигурных Unicode-обозначений (♘f3 -> Nf3, ♗b5 -> Bb5)
            .replace('♔', 'K').replace('♚', 'K')
            .replace('♕', 'Q').replace('♛', 'Q')
            .replace('♖', 'R').replace('♜', 'R')
            .replace('♗', 'B').replace('♝', 'B')
            .replace('♘', 'N').replace('♞', 'N')
            .replace("e.p.", "")
            .trim()
    }

    private fun buildEnrichedPgn(
        originalCleaned: String,
        metadata: PgnMetadata,
        detectedEco: String?,
        detectedOpeningName: String?,
        resolvedResult: PgnResult
    ): String {
        val extraTags = mutableListOf<String>()
        val hasEco = metadata.tags.keys.any { it.equals("ECO", ignoreCase = true) }
        val hasOpening = metadata.tags.keys.any { it.equals("Opening", ignoreCase = true) }
        val hasResult = metadata.tags.keys.any { it.equals("Result", ignoreCase = true) }

        if (!hasEco && !detectedEco.isNullOrBlank()) {
            extraTags.add("""[ECO "${escapeTagValue(detectedEco)}"]""")
        }
        // Если русское название дебюта найдено в базе, а тега [Opening] нет — сохраняем его в заголовок
        if (!hasOpening && !detectedOpeningName.isNullOrBlank()) {
            extraTags.add("""[Opening "${escapeTagValue(detectedOpeningName)}"]""")
        }
        if (!hasResult && resolvedResult != PgnResult.ONGOING) {
            extraTags.add("""[Result "${resolvedResult.token}"]""")
        }

        if (extraTags.isEmpty()) return originalCleaned

        return if (metadata.tags.isEmpty()) {
            extraTags.joinToString("\n") + "\n\n" + originalCleaned
        } else {
            extraTags.joinToString("\n") + "\n" + originalCleaned
        }
    }

    // ==================== ФОРМАТИРОВАНИЕ И ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

    private fun unescapeTagValue(raw: String): String =
        raw.replace("\\\\", "\\").replace("\\\"", "\"")

    private fun escapeTagValue(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun cleanPlayerName(raw: String?): String? {
        val t = raw?.trim()?.replace(WHITESPACE_REGEX, " ") ?: return null
        return if (t.lowercase(Locale.ROOT) in PLACEHOLDER_NAMES) null else t
    }

    private fun parseElo(raw: String?): Int? {
        val v = raw?.trim()?.toIntOrNull() ?: return null
        return v.takeIf { it in 100..4000 }
    }

    private fun cleanTitle(raw: String?): String? {
        val t = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        return if (t.isEmpty() || t == "?" || t == "-" || t == "NONE") null else t
    }

    private fun parsePgnDate(raw: String?): LocalDate? {
        val t = raw?.trim() ?: return null
        val m = DATE_REGEX.matchEntire(t) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (y !in 1800..2100 || mo !in 1..12 || d !in 1..31) return null
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }

    private fun parsePgnTime(raw: String?): LocalTime? {
        val t = raw?.trim() ?: return null
        val m = TIME_REGEX.find(t) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val s = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59 || s !in 0..59) return null
        return runCatching { LocalTime.of(h, min, s) }.getOrNull()
    }

    private fun localizeTermination(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val low = raw.lowercase(Locale.ROOT)
        return when {
            "checkmate" in low -> "мат"
            "resign" in low -> "сдача"
            "time forfeit" in low || "on time" in low || "timeout" in low -> "по времени"
            "stalemate" in low -> "пат"
            "repetition" in low -> "повторение"
            "insufficient" in low -> "недостаток материала"
            "50-move" in low || "fifty" in low -> "правило 50 ходов"
            "agreement" in low -> "по соглашению"
            "abandoned" in low -> "соперник покинул игру"
            low == "normal" -> null
            else -> null
        }
    }

    private fun buildOpponentTitle(title: String?, name: String?, elo: Int?): String? {
        if (name == null) return null
        val prefix = if (!title.isNullOrBlank()) "$title " else ""
        val suffix = if (elo != null) " ($elo)" else ""
        return "vs $prefix$name$suffix"
    }

    private fun buildPlayerDisplay(title: String?, name: String?, elo: Int?): String? {
        if (name == null) return null
        val prefix = if (!title.isNullOrBlank()) "$title " else ""
        val suffix = if (elo != null) " ($elo)" else ""
        return "$prefix$name$suffix"
    }

    fun formatOpeningLabel(eco: String?, openingName: String?): String? {
        val e = eco?.trim()?.takeIf { it.isNotEmpty() }
        val n = openingName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            e != null && n != null -> "$e · $n"
            n != null -> n
            e != null -> "ECO $e"
            else -> null
        }
    }

    private fun formatEpochMillisDate(millis: Long): String {
        if (millis <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DISPLAY_DATE_FMT)
        }.getOrDefault("")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        // Первых 16 байт (128 бит = 32 hex-символа) более чем достаточно против любых коллизий
        for (i in 0 until 16) {
            sb.append(String.format(Locale.US, "%02x", digest[i]))
        }
        return sb.toString()
    }
}
