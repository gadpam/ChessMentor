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
        val rows = source.latest("ArchiveNick", "2025/04", setOf(fp))
        assertEquals((60L downTo 51L).toList(), rows.map { it.ended })
        assertTrue(rows.first().inLibrary)
        assertEquals(ChessColor.BLACK, rows.first().side)
        assertEquals("Победа", rows.first().outcome)
    }

    @Test fun monthSelectionNeverImportsUntilConfirmed() {
        val submitted = mutableListOf<List<String>>()
        ui.setContent {
            ChessComImportDialog(savedName = "ArchiveNick", rememberSource = {}, monthsFor = { listOf("2025/04", "2025/01") },
                gamesFor = { _, month -> listOf(
                    ArchiveGame("old", "", 10, "Known", ChessColor.WHITE, "Ничья", true),
                    ArchiveGame(month, "", 11, "Rival", ChessColor.BLACK, "Победа", false)) },
                onImport = { rows -> submitted.add(rows.map { it.fingerprint }) }, onClose = {})
        }
        ui.onNodeWithText("Known").assertIsNotEnabled()
        ui.onNodeWithText("Rival").performScrollTo().performClick()
        ui.onNodeWithText("Раньше").performScrollTo().performClick()
        ui.onNodeWithText("Импортировать выбранные (0)").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(submitted.isEmpty()) }
        ui.onNodeWithText("Rival").performScrollTo().performClick()
        ui.onNodeWithText("Импортировать выбранные (1)").performClick()
        ui.runOnIdle { assertEquals(listOf(listOf("2025/01")), submitted) }
    }
}
