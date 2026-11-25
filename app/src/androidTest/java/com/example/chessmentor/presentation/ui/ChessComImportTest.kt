package com.example.chessmentor.presentation.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.data.chesscom.ArchiveGame
import com.example.chessmentor.data.chesscom.ChessComArchive
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.domain.usecase.UploadGameUseCase
import com.example.chessmentor.presentation.ui.components.screen.ChessComImportDialog
import com.example.chessmentor.presentation.ui.components.screen.UploadGameScreen
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test fun libraryRefreshFailureAfterSavedChessComGamesRetriesWithoutDuplicateWritesOrEngine() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(
            User(nickname = "ArchiveNick", email = "cc_refresh_${System.nanoTime()}@test.local", passwordHash = "test")
        )
        val uid = requireNotNull(user.id)
        val failLibraryRead = AtomicBoolean(true)
        val engineCalls = AtomicInteger(0)
        val spyEngine = object : ChessEngine by container.chessEngine {
            override suspend fun init(): Boolean {
                engineCalls.incrementAndGet()
                return false
            }

            override suspend fun ensureReadyOnStartup(): Boolean {
                engineCalls.incrementAndGet()
                return false
            }

            override suspend fun evaluate(fen: String, depthLimit: Int): Int {
                engineCalls.incrementAndGet()
                return 0
            }

            override suspend fun getBestMove(fen: String, depthLimit: Int): String? {
                engineCalls.incrementAndGet()
                return null
            }

            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): com.example.chessmentor.data.engine.AnalysisLine? {
                engineCalls.incrementAndGet()
                return null
            }
        }
        val analysis = AnalyzeGameUseCase(
            container.gameRepository,
            container.mistakeRepository,
            container.analyzedMoveRepository,
            container.userRepository,
            spyEngine,
            container.engineSettingsRepository
        )
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GameViewModel(
                    container = container,
                    analyzeGame = analysis,
                    observeGames = { id ->
                        flow {
                            if (failLibraryRead.get()) {
                                throw IOException("Simulated library read failure")
                            }
                            container.observeUserGames(id).collect { emit(it) }
                        }
                    }
                ) as T
        }
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, factory)[GameViewModel::class.java].also {
                it.setUser(user, persistSession = false)
            }
        }

        val archiveGames = (1..3).map { idx ->
            val raw = "[White \"ArchiveNick\"]\n[Black \"Opponent$idx\"]\n[Result \"1-0\"]\n\n1. e4 e${if (idx % 2 == 1) "5" else "6"} 2. Nf3 Nc6 1-0"
            val fp = requireNotNull(PgnParser.computeFingerprintForStoredPgn(raw))
            ArchiveGame(
                fingerprint = fp,
                pgn = raw,
                ended = 1_700_000_000L + idx,
                opponent = "Opponent$idx",
                side = ChessColor.WHITE,
                outcome = "Победа",
                libraryId = null
            )
        }

        var navigated = 0
        try {
            withTimeout(15_000L) {
                while (withContext(Dispatchers.Main) { vm.isLoadingLibrary.value }) delay(20)
            }

            withContext(Dispatchers.Main) {
                vm.importChessComGames(uid, archiveGames) { navigated++ }
            }
            withTimeout(15_000L) {
                while (withContext(Dispatchers.Main) { vm.isImporting.value || vm.isLoadingLibrary.value }) delay(20)
            }

            // 3 партии реально сохранены в Room, но чтение списка упало — повтор не должен их перезаписывать или запускать движок.
            assertEquals(3, container.gameRepository.findByUserId(uid).size)
            withContext(Dispatchers.Main) {
                assertTrue(vm.pendingChessComRetry.isEmpty())
                assertTrue(vm.pendingLibraryRefresh.value)
                assertTrue(vm.libraryRefreshError.value)
                assertEquals(0, navigated)
            }

            failLibraryRead.set(false)
            withContext(Dispatchers.Main) {
                vm.refreshLibrary { navigated++ }
            }
            withTimeout(15_000L) {
                while (withContext(Dispatchers.Main) {
                    vm.isLoadingLibrary.value || vm.userGames.size < 3
                }) delay(20)
            }

            assertEquals(3, container.gameRepository.findByUserId(uid).size)
            assertEquals(0, engineCalls.get())
            withContext(Dispatchers.Main) {
                assertEquals(3, vm.userGames.size)
                assertFalse(vm.pendingLibraryRefresh.value)
                assertFalse(vm.libraryRefreshError.value)
                assertEquals(1, navigated)
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            container.gameRepository.findByUserId(uid).forEach { it.id?.let { gid -> container.gameRepository.deleteById(gid) } }
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun leavingUploadScreenDuringChessComImportNeverHijacksNavigation() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(
            User(nickname = "LeaveUpload", email = "cc_leave_${System.nanoTime()}@test.local", passwordHash = "test")
        )
        val uid = requireNotNull(user.id)
        val enteredSave = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val gatedRepo = object : GameRepository by container.gameRepository {
            override suspend fun save(game: Game): Game {
                enteredSave.complete(Unit)
                releaseSave.await()
                return container.gameRepository.save(game)
            }
        }
        val gatedUpload = UploadGameUseCase(gatedRepo, container.userRepository)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GameViewModel(
                    container = container,
                    uploadGames = gatedUpload
                ) as T
        }
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, factory)[GameViewModel::class.java].also {
                it.setUser(user, persistSession = false)
            }
        }

        val pgn = "[White \"LeaveUpload\"]\n[Black \"RivalTwo\"]\n[Result \"1-0\"]\n\n1. d4 d5 2. c4 e6 1-0"
        val archive = ArchiveGame(
            fingerprint = requireNotNull(PgnParser.computeFingerprintForStoredPgn(pgn)),
            pgn = pgn,
            ended = 1_700_000_200L,
            opponent = "RivalTwo",
            side = ChessColor.WHITE,
            outcome = "Победа",
            libraryId = null
        )

        var showUpload by mutableStateOf(true)
        var navigatedCount = 0
        withContext(Dispatchers.Main) {
            vm.pendingChessComRetry.add(archive)
        }

        ui.setContent {
            if (showUpload) {
                UploadGameScreen(
                    paddingValues = PaddingValues(),
                    gameViewModel = vm,
                    engineSettingsRepository = container.engineSettingsRepository,
                    onGameUploaded = {},
                    onAnalysisComplete = {},
                    onOpenEngineSettings = {},
                    onNavigateToLibrary = { navigatedCount++ }
                )
            }
        }

        try {
            ui.onNodeWithText("Повторить оставшиеся (1)").performScrollTo().performClick()
            withTimeout(15_000L) { enteredSave.await() }

            // Пользователь уходит с экрана импорта до завершения записи
            ui.runOnUiThread { showUpload = false }
            ui.waitForIdle()

            releaseSave.complete(Unit)
            withTimeout(15_000L) {
                while (withContext(Dispatchers.Main) { vm.isImporting.value }) delay(20)
            }
            ui.waitForIdle()

            assertEquals(0, navigatedCount)
            assertEquals(1, container.gameRepository.findByUserId(uid).size)
        } finally {
            releaseSave.complete(Unit)
            withContext(Dispatchers.Main) { store.clear() }
            container.gameRepository.findByUserId(uid).forEach { it.id?.let { gid -> container.gameRepository.deleteById(gid) } }
            container.userRepository.deleteById(uid)
        }
    }
}
