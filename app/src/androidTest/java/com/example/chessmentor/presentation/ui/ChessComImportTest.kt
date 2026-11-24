package com.example.chessmentor.presentation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.chessmentor.data.chesscom.ArchiveGame
import com.example.chessmentor.data.chesscom.ChessComArchive
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.presentation.ui.components.screen.ChessComImportDialog
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChessComImportTest {
    @get:Rule val ui = createComposeRule()

    private fun pgn(i: Int) = "[White \"Rival$i\"]\n[Black \"ArchiveNick\"]\n[Result \"0-1\"]\n\n1. e4 e5 0-1"

    @Test fun latestTenStandardGamesAndLocalDuplicates() = runBlocking {
        val base = "https://api.chess.com/pub/player/archivenick/games/"
        val json = JSONArray()
        for (i in 1..61) json.put(JSONObject().put("end_time", i).put("rules", if (i == 61) "chess960" else "chess").put("pgn", pgn(i)))
        val source = ChessComArchive { url -> when (url) {
            base + "archives" -> JSONObject().put("archives", JSONArray(listOf(base + "2025/01", base + "2025/04", "https://other.example/2025/05"))).toString()
            base + "2025/04" -> JSONObject().put("games", json).toString()
            else -> error("Unexpected endpoint")
        } }
        assertEquals(listOf("2025/04", "2025/01"), source.months("ArchiveNick"))
        val fp = requireNotNull(PgnParser.computeFingerprintForStoredPgn(pgn(60)))
        val rows = source.latest("ArchiveNick", "2025/04", mapOf(fp to 37L))
        assertEquals((60L downTo 51L).toList(), rows.map { it.ended })
        assertEquals(37L, rows.first().libraryId)
        assertEquals(ChessColor.BLACK, rows.first().side)
        assertEquals("Победа", rows.first().outcome)

        val extended = source.latest("ArchiveNick", "2025/04", emptyMap(), limit = ChessComArchive.FULL_MONTH_FETCH_LIMIT)
        assertEquals(60, extended.size)
        assertEquals(60L, extended.first().ended)
        assertEquals(1L, extended.last().ended)
    }

    @Test fun paginationAndTimeClassFilterRevealOlderGamesBeyondTen() {
        val monthGames = (25 downTo 1).map { idx ->
            ArchiveGame(
                fingerprint = "fp_$idx",
                pgn = "",
                ended = idx.toLong(),
                opponent = "Opponent$idx",
                side = ChessColor.WHITE,
                outcome = "Победа",
                libraryId = null,
                timeClass = if (idx % 2 == 0) "rapid" else "blitz"
            )
        }
        ui.setContent {
            ChessComImportDialog(
                savedName = "ArchiveNick",
                rememberSource = {},
                monthsFor = { listOf("2025/04") },
                gamesFor = { _, _ -> monthGames },
                onOpenSaved = {},
                onImport = {},
                onClose = {}
            )
        }
        ui.onNodeWithText("Opponent25").assertExists()
        ui.onNodeWithText("Opponent15").assertDoesNotExist()
        ui.onNodeWithText("Показать ещё 10 (осталось 15)").performScrollTo().performClick()
        ui.onNodeWithText("Opponent15").assertExists()
        ui.onNodeWithText("Рапид (12)").performScrollTo().performClick()
        ui.onNodeWithText("Opponent24").assertExists()
        ui.onNodeWithText("Opponent25").assertDoesNotExist()
    }

    @Test fun monthSelectionNeverImportsUntilConfirmed() {
        val submitted = mutableListOf<List<String>>()
        val opened = mutableListOf<Long>()
        ui.setContent {
            ChessComImportDialog(savedName = "ArchiveNick", rememberSource = {}, monthsFor = { listOf("2025/04", "2025/01") },
                gamesFor = { _, month -> listOf(
                    ArchiveGame("old", "", 10, "Known", ChessColor.WHITE, "Ничья", 37L),
                    ArchiveGame(month, "", 11, "Rival", ChessColor.BLACK, "Победа", null)) },
                onOpenSaved = { opened.add(it) },
                onImport = { rows -> submitted.add(rows.map { it.fingerprint }) }, onClose = {})
        }
        ui.onNodeWithText("Known").assertIsNotEnabled()
        ui.onNodeWithText("Открыть партию №37").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(listOf(37L), opened); assertTrue(submitted.isEmpty()) }
        ui.onNodeWithText("Rival").performScrollTo().performClick()
        ui.onNodeWithText("Раньше").performScrollTo().performClick()
        ui.onNodeWithText("Импортировать выбранные (0)").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(submitted.isEmpty()) }
        ui.onNodeWithText("Rival").performScrollTo().performClick()
        ui.onNodeWithText("Импортировать выбранные (1)").performClick()
        ui.runOnIdle { assertEquals(listOf(listOf("2025/01")), submitted) }
    }
}
