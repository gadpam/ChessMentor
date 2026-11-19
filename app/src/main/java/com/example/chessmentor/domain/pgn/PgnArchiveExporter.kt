// domain/pgn/PgnArchiveExporter.kt
package com.example.chessmentor.domain.pgn

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Экспорт библиотеки партий пользователя в стандартный мульти-партийный PGN-архив,
 * совместимый с Lichess, Chess.com, ChessBase и повторным импортом в ChessMentor.
 */
object PgnArchiveExporter {

    private val PGN_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val FILE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val RESULT_TAG_REGEX = Regex("""^\s*\[Result\s+"([^"]+)"\]""", RegexOption.MULTILINE)
    private val RESULT_END_REGEX = Regex("""(?:1-0|0-1|1/2-1/2|\*)\s*$""")
    private val HAS_HEADERS_REGEX = Regex("""^\s*\[\w+\s+"[^"]*"\]""")

    data class ExportArchive(
        val content: String,
        val gamesCount: Int,
        val analyzedCount: Int,
        val suggestedFileName: String
    )

    /**
     * Формирует единый UTF-8 PGN-архив из [games] (в порядке от новых к старым).
     */
    fun buildArchive(
        games: List<Game>,
        playerNickname: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExportArchive {
        val sorted = games.sortedByDescending { it.playedAt }
        val blocks = sorted.mapNotNull { game ->
            formatSingleGame(game, playerNickname, zoneId).takeIf { it.isNotBlank() }
        }
        val content = if (blocks.isEmpty()) {
            ""
        } else {
            blocks.joinToString(separator = "\n\n", postfix = "\n")
        }
        return ExportArchive(
            content = content,
            gamesCount = blocks.size,
            analyzedCount = sorted.count { it.isAnalyzed() },
            suggestedFileName = suggestFileName(playerNickname, nowMs, zoneId)
        )
    }

    /** Генерирует безопасное имя файла вида `chessmentor_nick_2026-11-17.pgn`. */
    fun suggestFileName(
        playerNickname: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val datePart = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().format(FILE_DATE_FORMAT)
        val safeNick = playerNickname
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-zа-яё0-9_-]+"""), "_")
            .trim('_')
            .take(24)
        return if (safeNick.isNotEmpty()) {
            "chessmentor_${safeNick}_$datePart.pgn"
        } else {
            "chessmentor_games_$datePart.pgn"
        }
    }

    private fun formatSingleGame(
        game: Game,
        playerNickname: String,
        zoneId: ZoneId
    ): String {
        val normalized = game.pgnData
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return ""

        val safePlayer = escapePgnTagValue(playerNickname.ifBlank { "Player" })
        val withHeaders = if (HAS_HEADERS_REGEX.containsMatchIn(normalized)) {
            normalized
        } else {
            val dateStr = Instant.ofEpochMilli(game.playedAt)
                .atZone(zoneId)
                .toLocalDate()
                .format(PGN_DATE_FORMAT)
            val white = if (game.playerColor == ChessColor.WHITE) safePlayer else "Opponent"
            val black = if (game.playerColor == ChessColor.BLACK) safePlayer else "Opponent"
            buildString {
                appendLine("""[Event "ChessMentor Game"]""")
                appendLine("""[Date "$dateStr"]""")
                appendLine("""[White "$white"]""")
                appendLine("""[Black "$black"]""")
                game.timeControl?.takeIf { it.isNotBlank() }?.let { tc ->
                    appendLine("""[TimeControl "${escapePgnTagValue(tc)}"]""")
                }
                appendLine("""[Result "*"]""")
                appendLine()
                append(normalized)
            }
        }

        // Гарантируем завершающий токен результата для строгих PGN-ридеров
        return if (RESULT_END_REGEX.containsMatchIn(withHeaders)) {
            withHeaders
        } else {
            val tagResult = RESULT_TAG_REGEX.find(withHeaders)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it in setOf("1-0", "0-1", "1/2-1/2", "*") }
                ?: "*"
            "$withHeaders $tagResult"
        }
    }

    private fun escapePgnTagValue(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ').trim()
}
