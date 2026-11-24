package com.example.chessmentor.domain.pgn

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.usecase.UploadGameUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnParserAndUploadTest {

    private class FakeUserRepository : UserRepository {
        private val users = mutableMapOf<Long, User>()
        private var nextId = 1L

        override suspend fun findById(id: Long): User? = users[id]
        override suspend fun findByEmail(email: String): User? =
            users.values.firstOrNull { it.email == email }
        override suspend fun findByNickname(nickname: String): User? =
            users.values.firstOrNull { it.nickname == nickname }
        override suspend fun save(user: User): User {
            val id = user.id ?: nextId++
            return user.copy(id = id).also { users[id] = it }
        }
        override suspend fun update(user: User): User {
            val id = requireNotNull(user.id)
            users[id] = user
            return user
        }
        override suspend fun deleteById(id: Long) { users.remove(id) }
        override suspend fun existsByEmail(email: String): Boolean = findByEmail(email) != null
        override suspend fun existsByNickname(nickname: String): Boolean = findByNickname(nickname) != null
        override suspend fun findAll(): List<User> = users.values.toList()
        override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
            users.values.filter { it.skillLevel == skillLevel }
        override suspend fun findTopByRating(limit: Int): List<User> =
            users.values.sortedByDescending { it.rating }.take(limit)
    }

    private class FakeGameRepository : GameRepository {
        private val games = mutableMapOf<Long, Game>()
        private var nextId = 1L

        override suspend fun findById(id: Long): Game? = games[id]
        override suspend fun findByUserId(userId: Long): List<Game> =
            games.values.filter { it.userId == userId }
        override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> =
            games.values.filter { it.userId == userId && it.analysisStatus == status }
        override suspend fun save(game: Game): Game {
            val id = game.id ?: nextId++
            return game.copy(id = id).also { games[id] = it }
        }
        override suspend fun update(game: Game): Game {
            val id = requireNotNull(game.id)
            games[id] = game
            return game
        }
        override suspend fun deleteById(id: Long) { games.remove(id) }
    }

    // ==================== ПАРСИНГ МЕТАДАННЫХ, ЧАСОВ И АННОТАЦИЙ ====================

    @Test
    fun parseAndValidate_extractsLichessHeadersClocksTimeTroubleAndOpening() {
        val lichessPgn = """
            [Event "Rated Blitz game"]
            [Site "https://lichess.org/AbCd1234"]
            [Date "2026.11.15"]
            [White "GadPam"]
            [Black "PetrovMaster"]
            [Result "1-0"]
            [UTCDate "2026.11.15"]
            [UTCTime "18:30:00"]
            [WhiteElo "1680"]
            [BlackElo "2140"]
            [BlackTitle "FM"]
            [TimeControl "180+2"]
            [Termination "Time forfeit"]

            1. e4! { [%clk 0:03:00] [%eval 0.25] } 1... e5 { [%clk 0:02:58] }
            2. Nf3 { [%clk 0:02:55] } (2. Bc4 Nf6) 2... Nc6 $1 { [%clk 0:02:50] }
            3. Bc4 { [%clk 0:00:18] } 3... Nf6 { [%clk 0:02:40] }
            4. 0-0 { [%clk 0:00:12] } 4... Be7 { [%clk 0:00:09] } 1-0
        """.trimIndent()

        val result = PgnParser.parseAndValidate(lichessPgn)
        assertTrue("Expected success, got $result", result is PgnParser.ParseResult.Success)
        val parsed = (result as PgnParser.ParseResult.Success).game
        val meta = parsed.metadata

        assertEquals(PgnParser.GamePlatform.LICHESS, meta.platform)
        assertEquals("https://lichess.org/AbCd1234", meta.gameUrl)
        assertEquals("GadPam", meta.whiteName)
        assertEquals("PetrovMaster", meta.blackName)
        assertEquals(1680, meta.whiteElo)
        assertEquals(2140, meta.blackElo)
        assertEquals("FM", meta.blackTitle)
        assertEquals(PgnParser.PgnResult.WHITE_WIN, meta.result)
        assertEquals("по времени", meta.terminationRu)
        assertEquals("15.11.2026", meta.dateLabel)
        assertNotNull(meta.playedAtMillis)

        assertEquals(PgnParser.TimeControlCategory.BLITZ, meta.timeControl?.category)
        assertEquals("Блиц 3+2", meta.timeControl?.fullLabel)

        assertEquals(8, parsed.plyCount)
        assertEquals(4, parsed.fullMoveCount)
        assertEquals("O-O", parsed.sanMoves[6])
        assertEquals("C55", parsed.effectiveEco)
        assertTrue(parsed.effectiveOpeningName?.contains("двух коней", ignoreCase = true) == true)

        assertTrue(parsed.clocks.hasData)
        assertEquals(2, parsed.clocks.whiteTimeTroubleMoves)
        assertEquals(1, parsed.clocks.blackTimeTroubleMoves)
        assertEquals(12, parsed.clocks.whiteMinSeconds)
        assertEquals(9, parsed.clocks.blackMinSeconds)
    }

    // ==================== ВАЛИДАЦИЯ ХОДОВ И ВАРИАНТОВ ====================

    @Test
    fun parseAndValidate_rejectsIllegalMovesAndExoticVariants() {
        val illegalPgn = "1. d4 d5 2. Bb5 a6"
        val illegalRes = PgnParser.parseAndValidate(illegalPgn)
        assertTrue(illegalRes is PgnParser.ParseResult.Failure)
        val msg = (illegalRes as PgnParser.ParseResult.Failure).errorMessage
        assertTrue("Expected move details in '$msg'", msg.contains("Bb5"))

        val atomicPgn = """
            [Event "Rated Atomic game"]
            [Variant "Atomic"]
            1. Nf3 f6 2. e3 g5 1-0
        """.trimIndent()
        val atomicRes = PgnParser.parseAndValidate(atomicPgn)
        assertTrue(atomicRes is PgnParser.ParseResult.Failure)
        assertTrue((atomicRes as PgnParser.ParseResult.Failure).errorMessage.contains("Atomic"))
    }

    // ==================== КОНТРОЛЬ ВРЕМЕНИ И АВТООПРЕДЕЛЕНИЕ ЦВЕТА ====================

    @Test
    fun parseTimeControl_handlesPgnSecondsManualMinutesAndCorrespondence() {
        val ultra = PgnParser.parseTimeControl("30+0")
        assertEquals(PgnParser.TimeControlCategory.BULLET, ultra?.category)
        assertEquals("Пуля ½+0", ultra?.fullLabel)

        val bullet = PgnParser.parseTimeControl("60+0")
        assertEquals(PgnParser.TimeControlCategory.BULLET, bullet?.category)
        assertEquals("Пуля 1+0", bullet?.fullLabel)

        val blitzFromSec = PgnParser.parseTimeControl("180+2")
        val blitzFromMin = PgnParser.parseTimeControl("3+2")
        assertEquals("Блиц 3+2", blitzFromSec?.fullLabel)
        assertEquals("Блиц 3+2", blitzFromMin?.fullLabel)

        val rapidFromSec = PgnParser.parseTimeControl("600")
        val rapidFromMin = PgnParser.parseTimeControl("10+0")
        assertEquals("Рапид 10+0", rapidFromSec?.fullLabel)
        assertEquals("Рапид 10+0", rapidFromMin?.fullLabel)

        val classical = PgnParser.parseTimeControl("1800+20")
        assertEquals(PgnParser.TimeControlCategory.CLASSICAL, classical?.category)
        assertEquals("Классика 30+20", classical?.fullLabel)
        assertEquals("Классика 90+30", PgnParser.parseTimeControl("90+30")?.fullLabel)
        assertEquals("Классика 90+30", PgnParser.parseTimeControl("5400+30", fromPgnTag = true)?.fullLabel)

        val daily = PgnParser.parseTimeControl("1/86400")
        assertEquals(PgnParser.TimeControlCategory.CORRESPONDENCE, daily?.category)
        assertEquals("Заочные 1 дн./ход", daily?.fullLabel)
    }

    @Test
    fun detectPlayerColor_matchesCaseInsensitiveAndNormalizedNicknames() {
        val meta = PgnParser.extractMetadata(
            """
            [White "Opponent99"]
            [Black "Gad_Pam"]
            1. d4 d5 *
            """.trimIndent()
        )

        assertEquals(ChessColor.BLACK, PgnParser.detectPlayerColor(meta, listOf("gadpam")))
        assertEquals(ChessColor.WHITE, PgnParser.detectPlayerColor(meta, listOf("OPPONENT99")))
        assertNull(PgnParser.detectPlayerColor(meta, listOf("SomeoneElse")))
    }

    // ==================== МУЛЬТИ-PGN, ДЕДУПЛИКАЦИЯ И КАРТОЧКИ БИБЛИОТЕКИ ====================

    @Test
    fun splitGamesAndBatchUpload_importsMultiPgnDetectsColorsAndSkipsDuplicates() = runBlocking {
        val userRepo = FakeUserRepository()
        val gameRepo = FakeGameRepository()
        val user = userRepo.save(
            User(
                nickname = "GadPam",
                email = "gadpam@example.com",
                passwordHash = "hash",
                rating = 1500
            )
        )
        val useCase = UploadGameUseCase(gameRepo, userRepo)

        val archive = "\uFEFF" + """
            [Event "Rated Blitz"]
            [Site "https://lichess.org/GameOne1"]
            [UTCDate "2026.11.10"]
            [White "GadPam"]
            [Black "AlphaPlayer"]
            [WhiteElo "1520"]
            [BlackElo "1560"]
            [Result "1-0"]
            [TimeControl "180+2"]

            1. e4 { [%clk 0:03:00] } e5 { [%clk 0:03:00] } 2. Nf3 Nc6 3. Bb5 a6 1-0

            [Event "Live Chess"]
            [Site "Chess.com"]
            [Link "https://www.chess.com/game/live/123456789"]
            [Date "2026.11.12"]
            [White "BetaPlayer"]
            [Black "gad_pam"]
            [WhiteElo "1610"]
            [BlackElo "1535"]
            [Result "1/2-1/2"]
            [TimeControl "600"]

            1. d4 d5 2. c4 e6 3. Nc3 Nf6 1/2-1/2

            [Event "Duplicate of Game 1"]
            [Site "https://lichess.org/GameOne1"]
            [White "GadPam"]
            [Black "AlphaPlayer"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0
        """.trimIndent().replace("\n", "\r\n")

        val blocks = PgnParser.splitGames(archive)
        assertEquals(3, blocks.size)

        val batchRes = useCase.executeBatch(
            UploadGameUseCase.BatchInput(
                userId = user.id!!,
                rawPgnArchive = archive,
                fallbackColor = ChessColor.WHITE
            )
        )
        assertTrue(batchRes is UploadGameUseCase.BatchResult.Success)
        val ok = batchRes as UploadGameUseCase.BatchResult.Success
        assertEquals(2, ok.importedGames.size)
        assertEquals(1, ok.skippedDuplicates)
        assertEquals(0, ok.failedCount)
        assertEquals(2, ok.autoDetectedColorsCount)

        val first = ok.importedGames[0]
        val second = ok.importedGames[1]
        assertEquals(ChessColor.WHITE, first.playerColor)
        assertEquals(ChessColor.BLACK, second.playerColor)
        assertEquals("Блиц 3+2", first.timeControl)
        assertEquals("Рапид 10+0", second.timeControl)

        val card1 = PgnParser.extractGameCardInfo(first)
        assertEquals("vs AlphaPlayer (1560)", card1.title)
        assertEquals(PgnParser.PlayerOutcome.WIN, card1.outcome)
        assertEquals(PgnParser.GamePlatform.LICHESS, card1.platform)
        assertTrue(card1.hasClockData)
        assertTrue(card1.searchBlob.contains("alphaplayer"))
        assertTrue(card1.searchBlob.contains("c65"))

        val card2 = PgnParser.extractGameCardInfo(second)
        assertEquals("vs BetaPlayer (1610)", card2.title)
        assertEquals(PgnParser.PlayerOutcome.DRAW, card2.outcome)
        assertEquals(PgnParser.GamePlatform.CHESS_COM, card2.platform)
        assertFalse(card2.hasClockData)

        // Повторная одиночная загрузка той же партии должна вернуть Duplicate
        val dupSingle = useCase.execute(
            UploadGameUseCase.Input(
                userId = user.id!!,
                pgnData = blocks[0],
                playerColor = ChessColor.WHITE
            )
        )
        assertTrue(dupSingle is UploadGameUseCase.Result.Duplicate)
    }

    @Test
    fun splitGames_handlesMultiTagHeaderLinesAndInlineMovetext() {
        val multiTagPgn = """
            [White "Alice"] [Black "Bob"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0

            [White "Carol"] [Black "Dave"] 1. d4 d5 2. c4 e6 0-1
        """.trimIndent()

        val blocks = PgnParser.splitGames(multiTagPgn)
        assertEquals(2, blocks.size)

        val first = PgnParser.parseAndValidate(blocks[0])
        val second = PgnParser.parseAndValidate(blocks[1])
        assertTrue(first is PgnParser.ParseResult.Success)
        assertTrue(second is PgnParser.ParseResult.Success)

        val g1 = (first as PgnParser.ParseResult.Success).game
        assertEquals("Alice", g1.metadata.whiteName)
        assertEquals("Bob", g1.metadata.blackName)
        assertEquals(4, g1.plyCount)

        val g2 = (second as PgnParser.ParseResult.Success).game
        assertEquals("Carol", g2.metadata.whiteName)
        assertEquals("Dave", g2.metadata.blackName)
        assertEquals(4, g2.plyCount)
    }

    @Test
    fun parseAndValidate_andExtractGameCardInfo_ignoreStandaloneEnPassantToken() {
        val epPgn = """
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. e4 c5 2. e5 d5 3. exd6 e.p. Qxd6 *
        """.trimIndent()

        val res = PgnParser.parseAndValidate(epPgn)
        assertTrue("Expected success, got $res", res is PgnParser.ParseResult.Success)
        val parsed = (res as PgnParser.ParseResult.Success).game
        assertEquals(6, parsed.plyCount)

        val card = PgnParser.extractGameCardInfo(
            Game(id = 1L, userId = 1L, pgnData = epPgn, playerColor = ChessColor.WHITE, playedAt = 1L)
        )
        assertEquals(6, card.plyCount)
        assertEquals(3, card.fullMoveCount)
    }

    @Test
    fun extractGameCardInfo_localizesChessComEcoUrlAndNeverExposesRawEcoFallback() {
        val chessComPgn = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [White "Hero"]
            [Black "Opponent"]
            [Result "1-0"]
            [ECO "A40"]
            [ECOUrl "https://www.chess.com/openings/Queens-Pawn-Opening-Horwitz-Defense-2.c4-e6"]

            1. d4 e6 2. c4 b6 3. e4 Bb7 1-0
        """.trimIndent()

        val card = PgnParser.extractGameCardInfo(
            Game(id = 7L, userId = 1L, pgnData = chessComPgn, playerColor = ChessColor.WHITE, playedAt = 1L)
        )
        assertEquals("A40", card.eco)
        assertEquals("Дебют ферзевых пешек: защита Горвица", card.openingName)
        assertEquals("A40 · Дебют ферзевых пешек: защита Горвица", card.openingLabel)

        val bareEcoPgn = """
            [White "Hero"]
            [Black "Opponent"]
            [ECO "A40"]
            [Result "1/2-1/2"]

            1/2-1/2
        """.trimIndent()
        val bareCard = PgnParser.extractGameCardInfo(
            Game(id = 8L, userId = 1L, pgnData = bareEcoPgn, playerColor = ChessColor.WHITE, playedAt = 1L)
        )
        assertEquals("A40 · Дебют ферзевых пешек", bareCard.openingLabel)
        assertFalse(bareCard.openingLabel!!.startsWith("ECO "))
    }

    @Test
    fun parseAndValidate_detectsResultConflictAndDoesNotAssignWinner() {
        val conflictedPgn = """
            [White "Hero"]
            [Black "Opponent"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 0-1
        """.trimIndent()

        val res = PgnParser.parseAndValidate(conflictedPgn)
        assertTrue("Expected success, got $res", res is PgnParser.ParseResult.Success)
        val parsed = (res as PgnParser.ParseResult.Success).game
        assertTrue(parsed.hasResultConflict)
        assertEquals(PgnParser.PgnResult.WHITE_WIN, parsed.headerResult)
        assertEquals(PgnParser.PgnResult.BLACK_WIN, parsed.trailingResult)
        assertEquals(PgnParser.PgnResult.ONGOING, parsed.metadata.result)

        val conflictedCard = PgnParser.extractGameCardInfo(
            Game(id = 10L, userId = 1L, pgnData = conflictedPgn, playerColor = ChessColor.WHITE, playedAt = 1L)
        )
        assertTrue(conflictedCard.hasResultConflict)
        assertEquals(PgnParser.PlayerOutcome.UNKNOWN, conflictedCard.outcome)

        // Контроль: согласованная сдача без мата (1-0 и 1-0) не считается конфликтом
        val resignationPgn = """
            [White "Hero"]
            [Black "Opponent"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0
        """.trimIndent()
        val okParsed = (PgnParser.parseAndValidate(resignationPgn) as PgnParser.ParseResult.Success).game
        assertFalse(okParsed.hasResultConflict)
        assertEquals(PgnParser.PgnResult.WHITE_WIN, okParsed.metadata.result)
        val okCard = PgnParser.extractGameCardInfo(
            Game(id = 11L, userId = 1L, pgnData = resignationPgn, playerColor = ChessColor.WHITE, playedAt = 1L)
        )
        assertFalse(okCard.hasResultConflict)
        assertEquals(PgnParser.PlayerOutcome.WIN, okCard.outcome)
    }
}
