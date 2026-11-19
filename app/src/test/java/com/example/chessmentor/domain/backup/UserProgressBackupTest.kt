package com.example.chessmentor.domain.backup

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.AnalyzedMoveRepository
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.repository.GameRepository
import com.example.chessmentor.domain.repository.MistakeRepository
import com.example.chessmentor.domain.repository.SessionRepository
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.session.AppThemeMode
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.HintUsage
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.usecase.ExportUserBackupUseCase
import com.example.chessmentor.domain.usecase.ImportUserBackupUseCase
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProgressBackupTest {

    private val fixedNow = 1_794_873_600_000L // 2026-11-17 00:00:00 UTC

    @Test
    fun `SimpleJson round-trips Cyrillic, PGN escapes, numbers and rejects excessive nesting`() {
        val original = SimpleJson.obj(
            "title" to SimpleJson.str("Сицилианская: \"Найдорф\"\n1. e4 c5 \\ {[%clk 0:03:00]}\t♔"),
            "accuracy" to SimpleJson.num(88.75),
            "eval" to SimpleJson.num(-145),
            "solved" to SimpleJson.bool(true),
            "empty" to SimpleJson.str(null),
            "moves" to SimpleJson.arr(listOf(SimpleJson.str("e2e4"), SimpleJson.str("c7c5")))
        )

        val pretty = SimpleJson.stringify(original, pretty = true)
        val parsed = SimpleJson.parseObject("\uFEFF$pretty")

        assertEquals(
            "Сицилианская: \"Найдорф\"\n1. e4 c5 \\ {[%clk 0:03:00]}\t♔",
            parsed.requireString("title")
        )
        assertEquals(88.75, parsed.doubleOrNull("accuracy")!!, 0.0001)
        assertEquals(-145, parsed.requireInt("eval"))
        assertEquals(true, parsed.boolOrNull("solved"))
        assertEquals(null, parsed.stringOrNull("empty"))
        assertEquals(2, parsed.arrOrEmpty("moves").size)

        // Проверка разбора \uXXXX
        val unicodeParsed = SimpleJson.parseObject("""{"eco":"\u0042\u0039\u0030"}""")
        assertEquals("B90", unicodeParsed.requireString("eco"))

        // Защита от чрезмерной вложенности
        val deep = "[".repeat(40) + "]".repeat(40)
        val deepFailed = runCatching { SimpleJson.parse(deep) }.isFailure
        assertTrue(deepFailed)
    }

    @Test
    fun `codec rejects tampered payload checksum and unsupported schema version`() {
        val snapshot = UserProgressBackupCodec.BackupSnapshot(
            exportedAtMs = fixedNow,
            profile = UserProgressBackupCodec.ProfileSection(
                nickname = "Tal Fan!",
                rating = 1620,
                isSoundEnabled = true,
                preferredBoardTheme = "Дерево",
                appThemeMode = AppThemeMode.DARK,
                chessUsernameHint = "mikhail_tal"
            )
        )

        assertEquals(
            "chessmentor_backup_tal_fan_2026-11-17.json",
            UserProgressBackupCodec.suggestFileName("Tal Fan!", fixedNow, ZoneOffset.UTC)
        )

        val encoded = UserProgressBackupCodec.encode(snapshot, pretty = true)
        assertTrue(UserProgressBackupCodec.decodeAndVerify(encoded) is UserProgressBackupCodec.DecodeResult.Success)

        // Подмена рейтинга в JSON обязана ловиться проверкой SHA-256
        val tampered = encoded.replace("1620", "2850")
        val tamperedRes = UserProgressBackupCodec.decodeAndVerify(tampered)
        assertTrue(tamperedRes is UserProgressBackupCodec.DecodeResult.Error)

        // Неподдерживаемая будущая версия схемы
        val futureVer = encoded.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertTrue(UserProgressBackupCodec.decodeAndVerify(futureVer) is UserProgressBackupCodec.DecodeResult.Error)
    }

    @Test
    fun `full export and import restores games, moves, personal puzzles and SM2 history idempotently`() = runBlocking {
        // 1. Исходное устройство (пользователь id=1)
        val srcEnv = InMemoryEnv()
        val secretHash = "pbkdf2_sha256\$120000\$super_secret_salt\$super_secret_hash"
        val srcUser = srcEnv.users.save(
            User(
                nickname = "Botvinnik_Lab",
                email = "source@example.com",
                passwordHash = secretHash,
                rating = 1640,
                isSoundEnabled = false,
                preferredTheme = "Синяя"
            )
        )
        val srcUid = requireNotNull(srcUser.id)
        srcEnv.session.setThemeMode(AppThemeMode.DARK)
        srcEnv.session.saveChessUsernameHint(srcUid, "botvinnik1948")
        srcEnv.engineSettings.saveSettings(EngineSettings(depth = 18, threads = 4, hashSizeMb = 64))

        CuratedExerciseBank.ensureSeeded(srcEnv.exercises)
        val curatedEx = srcEnv.exercises.findAccessibleByUserId(srcUid).first()

        val savedGame = srcEnv.games.save(
            Game(
                userId = srcUid,
                pgnData = """
                    [Event "Rated Rapid"]
                    [White "Botvinnik_Lab"]
                    [Black "Opponent"]
                    [Result "1-0"]
                    [ECO "D35"]
                    [Opening "Ферзевый гамбит"]
                    [TimeControl "600+5"]

                    1. d4 d5 2. c4 e6 3. Nc3 Nf6 4. cxd5 exd5 1-0
                """.trimIndent(),
                playerColor = ChessColor.WHITE,
                timeControl = "600+5",
                playedAt = fixedNow - 86_400_000L,
                analysisStatus = AnalysisStatus.COMPLETED,
                analyzedAt = fixedNow - 80_000_000L,
                accuracy = 88.5,
                averageEvaluationLoss = 21,
                totalMistakes = 1,
                mistakesCount = 1,
                evaluationsJson = "[20,25,30,28,35,32,40,45]"
            )
        )
        val srcGameId = requireNotNull(savedGame.id)

        srcEnv.moves.saveAll(
            listOf(
                AnalyzedMove(
                    gameId = srcGameId,
                    moveIndex = 0,
                    moveNumber = 1,
                    color = ChessColor.WHITE,
                    quality = MoveQuality.BOOK,
                    san = "d4",
                    evalBefore = 20,
                    evalAfter = 25,
                    evalLoss = 0
                ),
                AnalyzedMove(
                    gameId = srcGameId,
                    moveIndex = 6,
                    moveNumber = 4,
                    color = ChessColor.WHITE,
                    quality = MoveQuality.MISTAKE,
                    san = "cxd5",
                    bestMove = "Bg5",
                    evalBefore = 65,
                    evalAfter = -45,
                    evalLoss = 110,
                    comment = "Упущена активная связка"
                )
            )
        )

        srcEnv.mistakes.saveAll(
            listOf(
                Mistake(
                    gameId = srcGameId,
                    themeId = 2L,
                    moveNumber = 4,
                    color = ChessColor.WHITE,
                    evaluationLoss = 110,
                    mistakeType = MistakeType.MISTAKE,
                    bestMove = "Bg5",
                    userMove = "cxd5",
                    comment = "Связка коня f6 сильнее размена",
                    fenBefore = "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4"
                )
            )
        )

        val personalEx = srcEnv.exercises.saveExercise(
            Exercise(
                fenPosition = "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4",
                solutionMoves = listOf("c1g5"),
                tacticalPattern = TacticalPattern.PIN_ABSOLUTE,
                patternClusterId = "PIN_BISHOP_MEDIUM",
                difficultyRating = 48,
                theme = TacticalTheme.PIN,
                createdFrom = ExerciseSource.MISTAKE,
                sourceGameId = srcGameId,
                sourceMoveNumber = 4,
                createdAt = fixedNow - 70_000_000L,
                timesShown = 1,
                timesSolved = 1,
                successRate = 100
            )
        )

        val taggedMoves = AttemptMetadata.buildTaggedMoves(
            rawUciMoves = curatedEx.solutionMoves,
            hintUsage = HintUsage.NONE,
            solutionRevealed = false,
            firstMoveThinkMs = 6_500L,
            wasImpulsive = false,
            retryCount = 0
        )
        srcEnv.exercises.saveAttempt(
            ExerciseAttempt(
                userId = srcUid,
                exerciseId = requireNotNull(curatedEx.id),
                solved = true,
                timeSpentSeconds = 9,
                movesMade = taggedMoves,
                attemptedAt = fixedNow - 50_000_000L
            )
        )
        srcEnv.exercises.saveAttempt(
            ExerciseAttempt(
                userId = srcUid,
                exerciseId = requireNotNull(personalEx.id),
                solved = true,
                timeSpentSeconds = 14,
                movesMade = listOf("c1g5"),
                attemptedAt = fixedNow - 40_000_000L
            )
        )

        val exportRes = srcEnv.exporter().execute(
            ExportUserBackupUseCase.Input(userId = srcUid, nowMs = fixedNow, zoneId = ZoneOffset.UTC)
        )
        assertTrue(exportRes is ExportUserBackupUseCase.Result.Success)
        val exported = (exportRes as ExportUserBackupUseCase.Result.Success).backup

        // Пароль и хеш пароля гарантированно НЕ попадают в резервную копию
        assertFalse(exported.jsonContent.contains(secretHash))
        assertFalse(exported.jsonContent.contains("passwordHash"))
        assertEquals(1, exported.gamesCount)
        assertEquals(1, exported.analyzedGamesCount)
        assertEquals(1, exported.personalExercisesCount)
        assertEquals(2, exported.attemptsCount)

        // 2. Чистое устройство (новый пользователь с другими автоинкрементными ID)
        val dstEnv = InMemoryEnv(initialGameId = 50L, initialExerciseId = 500L)
        val dstUser = dstEnv.users.save(
            User(
                nickname = "Fresh_Account",
                email = "dest@example.com",
                passwordHash = "dest_hash_untouched",
                rating = 1200
            )
        )
        val dstUid = requireNotNull(dstUser.id)

        val importRes = dstEnv.importer().execute(
            ImportUserBackupUseCase.Input(userId = dstUid, rawJson = exported.jsonContent)
        )
        assertTrue(importRes is ImportUserBackupUseCase.Result.Success)
        val summary = (importRes as ImportUserBackupUseCase.Result.Success).summary

        assertEquals(1, summary.gamesImported)
        assertEquals(0, summary.gamesSkippedDuplicate)
        assertEquals(1, summary.exercisesImported)
        assertEquals(2, summary.attemptsImported)

        // Профиль, тема, настройки движка и рейтинг восстановлены, а хеш пароля не затронут
        assertEquals(1640, summary.updatedUser.rating)
        assertEquals(SkillLevel.fromRating(1640), summary.updatedUser.skillLevel)
        assertEquals("Синяя", summary.updatedUser.preferredTheme)
        assertEquals(false, summary.updatedUser.isSoundEnabled)
        assertEquals("dest_hash_untouched", summary.updatedUser.passwordHash)
        assertEquals(AppThemeMode.DARK, dstEnv.session.themeMode.value)
        assertEquals("botvinnik1948", dstEnv.session.getChessUsernameHint(dstUid))
        assertEquals(18, dstEnv.engineSettings.getSettings().depth)

        // Партия получила новый ID (50), и персональная задача перепривязалась именно к нему
        val restoredGame = dstEnv.games.findByUserId(dstUid).single()
        assertNotEquals(srcGameId, restoredGame.id)
        assertEquals(2, dstEnv.moves.findByGameId(restoredGame.id!!).size)
        assertEquals(1, dstEnv.mistakes.findByGameId(restoredGame.id!!).size)

        val restoredPersonal = dstEnv.exercises.findByUserId(dstUid).single()
        assertEquals(restoredGame.id, restoredPersonal.sourceGameId)
        assertEquals(4, restoredPersonal.sourceMoveNumber)

        // Карточки интервального повторения SM-2 полностью восстановлены
        val dstAttempts = dstEnv.exercises.getAttemptsByUserId(dstUid)
        assertEquals(2, dstAttempts.size)
        val dstExMap = dstEnv.exercises.findAccessibleByUserId(dstUid).associateBy { it.id!! }
        val schedule = SpacedRepetitionScheduler.buildSchedule(dstAttempts, dstExMap)
        assertEquals(2, schedule.size)
        assertNotNull(schedule[restoredPersonal.id])

        // 3. Повторный импорт того же файла на 100% идемпотентен (0 дублей)
        val secondRes = dstEnv.importer().execute(
            ImportUserBackupUseCase.Input(userId = dstUid, rawJson = exported.jsonContent)
        )
        val secondSummary = (secondRes as ImportUserBackupUseCase.Result.Success).summary
        assertEquals(0, secondSummary.gamesImported)
        assertEquals(1, secondSummary.gamesSkippedDuplicate)
        assertEquals(0, secondSummary.exercisesImported)
        assertEquals(0, secondSummary.attemptsImported)
        assertEquals(2, secondSummary.attemptsSkippedDuplicate)
        assertEquals(1, dstEnv.games.findByUserId(dstUid).size)
        assertEquals(2, dstEnv.exercises.getAttemptsByUserId(dstUid).size)
    }

    @Test
    fun `import upgrades existing PENDING game with COMPLETED analysis without duplicating it`() = runBlocking {
        val pgn = """
            [White "Player"]
            [Black "Rival"]
            [Result "1-0"]
            [ECO "C50"]

            1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 1-0
        """.trimIndent()

        // Создаём копию, в которой партия уже разобрана
        val src = InMemoryEnv()
        val u1 = src.users.save(User(nickname = "Hero", email = "a@b.com", passwordHash = "h"))
        val g1 = src.games.save(
            Game(
                userId = u1.id!!,
                pgnData = pgn,
                playerColor = ChessColor.WHITE,
                playedAt = fixedNow,
                analysisStatus = AnalysisStatus.COMPLETED,
                analyzedAt = fixedNow + 1_000L,
                accuracy = 92.4,
                averageEvaluationLoss = 16
            )
        )
        src.moves.saveAll(
            listOf(
                AnalyzedMove(
                    gameId = g1.id!!,
                    moveIndex = 0,
                    moveNumber = 1,
                    color = ChessColor.WHITE,
                    quality = MoveQuality.BOOK,
                    san = "e4",
                    evalBefore = 20,
                    evalAfter = 25,
                    evalLoss = 0
                )
            )
        )
        val backup = (src.exporter().execute(ExportUserBackupUseCase.Input(u1.id!!, fixedNow))
            as ExportUserBackupUseCase.Result.Success).backup

        // На целевом устройстве эта же партия лежит в статусе PENDING
        val dst = InMemoryEnv()
        val u2 = dst.users.save(User(nickname = "Hero", email = "a@b.com", passwordHash = "h"))
        val pending = dst.games.save(
            Game(
                userId = u2.id!!,
                pgnData = pgn,
                playerColor = ChessColor.WHITE,
                playedAt = fixedNow,
                analysisStatus = AnalysisStatus.PENDING
            )
        )

        val res = dst.importer().execute(ImportUserBackupUseCase.Input(u2.id!!, backup.jsonContent))
        val summary = (res as ImportUserBackupUseCase.Result.Success).summary

        assertEquals(0, summary.gamesImported)
        assertEquals(1, summary.gamesAnalysisUpgraded)
        val after = dst.games.findById(pending.id!!)!!
        assertEquals(AnalysisStatus.COMPLETED, after.analysisStatus)
        assertEquals(92.4, after.accuracy!!, 0.01)
        assertEquals(1, dst.moves.findByGameId(pending.id!!).size)
    }

    // ==================== IN-MEMORY ОКРУЖЕНИЕ ====================

    private class InMemoryEnv(
        initialGameId: Long = 1L,
        initialExerciseId: Long = 1L
    ) {
        private var nextUserId = 1L
        private var nextGameId = initialGameId
        private var nextMoveId = 1L
        private var nextMistakeId = 1L
        private var nextExId = initialExerciseId
        private var nextAttemptId = 1L

        private val userMap = LinkedHashMap<Long, User>()
        private val gameMap = LinkedHashMap<Long, Game>()
        private val moveList = ArrayList<AnalyzedMove>()
        private val mistakeList = ArrayList<Mistake>()
        private val exMap = LinkedHashMap<Long, Exercise>()
        private val attemptList = ArrayList<ExerciseAttempt>()
        private val hints = HashMap<Long, String>()
        private var settings = EngineSettings.default()
        private val themeFlow = MutableStateFlow(AppThemeMode.SYSTEM)

        val users = object : UserRepository {
            override suspend fun findById(id: Long): User? = userMap[id]
            override suspend fun findByEmail(email: String): User? =
                userMap.values.firstOrNull { it.email == email }
            override suspend fun findByNickname(nickname: String): User? =
                userMap.values.firstOrNull { it.nickname == nickname }
            override suspend fun save(user: User): User {
                val id = user.id ?: nextUserId++
                return user.copy(id = id).also { userMap[id] = it }
            }
            override suspend fun update(user: User): User {
                userMap[requireNotNull(user.id)] = user
                return user
            }
            override suspend fun deleteById(id: Long) { userMap.remove(id) }
            override suspend fun existsByEmail(email: String): Boolean = findByEmail(email) != null
            override suspend fun existsByNickname(nickname: String): Boolean = findByNickname(nickname) != null
            override suspend fun findAll(): List<User> = userMap.values.toList()
            override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
                userMap.values.filter { it.skillLevel == skillLevel }
            override suspend fun findTopByRating(limit: Int): List<User> =
                userMap.values.sortedByDescending { it.rating }.take(limit)
        }

        val games = object : GameRepository {
            override suspend fun findById(id: Long): Game? = gameMap[id]
            override suspend fun findByUserId(userId: Long): List<Game> =
                gameMap.values.filter { it.userId == userId }
            override suspend fun findByUserIdAndStatus(userId: Long, status: AnalysisStatus): List<Game> =
                findByUserId(userId).filter { it.analysisStatus == status }
            override suspend fun save(game: Game): Game {
                val id = game.id ?: nextGameId++
                return game.copy(id = id).also { gameMap[id] = it }
            }
            override suspend fun update(game: Game): Game {
                gameMap[requireNotNull(game.id)] = game
                return game
            }
            override suspend fun deleteById(id: Long) { gameMap.remove(id) }
        }

        val moves = object : AnalyzedMoveRepository {
            override suspend fun saveAll(moves: List<AnalyzedMove>): List<AnalyzedMove> =
                moves.map { m -> m.copy(id = m.id ?: nextMoveId++).also { moveList.add(it) } }
            override suspend fun findByGameId(gameId: Long): List<AnalyzedMove> =
                moveList.filter { it.gameId == gameId }
            override suspend fun findMistakesByGameId(gameId: Long): List<AnalyzedMove> =
                findByGameId(gameId).filter { it.isMistake() }
            override suspend fun findGoodMovesByGameId(gameId: Long): List<AnalyzedMove> =
                findByGameId(gameId).filter { it.isGoodMove() }
            override suspend fun findByMoveIndex(gameId: Long, moveIndex: Int): AnalyzedMove? =
                findByGameId(gameId).firstOrNull { it.moveIndex == moveIndex }
            override suspend fun deleteByGameId(gameId: Long) {
                moveList.removeAll { it.gameId == gameId }
            }
        }

        val mistakes = object : MistakeRepository {
            override suspend fun findById(id: Long): Mistake? = mistakeList.firstOrNull { it.id == id }
            override suspend fun findByGameId(gameId: Long): List<Mistake> =
                mistakeList.filter { it.gameId == gameId }
            override suspend fun findByUserId(userId: Long): List<Mistake> {
                val gids = gameMap.values.filter { it.userId == userId }.mapNotNull { it.id }.toSet()
                return mistakeList.filter { it.gameId in gids }
            }
            override suspend fun findByGameIds(gameIds: List<Long>): List<Mistake> =
                mistakeList.filter { it.gameId in gameIds }
            override suspend fun save(mistake: Mistake): Mistake =
                mistake.copy(id = mistake.id ?: nextMistakeId++).also { mistakeList.add(it) }
            override suspend fun saveAll(mistakes: List<Mistake>): List<Mistake> = mistakes.map { save(it) }
            override suspend fun update(mistake: Mistake): Mistake = mistake
            override suspend fun deleteById(id: Long) { mistakeList.removeAll { it.id == id } }
            override suspend fun deleteByGameId(gameId: Long) { mistakeList.removeAll { it.gameId == gameId } }
            override suspend fun findByThemeId(themeId: Long): List<Mistake> =
                mistakeList.filter { it.themeId == themeId }
            override suspend fun findByGameIdAndType(gameId: Long, type: MistakeType): List<Mistake> =
                findByGameId(gameId).filter { it.mistakeType == type }
            override suspend fun findByUserIdAndTheme(userId: Long, themeId: Long): List<Mistake> =
                findByUserId(userId).filter { it.themeId == themeId }
            override suspend fun countByGameId(gameId: Long): Int = findByGameId(gameId).size
            override suspend fun countByUserIdAndType(userId: Long, type: MistakeType): Int =
                findByUserId(userId).count { it.mistakeType == type }
            override suspend fun getMostFrequentThemesByUserId(userId: Long, limit: Int): Map<Long, Int> =
                findByUserId(userId).groupingBy { it.themeId }.eachCount()
            override suspend fun getAverageEvaluationLossByUserId(userId: Long): Double? =
                findByUserId(userId).takeIf { it.isNotEmpty() }?.map { it.evaluationLoss }?.average()
        }

        val exercises = object : ExerciseRepository {
            private fun userGameIds(userId: Long): Set<Long> =
                gameMap.values.filter { it.userId == userId }.mapNotNull { it.id }.toSet()

            override suspend fun findById(id: Long): Exercise? = exMap[id]
            override suspend fun findByIds(ids: List<Long>): List<Exercise> = ids.mapNotNull { exMap[it] }
            override suspend fun findSuitableExercise(userId: Long, userRating: Int, excludeId: Long): Exercise? =
                findAccessibleByUserId(userId).firstOrNull { it.id != excludeId }
            override suspend fun findFailedUnresolvedExercise(userId: Long, excludeId: Long): Exercise? = null
            override suspend fun findUserMistakeExercise(userId: Long, excludeId: Long): Exercise? =
                findByUserId(userId).firstOrNull { it.id != excludeId }
            override suspend fun findExerciseByTheme(
                userId: Long,
                theme: TacticalTheme,
                userRating: Int,
                excludeId: Long
            ): Exercise? = findAccessibleByUserId(userId).firstOrNull { it.theme == theme && it.id != excludeId }
            override suspend fun findByTheme(theme: String): List<Exercise> =
                exMap.values.filter { it.theme.name == theme }
            override suspend fun findByPattern(pattern: String): List<Exercise> =
                exMap.values.filter { it.tacticalPattern.name == pattern }
            override suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise? =
                exMap.values.firstOrNull { it.sourceGameId == gameId && it.sourceMoveNumber == moveNumber }
            override suspend fun findByUserId(userId: Long): List<Exercise> {
                val gids = userGameIds(userId)
                return exMap.values.filter { it.sourceGameId != null && it.sourceGameId in gids }
            }
            override suspend fun findAccessibleByUserId(userId: Long): List<Exercise> {
                val gids = userGameIds(userId)
                return exMap.values.filter { it.sourceGameId == null || it.sourceGameId in gids }
            }
            override suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise? =
                findAccessibleByUserId(userId).firstOrNull { it.id != excludeId }
            override suspend fun findGlobalByFen(fen: String): Exercise? =
                exMap.values.firstOrNull { it.sourceGameId == null && it.fenPosition == fen }
            override suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise? =
                findByUserId(userId).firstOrNull { it.fenPosition == fen }
            override suspend fun findByFen(fen: String): Exercise? =
                exMap.values.firstOrNull { it.fenPosition == fen }
            override suspend fun deleteDuplicatesForUser(userId: Long) = Unit
            override suspend fun deleteGlobalDuplicates() = Unit
            override suspend fun deleteBySourceGameId(gameId: Long) {
                exMap.entries.removeIf { it.value.sourceGameId == gameId }
            }
            override suspend fun countExercises(): Int = exMap.size
            override suspend fun countGlobalExercises(): Int = exMap.values.count { it.sourceGameId == null }
            override suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean =
                attemptList.any { it.userId == userId && it.exerciseId == exerciseId && it.solved }
            override suspend fun saveExercise(exercise: Exercise): Exercise {
                val id = exercise.id ?: nextExId++
                return exercise.copy(id = id).also { exMap[id] = it }
            }
            override suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt {
                val id = attempt.id ?: nextAttemptId++
                return attempt.copy(id = id).also { attemptList.add(it) }
            }
            override suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt> =
                attemptList.filter { it.userId == userId }
            override suspend fun findByCluster(
                userId: Long,
                clusterId: String,
                excludeId: Long,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun findSimilarByPattern(
                userId: Long,
                pattern: String,
                excludeId: Long,
                minDifficulty: Int,
                maxDifficulty: Int,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun findForMistakeRepetition(
                userId: Long,
                pattern: String,
                difficulty: Int,
                excludeId: Long,
                limit: Int
            ): List<Exercise> = emptyList()
            override suspend fun incrementTimesShown(exerciseId: Long) = Unit
            override suspend fun incrementTimesSolved(exerciseId: Long) = Unit
        }

        val session = object : SessionRepository {
            override val themeMode: StateFlow<AppThemeMode> = themeFlow
            override fun getActiveUserId(): Long? = null
            override fun saveActiveSession(userId: Long, email: String) = Unit
            override fun clearActiveSession() = Unit
            override fun getLastLoginEmail(): String = ""
            override fun setThemeMode(mode: AppThemeMode) { themeFlow.value = mode }
            override fun getChessUsernameHint(userId: Long): String? = hints[userId]
            override fun saveChessUsernameHint(userId: Long, hint: String) { hints[userId] = hint }
        }

        val engineSettings = object : EngineSettingsRepository {
            override fun getSettings(): EngineSettings = settings
            override fun saveSettings(settings: EngineSettings) { this@InMemoryEnv.settings = settings }
            override fun resetToDefault() { settings = EngineSettings.default() }
            override fun updateDepth(depth: Int) { settings = settings.copy(depth = depth) }
            override fun updateThreads(threads: Int) { settings = settings.copy(threads = threads) }
            override fun updateHashSize(hashSizeMb: Int) { settings = settings.copy(hashSizeMb = hashSizeMb) }
        }

        fun exporter() = ExportUserBackupUseCase(
            userRepository = users,
            gameRepository = games,
            mistakeRepository = mistakes,
            analyzedMoveRepository = moves,
            exerciseRepository = exercises,
            sessionRepository = session,
            engineSettingsRepository = engineSettings
        )

        fun importer() = ImportUserBackupUseCase(
            userRepository = users,
            gameRepository = games,
            mistakeRepository = mistakes,
            analyzedMoveRepository = moves,
            exerciseRepository = exercises,
            sessionRepository = session,
            engineSettingsRepository = engineSettings
        )
    }
}
