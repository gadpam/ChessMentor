package com.example.chessmentor.domain.session

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnArchiveExporter
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher
import com.example.chessmentor.domain.usecase.ChangePasswordUseCase
import com.example.chessmentor.domain.usecase.LoginUserUseCase
import com.example.chessmentor.domain.usecase.RegisterUserUseCase
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuthAndBackupTest {

    private class CaseInsensitiveFakeUserRepo : UserRepository {
        val users = linkedMapOf<Long, User>()
        private var nextId = 1L

        override suspend fun findById(id: Long): User? = users[id]
        override suspend fun findByEmail(email: String): User? =
            users.values.firstOrNull { it.email.equals(email, ignoreCase = true) }
        override suspend fun findByNickname(nickname: String): User? =
            users.values.firstOrNull { it.nickname.equals(nickname, ignoreCase = true) }
        override suspend fun save(user: User): User {
            val id = user.id ?: nextId++
            return user.copy(id = id).also { users[id] = it }
        }
        override suspend fun update(user: User): User {
            val id = requireNotNull(user.id)
            users[id] = user
            return user
        }
        override suspend fun deleteById(id: Long) {
            users.remove(id)
        }
        override suspend fun existsByEmail(email: String): Boolean = findByEmail(email) != null
        override suspend fun existsByNickname(nickname: String): Boolean = findByNickname(nickname) != null
        override suspend fun findAll(): List<User> = users.values.toList()
        override suspend fun findBySkillLevel(skillLevel: SkillLevel): List<User> =
            users.values.filter { it.skillLevel == skillLevel }
        override suspend fun findTopByRating(limit: Int): List<User> =
            users.values.sortedByDescending { it.rating }.take(limit)
    }

    @Test
    fun appThemeMode_storageRoundTrip_defaultsSafelyToSystem() {
        for (mode in AppThemeMode.entries) {
            assertEquals(mode, AppThemeMode.fromStorageKey(mode.storageKey))
            assertEquals(mode, AppThemeMode.fromStorageKey(mode.storageKey.uppercase(Locale.ROOT)))
        }
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey("unknown_mode"))
    }

    @Test
    fun registerLoginAndChangePassword_normalizeEmailVerifyPbkdf2AndUpgradeLegacy() = runBlocking {
        val repo = CaseInsensitiveFakeUserRepo()
        val register = RegisterUserUseCase(repo)
        val login = LoginUserUseCase(repo)
        val changePassword = ChangePasswordUseCase(repo)

        // 1. Регистрация с заглавными буквами в email сохраняет нормализованный адрес
        val regRes = register.execute(
            RegisterUserUseCase.Input(
                nickname = "KasparovFan",
                email = "  Alice.Player@Example.COM ",
                password = "secret123",
                rating = 1500
            )
        )
        assertTrue(regRes is RegisterUserUseCase.Result.Success)
        val created = (regRes as RegisterUserUseCase.Result.Success).user
        assertEquals("alice.player@example.com", created.email)

        // Повторная регистрация того же email в другом регистре отклоняется
        val dup = register.execute(
            RegisterUserUseCase.Input("OtherNick", "ALICE.PLAYER@example.com", "abcdef", 1200)
        )
        assertTrue(dup is RegisterUserUseCase.Result.Error)

        // 2. Вход с авто-капитализацией клавиатуры проходит штатно
        val loginOk = login.execute(
            LoginUserUseCase.Input(email = "Alice.Player@example.com", password = "secret123")
        )
        assertTrue(loginOk is LoginUserUseCase.Result.Success)

        // 3. Смена пароля отклоняет неверный текущий, короткий, несовпадающий и совпадающий пароли
        assertTrue(
            changePassword.execute(
                ChangePasswordUseCase.Input(created.id!!, "wrong", "newpass1", "newpass1")
            ) is ChangePasswordUseCase.Result.Error
        )
        assertTrue(
            changePassword.execute(
                ChangePasswordUseCase.Input(created.id!!, "secret123", "12345", "12345")
            ) is ChangePasswordUseCase.Result.Error
        )
        assertTrue(
            changePassword.execute(
                ChangePasswordUseCase.Input(created.id!!, "secret123", "newpass1", "newpass2")
            ) is ChangePasswordUseCase.Result.Error
        )
        assertTrue(
            changePassword.execute(
                ChangePasswordUseCase.Input(created.id!!, "secret123", "secret123", "secret123")
            ) is ChangePasswordUseCase.Result.Error
        )

        // 4. Валидная смена пароля перехеширует в PBKDF2, старый пароль перестаёт подходить
        val changed = changePassword.execute(
            ChangePasswordUseCase.Input(created.id!!, "secret123", "strongNew99!", "strongNew99!")
        )
        assertTrue(changed is ChangePasswordUseCase.Result.Success)
        assertTrue(login.execute(LoginUserUseCase.Input(created.email, "secret123")) is LoginUserUseCase.Result.Error)
        assertTrue(login.execute(LoginUserUseCase.Input(created.email, "strongNew99!")) is LoginUserUseCase.Result.Success)

        // 5. Миграция устаревшего хеша `hashed_...` при смене пароля
        val legacyUser = repo.save(
            User(
                nickname = "LegacyUser",
                email = "legacy@example.com",
                passwordHash = "hashed_oldpass",
                rating = 1200
            )
        )
        val upgraded = changePassword.execute(
            ChangePasswordUseCase.Input(legacyUser.id!!, "oldpass", "modernPass7", "modernPass7")
        ) as ChangePasswordUseCase.Result.Success
        assertFalse(PasswordHasher.isLegacyHash(upgraded.user.passwordHash))
        assertTrue(PasswordHasher.verify("modernPass7", upgraded.user.passwordHash))
    }

    @Test
    fun pgnArchiveExporter_producesRoundTrippableMultiGameArchiveAndCleanFilename() {
        val fixedNow = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()
        val games = listOf(
            // Партия без заголовков и без финального токена результата
            Game(
                id = 1L,
                userId = 10L,
                pgnData = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6",
                playerColor = ChessColor.WHITE,
                timeControl = "3+2",
                playedAt = fixedNow - 86_400_000L,
                analysisStatus = AnalysisStatus.PENDING
            ),
            // Полноценная проанализированная партия с заголовками
            Game(
                id = 2L,
                userId = 10L,
                pgnData = """
                    [Event "Rated Blitz"]
                    [Date "2026.11.17"]
                    [White "Opponent"]
                    [Black "Tal_Fan"]
                    [Result "0-1"]

                    1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 0-1
                """.trimIndent(),
                playerColor = ChessColor.BLACK,
                timeControl = "5+0",
                playedAt = fixedNow,
                analysisStatus = AnalysisStatus.COMPLETED,
                accuracy = 88.5
            )
        )

        val archive = PgnArchiveExporter.buildArchive(
            games = games,
            playerNickname = "Tal Fan!",
            nowMs = fixedNow,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(2, archive.gamesCount)
        assertEquals(1, archive.analyzedCount)
        assertEquals("chessmentor_tal_fan_2026-11-17.pgn", archive.suggestedFileName)

        // Архив обязан без потерь читаться обратно нашим мульти-PGN парсером
        val reparsed = PgnParser.splitGames(archive.content)
            .map { PgnParser.parseAndValidate(it) }
        assertEquals(2, reparsed.size)
        assertTrue(reparsed.all { it is PgnParser.ParseResult.Success })

        // Первая партия в архиве — самая свежая (Сицилианская за чёрных)
        val newest = (reparsed[0] as PgnParser.ParseResult.Success).game
        assertEquals("Tal_Fan", newest.metadata.blackName)
        assertEquals("0-1", newest.metadata.result.token)

        // Вторая партия получила корректные заголовки [White], [TimeControl] и завершающий `*`
        val older = (reparsed[1] as PgnParser.ParseResult.Success).game
        assertEquals("Tal Fan!", older.metadata.whiteName)
        assertEquals("3+2", older.metadata.timeControl?.shortLabel)
        assertEquals(6, older.sanMoves.size)
    }
}
