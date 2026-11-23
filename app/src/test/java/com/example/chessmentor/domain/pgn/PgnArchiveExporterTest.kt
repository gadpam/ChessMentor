package com.example.chessmentor.domain.pgn

import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PgnArchiveExporterTest {
    private fun archive(pgn: String, nick: String = "Player") = PgnArchiveExporter.buildArchive(
        listOf(Game(userId = 1L, playerColor = ChessColor.BLACK, pgnData = pgn, playedAt = 0L)),
        nick, nowMs = 0L, zoneId = ZoneOffset.ofHours(-1)
    ).content

    @Test fun emptyArchiveHasNoBlankGameOrPhantomAnalysis() {
        val result = PgnArchiveExporter.buildArchive(emptyList(), "", 0L, ZoneOffset.UTC)
        assertEquals("", result.content)
        assertEquals(0, result.gamesCount)
        assertEquals(0, result.analyzedCount)
    }

    @Test fun headerlessGameKeepsItsFinalResultInTheGeneratedHeader() {
        for (token in listOf("1-0", "0-1", "1/2-1/2", "*")) {
            val result = archive("1. e4 e5 $token")
            assertTrue(result.contains("[Result \"$token\"]"))
            assertEquals("1. e4 e5 $token", result.trimEnd().lines().last())
        }
    }

    @Test fun existingHeadersArePreservedWhenAppendingDrawResult() {
        val pgn = "[Site \"Riga\"]\r\n[Result \"1/2-1/2\"]\r\n\r\n1. e4 e5"
        assertEquals(pgn.replace("\r\n", "\n") + " 1/2-1/2\n", archive(pgn))
    }

    @Test fun invalidResultTagIsNotAppendedAsMovetext() {
        assertTrue(archive("[Result \"unknown\"]\n\n1. e4 e5").endsWith("1. e4 e5 *\n"))
    }

    @Test fun generatedTagsEscapeUserTextWithoutExtraTagLines() {
        val result = archive("1. e4", "A\"B\\C\rD\nE")
        assertTrue(result.contains("[White \"Opponent\"]\n[Black \"A\\\"B\\\\C D E\"]"))
        assertTrue(result.contains("[Date \"1969.12.31\"]"))
    }

    @Test fun filenameStaysLocalAndUsesTheRequestedDateZone() {
        fun name(nick: String) = PgnArchiveExporter.suggestFileName(nick, 0L, ZoneOffset.ofHours(-1))
        assertEquals("chessmentor_ёж_1969-12-31.pgn", name("../Ёж/../"))
        assertEquals("chessmentor_games_1969-12-31.pgn", name("///"))
        assertEquals("chessmentor_${"я".repeat(24)}_1969-12-31.pgn", name("Я".repeat(80)))
    }

    @Test fun escapedQuoteHeaderIsPreservedAndNotDuplicated() {
        val pgn = "[White \"O\\\"Brien\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0"
        val out = archive(pgn)
        assertTrue(out.startsWith("[White \"O\\\"Brien\"]\n"))
        assertEquals(1, Regex("""\[White """).findAll(out).count())
    }

    @Test fun lineCommentDoesNotSwallowFallbackResultToken() {
        val pgn = "[Result \"1-0\"]\n\n1. e4 e5 ; opening note"
        val out = archive(pgn)
        assertEquals("[Result \"1-0\"]\n\n1. e4 e5 ; opening note\n1-0\n", out)
    }

    @Test fun unicodeHalfDrawNormalizesToCanonicalResultHeader() {
        val out = archive("1. e4 e5 ½-½")
        assertTrue(out.contains("[Result \"1/2-1/2\"]"))
        assertEquals("1. e4 e5 ½-½", out.trimEnd().lines().last())
    }
}
