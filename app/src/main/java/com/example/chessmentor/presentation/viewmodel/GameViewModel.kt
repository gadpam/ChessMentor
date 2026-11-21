// presentation/viewmodel/GameViewModel.kt
package com.example.chessmentor.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.data.chesscom.ArchiveGame
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.session.SessionState
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.domain.usecase.UploadGameUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Живой предпросмотр вставленного PGN или выбранного `.pgn`-файла.
 */
data class PgnImportPreview(
    val totalGames: Int,
    val validGamesCount: Int,
    val singleParsed: PgnParser.ParsedPgnGame? = null,
    val singleError: String? = null,
    val duplicateExistingGame: Game? = null,
    val autoDetectedColor: ChessColor? = null,
    val batchSampleLines: List<String> = emptyList(),
    val batchAutoColorCount: Int = 0,
    val batchDuplicatesCount: Int = 0,
    val truncatedCount: Int = 0,
    val sourceFileName: String? = null
) {
    val isBatch: Boolean get() = totalGames > 1
}

/**
 * ViewModel для управления партиями и текущей пользовательской сессией
 */
class GameViewModel(
    private val container: AppContainer,
    private val analyzeGame: AnalyzeGameUseCase = container.analyzeGameUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"

    }

    // ==================== СОСТОЯНИЯ ====================

    var sessionState = mutableStateOf<SessionState>(SessionState.Restoring)
        private set

    var currentUser = mutableStateOf<User?>(null)

    var lastLoginEmail = mutableStateOf(container.sessionRepository.getLastLoginEmail())
        private set

    val userGames = mutableStateListOf<Game>()

    private val _userMistakes = mutableStateListOf<Mistake>()
    val userMistakes: List<Mistake> = _userMistakes

    // Кэш ошибок по gameId для O(1) доступа в списке партий (без N+1 фильтраций)
    private val _mistakesByGameId = mutableStateOf<Map<Long, List<Mistake>>>(emptyMap())
    val mistakesByGameId: Map<Long, List<Mistake>> get() = _mistakesByGameId.value

    var selectedGame = mutableStateOf<Game?>(null)
    val selectedGameMistakes = mutableStateListOf<Mistake>()
    val selectedGameAnalyzedMoves = mutableStateListOf<AnalyzedMove>()
    val selectedGameEvaluations = mutableStateListOf<Int>()

    var pgnInput = mutableStateOf("")
    var selectedColor = mutableStateOf(ChessColor.WHITE)
    var timeControl = mutableStateOf("")
    var chessUsernameHint = mutableStateOf("")
    var colorAutoDetected = mutableStateOf(false)
    var importPreview = mutableStateOf<PgnImportPreview?>(null)

    var message = mutableStateOf("")
    var isLoading = mutableStateOf(false)

    /**
     * Партия, разбор которой инициирован из списка партий: там нужен переход в сводку.
     *
     * Хранится во ViewModel, а не в `rememberSaveable` экрана: после пересоздания
     * Activity прогресс по-прежнему привязан к нужной карточке.
     */
    var libraryAnalysisRequestedGameId = mutableStateOf<Long?>(null)

    private val _analysisProgress = MutableStateFlow<AnalyzeGameUseCase.AnalysisProgress?>(null)
    val analysisProgress: StateFlow<AnalyzeGameUseCase.AnalysisProgress?> =
        _analysisProgress.asStateFlow()

    private var analysisJob: Job? = null
    private var activeAnalysisGameId: Long? = null
    private var libraryJob: Job? = null
    private var previewJob: Job? = null
    private var manualTimeControl = false
    private var lastSourceFileName: String? = null
    private var sessionRestoreStarted = false

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Восстанавливает сохранённую пользовательскую сессию при холодном перезапуске приложения
     * и запускает разовую проверку согласованности БД (сброс прерванных `IN_PROGRESS` партий
     * и очистка осиротевших персональных задач).
     */
    fun restoreSessionIfNeeded() {
        if (sessionRestoreStarted) return
        sessionRestoreStarted = true

        // Если пользователь уже успел войти вручную, просто отмечаем сессию активной
        currentUser.value?.let { existing ->
            sessionState.value = SessionState.Authenticated(existing)
            return
        }

        val savedUserId = container.sessionRepository.getActiveUserId()
        lastLoginEmail.value = container.sessionRepository.getLastLoginEmail()

        sessionState.value = SessionState.Restoring
        viewModelScope.launch {
            try {
                container.databaseMaintenance.runStartupChecksOnce()
                if (savedUserId == null) {
                    val tester = if (lastLoginEmail.value.isBlank()) container.testerProfiles.open(onlyIfEmpty = true) else null
                    if (tester == null) sessionState.value = SessionState.Unauthenticated else setUser(tester)
                    return@launch
                }
                val restoredUser = withContext(Dispatchers.IO) {
                    container.userRepository.findById(savedUserId)
                }
                if (restoredUser != null) {
                    setUser(restoredUser, persistSession = true)
                    Log.i(TAG, "Restored active session for user id=${restoredUser.id}")
                } else {
                    Log.w(TAG, "Saved session user id=$savedUserId not found in DB, clearing")
                    container.sessionRepository.clearActiveSession()
                    sessionState.value = SessionState.Unauthenticated
                }
            } catch (e: CancellationException) {
                sessionRestoreStarted = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore session", e)
                sessionState.value = SessionState.Unauthenticated
            }
        }
    }

    /**
     * Установить текущего пользователя, сохранить сессию и загрузить его данные.
     */
    fun setUser(user: User, persistSession: Boolean = true) {
        val previousId = currentUser.value?.id
        currentUser.value = user
        sessionState.value = SessionState.Authenticated(user)
        lastLoginEmail.value = user.email

        val uid = user.id
        if (persistSession && uid != null) {
            container.sessionRepository.saveActiveSession(uid, user.email)
        }

        if (previousId != uid || chessUsernameHint.value.isBlank()) {
            val savedHint = uid?.let { container.sessionRepository.getChessUsernameHint(it) }
            chessUsernameHint.value = savedHint ?: user.nickname
        }
        if (previousId != uid || libraryJob?.isActive != true) {
            libraryJob?.cancel()
            userGames.clear()
            if (uid != null) libraryJob = viewModelScope.launch {
                container.observeUserGames(uid).catch { e ->
                    message.value = "Ошибка загрузки партий: ${e.message}"
                }.collect { games ->
                    userGames.clear()
                    userGames.addAll(games)
                }
            }
        }
        if (previousId != uid || userGames.isEmpty()) {
            loadUserData()
        }
    }

    /**
     * Обновить данные текущего пользователя (после сохранения настроек или изменения рейтинга)
     */
    fun updateCurrentUser(user: User) {
        val oldNick = currentUser.value?.nickname
        currentUser.value = user
        sessionState.value = SessionState.Authenticated(user)
        lastLoginEmail.value = user.email
        user.id?.let { uid ->
            container.sessionRepository.saveActiveSession(uid, user.email)
            if (chessUsernameHint.value.isBlank() || chessUsernameHint.value == oldNick) {
                chessUsernameHint.value = user.nickname
                container.sessionRepository.saveChessUsernameHint(uid, user.nickname)
            }
        }
    }

    /**
     * Выход из аккаунта с полной очисткой активной сессии в памяти и `SharedPreferences`.
     */
    fun logout() {
        cancelAnalysis()
        previewJob?.cancel()
        libraryJob?.cancel()
        container.sessionRepository.clearActiveSession()
        lastLoginEmail.value = container.sessionRepository.getLastLoginEmail()
        currentUser.value = null
        sessionState.value = SessionState.Unauthenticated
        userGames.clear()
        _userMistakes.clear()
        _mistakesByGameId.value = emptyMap()
        closeGameView()
        clearImportForm()
        chessUsernameHint.value = ""
        message.value = ""
        isLoading.value = false
    }

    internal fun savedChessComSource(userId: Long) = container.sessionRepository.getChessComSource(userId)

    internal fun setChessComSource(userId: Long, name: String?) {
        if (currentUser.value?.id == userId) container.sessionRepository.setChessComSource(userId, name)
    }

    internal suspend fun archiveMonths(name: String) = container.chessComArchive.months(name)

    internal suspend fun archiveGames(userId: Long, name: String, period: String): List<ArchiveGame> {
        val known = withContext(Dispatchers.IO) {
            indexSavedGames(container.gameRepository.findByUserId(userId)).mapValues { it.value.id!! }
        }
        return container.chessComArchive.latest(name, period, known)
    }

    internal fun importChessComGames(userId: Long, selection: List<ArchiveGame>) {
        if (isLoading.value || currentUser.value?.id != userId || selection.isEmpty()) return
        isLoading.value = true
        message.value = "Сохраняем выбранные партии…"
        viewModelScope.launch {
            var saved = 0
            var duplicates = 0
            var invalid = 0
            try {
                // Per-side batches use the archive identity, never the unrelated local nickname.
                for ((side, games) in selection.groupBy { it.side }) {
                    val result = withContext(Dispatchers.IO) {
                        container.uploadGameUseCase.executeBatch(UploadGameUseCase.BatchInput(
                            userId = userId, rawPgnArchive = games.joinToString("\n\n") { it.pgn },
                            fallbackColor = side, autoDetectColor = false
                        ))
                    }
                    when (result) {
                        is UploadGameUseCase.BatchResult.Success -> {
                            saved += result.importedGames.size
                            duplicates += result.skippedDuplicates
                            invalid += result.failedCount
                        }
                        is UploadGameUseCase.BatchResult.Error -> error(result.message)
                    }
                }
                message.value = "Chess.com → библиотека: добавлено $saved, повторов $duplicates, ошибок $invalid."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка импорта Chess.com: ${e.localizedMessage}"
            } finally {
                isLoading.value = false
                if (currentUser.value?.id == userId) loadUserData()
            }
        }
    }

    fun clearImportForm() {
        previewJob?.cancel()
        pgnInput.value = ""
        selectedColor.value = ChessColor.WHITE
        manualTimeControl = false
        timeControl.value = ""
        colorAutoDetected.value = false
        importPreview.value = null
        lastSourceFileName = null
    }

    fun setImportTimeControl(value: String) {
        manualTimeControl = true
        timeControl.value = value
    }

    fun selectPlayerColorManually(color: ChessColor) {
        selectedColor.value = color
        colorAutoDetected.value = false
    }

    fun onChessUsernameHintChanged(hint: String) {
        chessUsernameHint.value = hint
        currentUser.value?.id?.let { uid ->
            container.sessionRepository.saveChessUsernameHint(uid, hint)
        }
        if (pgnInput.value.isNotBlank()) {
            schedulePgnPreview(pgnInput.value, lastSourceFileName, debounce = true)
        }
    }

    /**
     * Обновляет введённый PGN и асинхронно запускает предпросмотр, валидацию,
     * автоопределение цвета и проверку на дубликат.
     */
    fun onPgnInputChanged(
        newPgn: String,
        sourceFileName: String? = null,
        forceAutoApply: Boolean = false
    ) {
        val wasBlank = pgnInput.value.isBlank()
        pgnInput.value = newPgn
        lastSourceFileName = sourceFileName
        manualTimeControl = false
        timeControl.value = ""
        if (newPgn.isBlank()) {
            clearImportForm()
            return
        }
        schedulePgnPreview(newPgn, sourceFileName, debounce = !wasBlank && !forceAutoApply && sourceFileName == null)
    }

    private fun schedulePgnPreview(
        rawPgn: String,
        sourceFileName: String?,
        debounce: Boolean
    ) {
        previewJob?.cancel()
        importPreview.value = null
        selectedColor.value = ChessColor.WHITE
        colorAutoDetected.value = false
        val userSnapshot = currentUser.value
        val hintSnapshot = chessUsernameHint.value
        val existingSnapshot = userGames.toList()

        previewJob = viewModelScope.launch {
            if (debounce) {
                delay(180L)
            }
            val preview = withContext(Dispatchers.Default) {
                buildPreviewInternal(rawPgn, userSnapshot, hintSnapshot, existingSnapshot, sourceFileName)
            }
            if (preview != null && !preview.isBatch) {
                preview.autoDetectedColor?.let { detected ->
                    selectedColor.value = detected
                    colorAutoDetected.value = true
                }
                if (!manualTimeControl) {
                    timeControl.value = preview.singleParsed?.metadata?.timeControl?.fullLabel.orEmpty()
                }
            }
            importPreview.value = preview
        }
    }

    // If multiple copies exist, link to the earliest remaining local record.
    private fun indexSavedGames(games: List<Game>): Map<String, Game> = buildMap {
        for (game in games) {
            val id = game.id ?: continue
            val fp = PgnParser.computeFingerprintForStoredPgn(game.pgnData) ?: continue
            val previous = get(fp)?.id
            if (previous == null || id < previous) put(fp, game)
        }
    }

    private fun buildPreviewInternal(
        rawPgn: String,
        user: User?,
        usernameHint: String,
        existingGames: List<Game>,
        sourceFileName: String?
    ): PgnImportPreview? {
        val trimmed = rawPgn.trim()
        if (trimmed.isEmpty()) return null

        val blocks = PgnParser.splitGames(trimmed)
        if (blocks.isEmpty()) return PgnImportPreview(0, 0, singleError = "В тексте не найдена партия")

        val candidateNames = buildList {
            usernameHint.trim().takeIf { it.isNotEmpty() }?.let(::add)
            user?.nickname?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            user?.email?.substringBefore('@')?.trim()?.takeIf { it.length >= 3 }?.let(::add)
        }

        val existingByFingerprint = indexSavedGames(existingGames)

        if (blocks.size == 1) {
            return when (val res = PgnParser.parseAndValidate(blocks[0])) {
                is PgnParser.ParseResult.Success -> {
                    val parsed = res.game
                    val autoColor = PgnParser.detectPlayerColor(parsed.metadata, candidateNames)
                    val dup = existingByFingerprint[parsed.fingerprint]
                    PgnImportPreview(
                        totalGames = 1,
                        validGamesCount = 1,
                        singleParsed = parsed,
                        autoDetectedColor = autoColor,
                        duplicateExistingGame = dup,
                        sourceFileName = sourceFileName
                    )
                }
                is PgnParser.ParseResult.Failure -> {
                    PgnImportPreview(
                        totalGames = 1,
                        validGamesCount = 0,
                        singleError = res.errorMessage,
                        sourceFileName = sourceFileName
                    )
                }
            }
        }

        // Мульти-PGN архив
        val truncated = (blocks.size - UploadGameUseCase.MAX_BATCH_GAMES).coerceAtLeast(0)
        val limited = blocks.take(UploadGameUseCase.MAX_BATCH_GAMES)
        val seenFp = HashSet(existingByFingerprint.keys)
        var validCount = 0
        var dupCount = 0
        var autoColorCount = 0
        val samples = mutableListOf<String>()

        for (block in limited) {
            val res = PgnParser.parseAndValidate(block)
            if (res is PgnParser.ParseResult.Success) {
                val g = res.game
                if (!seenFp.add(g.fingerprint)) {
                    dupCount++
                    continue
                }
                validCount++
                if (PgnParser.detectPlayerColor(g.metadata, candidateNames) != null) {
                    autoColorCount++
                }
                if (samples.size < 4) {
                    val w = g.metadata.whiteName ?: "Белые"
                    val b = g.metadata.blackName ?: "Чёрные"
                    val resSym = g.metadata.result.displaySymbol()
                    val ecoPart = g.effectiveEco?.let { " · $it" }.orEmpty()
                    samples.add("$w – $b ($resSym$ecoPart)")
                }
            }
        }

        return PgnImportPreview(
            totalGames = blocks.size,
            validGamesCount = validCount,
            batchSampleLines = samples,
            batchAutoColorCount = autoColorCount,
            batchDuplicatesCount = dupCount,
            truncatedCount = truncated,
            sourceFileName = sourceFileName
        )
    }

    /**
     * Загрузить все данные пользователя (партии и ошибки)
     */
    fun loadUserData() {
        val userId = currentUser.value?.id ?: return

        viewModelScope.launch {
            try {
                val userFromDb = container.userRepository.findById(userId)
                if (currentUser.value?.id != userId) return@launch
                if (userFromDb != null) {
                    currentUser.value = userFromDb
                }

                val mistakes = container.mistakeRepository.findByUserId(userId)
                if (currentUser.value?.id != userId) return@launch
                _userMistakes.clear()
                _userMistakes.addAll(mistakes)
                _mistakesByGameId.value = mistakes.groupBy { it.gameId }

                Log.d(
                    TAG,
                    "Loaded ${mistakes.size} mistakes for user $userId"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка загрузки данных: ${e.message}"
                Log.e(TAG, "Error loading user data", e)
            }
        }
    }

    /**
     * Получить ошибки для конкретной игры (O(1) по индексированной карте)
     */
    fun getMistakesForGame(gameId: Long): List<Mistake> {
        return _mistakesByGameId.value[gameId].orEmpty()
    }

    /**
     * Загрузить одну партию и сразу запустить анализ Stockfish с прогрессом.
     */
    fun uploadAndAnalyzeGame(allowDuplicate: Boolean = false) {
        val userId = currentUser.value?.id
        if (userId == null) {
            message.value = "Ошибка: Необходимо войти в систему"
            return
        }

        val pgn = pgnInput.value.trim()
        val color = selectedColor.value
        val time = timeControl.value.trim().ifEmpty { null }
        val hint = chessUsernameHint.value.trim().ifEmpty { null }

        if (pgn.isBlank()) {
            message.value = "Ошибка: Введите или вставьте PGN партии"
            return
        }

        isLoading.value = true
        message.value = ""

        viewModelScope.launch {
            try {
                val input = UploadGameUseCase.Input(
                    userId = userId,
                    pgnData = pgn,
                    playerColor = color,
                    timeControl = time,
                    playedAt = System.currentTimeMillis(),
                    playerNameHint = hint,
                    allowDuplicate = allowDuplicate
                )

                val uploadResult = withContext(Dispatchers.IO) {
                    container.uploadGameUseCase.execute(input)
                }

                when (uploadResult) {
                    is UploadGameUseCase.Result.Success -> {
                        val gameId = uploadResult.game.id
                        if (gameId == null) {
                            message.value = "Ошибка: Не удалось сохранить партию"
                            isLoading.value = false
                            return@launch
                        }

                        clearImportForm()
                        analyzeGameWithProgress(gameId)
                    }

                    is UploadGameUseCase.Result.Duplicate -> {
                        message.value = "Ошибка: Эта партия уже есть в вашей библиотеке"
                        isLoading.value = false
                    }

                    is UploadGameUseCase.Result.Error -> {
                        message.value = "Ошибка: ${uploadResult.message}"
                        isLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка: ${e.message}"
                isLoading.value = false
                Log.e(TAG, "Error uploading game", e)
            }
        }
    }

    /**
     * Сохранить одну партию в библиотеку без немедленного запуска анализа.
     */
    fun saveGameWithoutAnalysis(
        allowDuplicate: Boolean = false,
        onSavedToLibrary: () -> Unit = {}
    ) {
        val userId = currentUser.value?.id
        if (userId == null) {
            message.value = "Ошибка: Необходимо войти в систему"
            return
        }

        val pgn = pgnInput.value.trim()
        if (pgn.isBlank()) {
            message.value = "Ошибка: Введите или вставьте PGN партии"
            return
        }

        val color = selectedColor.value
        val time = timeControl.value.trim().ifEmpty { null }
        val hint = chessUsernameHint.value.trim().ifEmpty { null }

        isLoading.value = true
        message.value = ""

        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    container.uploadGameUseCase.execute(
                        UploadGameUseCase.Input(
                            userId = userId,
                            pgnData = pgn,
                            playerColor = color,
                            timeControl = time,
                            playedAt = System.currentTimeMillis(),
                            playerNameHint = hint,
                            allowDuplicate = allowDuplicate
                        )
                    )
                }
                isLoading.value = false
                when (res) {
                    is UploadGameUseCase.Result.Success -> {
                        clearImportForm()
                        loadUserData()
                        message.value = "Партия добавлена в библиотеку (можно разобрать в любой момент)"
                        onSavedToLibrary()
                    }
                    is UploadGameUseCase.Result.Duplicate -> {
                        message.value = "Ошибка: Эта партия уже есть в вашей библиотеке"
                    }
                    is UploadGameUseCase.Result.Error -> {
                        message.value = "Ошибка: ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                isLoading.value = false
                message.value = "Ошибка сохранения: ${e.message}"
                Log.e(TAG, "Error saving game without analysis", e)
            }
        }
    }

    /**
     * Пакетный импорт мульти-PGN файла или текста в библиотеку пользователя.
     */
    fun importBatchGames(
        analyzeNewest: Boolean = false,
        onImportedToLibrary: () -> Unit = {}
    ) {
        val userId = currentUser.value?.id
        if (userId == null) {
            message.value = "Ошибка: Необходимо войти в систему"
            return
        }

        val rawArchive = pgnInput.value.trim()
        if (rawArchive.isBlank()) {
            message.value = "Ошибка: Вставьте PGN или выберите файл .pgn"
            return
        }

        val fallbackColor = selectedColor.value
        val manualTc = timeControl.value.trim().ifEmpty { null }
        val hint = chessUsernameHint.value.trim().ifEmpty { null }

        isLoading.value = true
        message.value = ""

        viewModelScope.launch {
            try {
                val batchRes = withContext(Dispatchers.IO) {
                    container.uploadGameUseCase.executeBatch(
                        UploadGameUseCase.BatchInput(
                            userId = userId,
                            rawPgnArchive = rawArchive,
                            fallbackColor = fallbackColor,
                            manualTimeControl = manualTc,
                            playerNameHint = hint,
                            autoDetectColor = true,
                            skipDuplicates = true
                        )
                    )
                }

                when (batchRes) {
                    is UploadGameUseCase.BatchResult.Success -> {
                        val importedCount = batchRes.importedGames.size
                        if (importedCount == 0) {
                            isLoading.value = false
                            message.value = "Все партии из файла (${batchRes.skippedDuplicates}) уже есть в библиотеке"
                            return@launch
                        }

                        clearImportForm()
                        loadUserData()

                        val details = buildList {
                            add("импортировано: $importedCount")
                            if (batchRes.skippedDuplicates > 0) {
                                add("дубликатов пропущено: ${batchRes.skippedDuplicates}")
                            }
                            if (batchRes.failedCount > 0) {
                                add("с ошибками: ${batchRes.failedCount}")
                            }
                            if (batchRes.truncatedCount > 0) {
                                add("лимит ${UploadGameUseCase.MAX_BATCH_GAMES} за раз")
                            }
                        }.joinToString(" · ")

                        val newestId = batchRes.importedGames
                            .maxByOrNull { it.playedAt }
                            ?.id

                        if (analyzeNewest && newestId != null) {
                            message.value = "Пакет загружен ($details). Запускаем анализ последней партии..."
                            analyzeGameWithProgress(newestId)
                        } else {
                            isLoading.value = false
                            message.value = "Готово — $details"
                            onImportedToLibrary()
                        }
                    }

                    is UploadGameUseCase.BatchResult.Error -> {
                        isLoading.value = false
                        message.value = "Ошибка: ${batchRes.message}"
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                isLoading.value = false
                message.value = "Ошибка импорта: ${e.message}"
                Log.e(TAG, "Error importing PGN batch", e)
            }
        }
    }

    /**
     * Анализировать партию С ПРОГРЕССОМ
     */
    fun analyzeGameWithProgress(
        gameId: Long,
        forceReanalyze: Boolean = false,
        requestedByLibrary: Boolean = false
    ): Boolean {
        if (analysisJob?.isCompleted == false && activeAnalysisGameId == gameId) {
            // После пересоздания Activity тот же запрос (например, из списка партий)
            // не должен ни падать, ни запускать второй разбор: подписываемся заново.
            Log.i(TAG, "Re-attaching UI to running analysis of game $gameId")
            return true
        }
        if (analysisJob?.isCompleted == false) {
            message.value = "Дождитесь завершения текущего анализа"
            return false
        }

        libraryAnalysisRequestedGameId.value = if (requestedByLibrary) gameId else null
        isLoading.value = true
        _analysisProgress.value = AnalyzeGameUseCase.AnalysisProgress.Starting
        activeAnalysisGameId = gameId
        analysisJob = viewModelScope.launch {
            try {
                analyzeGame.executeWithProgress(
                    AnalyzeGameUseCase.Input(gameId = gameId, forceReanalyze = forceReanalyze)
                ).collect { progress ->
                    _analysisProgress.value = progress

                    when (progress) {
                        is AnalyzeGameUseCase.AnalysisProgress.Starting -> {
                            Log.d(TAG, "Analysis starting for game $gameId")
                        }

                        is AnalyzeGameUseCase.AnalysisProgress.InProgress -> {
                            // Прогресс обновляется реактивно через StateFlow
                        }

                        is AnalyzeGameUseCase.AnalysisProgress.Completed -> {
                            isLoading.value = false
                            when (val result = progress.result) {
                                is AnalyzeGameUseCase.Result.Success -> {
                                    loadUserData()

                                    selectGameWithData(
                                        game = result.game,
                                        mistakes = result.mistakes,
                                        analyzedMoves = result.analyzedMoves,
                                        evaluations = result.evaluations
                                    )

                                    val pColor = result.game.playerColor
                                    val playerMoves = result.analyzedMoves.filter { it.color == pColor }
                                    val opponentMoves = result.analyzedMoves.filter { it.color == pColor.opposite() }
                                    val errorsCount = playerMoves.count { it.isMistake() }
                                    val playerAcc = result.game.accuracy
                                    val oppAccText = if (opponentMoves.isNotEmpty()) {
                                        val oppAcc = com.example.chessmentor.domain.analysis.GameMoveClassifier
                                            .calculateGameAccuracy(result.analyzedMoves, pColor.opposite())
                                        " · соперник: ${String.format(java.util.Locale.US, "%.1f", oppAcc)}%"
                                    } else {
                                        ""
                                    }
                                    message.value = if (playerAcc != null) {
                                        "Точность: ${String.format(java.util.Locale.US, "%.1f", playerAcc)}%$oppAccText (ошибок: $errorsCount)"
                                    } else {
                                        "Анализ завершён! Ошибок: $errorsCount"
                                    }

                                    Log.i(
                                        TAG,
                                        "Analysis completed: ${result.evaluations.size} evaluations saved"
                                    )
                                }

                                is AnalyzeGameUseCase.Result.Error -> {
                                    message.value = "Ошибка: ${result.message}"
                                }
                            }
                        }

                        is AnalyzeGameUseCase.AnalysisProgress.Failed -> {
                            isLoading.value = false
                            loadUserData()
                            message.value = "Ошибка: ${progress.error}"
                            Log.e(TAG, "Analysis failed: ${progress.error}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                _analysisProgress.value = null
                loadUserData()
                throw e
            } catch (e: Exception) {
                isLoading.value = false
                _analysisProgress.value = AnalyzeGameUseCase.AnalysisProgress.Failed(
                    e.message ?: "Неизвестная ошибка"
                )
                message.value = "Ошибка анализа: ${e.message}"
                Log.e(TAG, "Analysis flow crashed", e)
            } finally {
                if (activeAnalysisGameId == gameId) {
                    activeAnalysisGameId = null
                }
            }
        }
        return true
    }

    /**
     * Отменить текущий анализ и остановить поиск Stockfish
     */
    fun cancelAnalysis() {
        container.chessEngine.stop()
        analysisJob?.cancel()
        libraryAnalysisRequestedGameId.value = null
        activeAnalysisGameId = null
        isLoading.value = false
        _analysisProgress.value = null
    }

    /**
     * Повторить загрузку/инициализацию Stockfish и его нейросети NNUE в `viewModelScope`,
     * чтобы процесс не прерывался при повороте экрана или смене вкладки.
     */
    fun retryEngineInit(forceRedownload: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.chessEngine.retryInit(forceRedownload = forceRedownload)
            } catch (e: CancellationException) {
                Log.i(TAG, "Engine retry cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Engine retry failed", e)
            }
        }
    }

    /**
     * Запустить полную проверку SHA-256 установленного файла NNUE.
     */
    fun verifyEngineNetwork() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.chessEngine.verifyNetwork()
            } catch (e: CancellationException) {
                Log.i(TAG, "NNUE verification cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "NNUE verification failed", e)
            }
        }
    }

    /** Приостановить активную загрузку нейросети NNUE. */
    fun cancelEngineInit() {
        container.chessEngine.cancelInit()
    }

    /**
     * Загрузить партию на анализ (делегирует в прогрессивный анализ)
     */
    fun uploadGame() {
        uploadAndAnalyzeGame()
    }

    /**
     * Анализировать партию
     */
    fun analyzeGame(gameId: Long) {
        analyzeGameWithProgress(gameId)
    }

    /**
     * Сброс прогресса анализа
     */
    fun clearAnalysisProgress() {
        _analysisProgress.value = null
    }

    /**
     * Выбрать партию для просмотра (из списка игр)
     */
    fun selectGame(game: Game) {
        viewModelScope.launch {
            selectedGame.value = game
            selectedGameMistakes.clear()
            selectedGameAnalyzedMoves.clear()
            selectedGameEvaluations.clear()

            val gameId = game.id ?: return@launch
            try {
                val mistakes = container.mistakeRepository.findByGameId(gameId)
                selectedGameMistakes.clear()
                selectedGameMistakes.addAll(mistakes.sortedBy { it.moveNumber })

                val analyzedMoves = container.analyzedMoveRepository.findByGameId(gameId)
                selectedGameAnalyzedMoves.clear()
                selectedGameAnalyzedMoves.addAll(analyzedMoves.sortedBy { it.moveIndex })

                val evaluations = game.getEvaluations()
                selectedGameEvaluations.clear()
                selectedGameEvaluations.addAll(evaluations)

                Log.d(
                    TAG,
                    "Selected game $gameId: ${mistakes.size} mistakes, " +
                        "${analyzedMoves.size} analyzed moves, ${evaluations.size} evaluations"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading game data", e)
            }
        }
    }

    private fun selectGameWithData(
        game: Game,
        mistakes: List<Mistake>,
        analyzedMoves: List<AnalyzedMove>,
        evaluations: List<Int>
    ) {
        selectedGame.value = game

        selectedGameMistakes.clear()
        selectedGameMistakes.addAll(mistakes.sortedBy { it.moveNumber })

        selectedGameAnalyzedMoves.clear()
        selectedGameAnalyzedMoves.addAll(analyzedMoves.sortedBy { it.moveIndex })

        selectedGameEvaluations.clear()
        selectedGameEvaluations.addAll(evaluations)
    }

    fun getEvaluationsForCurrentGame(): List<Int> = selectedGameEvaluations.toList()

    fun hasEvaluations(): Boolean = selectedGameEvaluations.isNotEmpty()

    fun closeGameView() {
        selectedGame.value = null
        selectedGameMistakes.clear()
        selectedGameAnalyzedMoves.clear()
        selectedGameEvaluations.clear()
    }

    fun deleteGame(gameId: Long) {
        if (activeAnalysisGameId == gameId) {
            cancelAnalysis()
        }
        viewModelScope.launch {
            try {
                container.exerciseRepository.deleteBySourceGameId(gameId)
                container.analyzedMoveRepository.deleteByGameId(gameId)
                container.mistakeRepository.deleteByGameId(gameId)
                container.gameRepository.deleteById(gameId)

                _userMistakes.removeAll { it.gameId == gameId }
                _mistakesByGameId.value = _userMistakes.groupBy { it.gameId }

                if (selectedGame.value?.id == gameId) {
                    closeGameView()
                }

                message.value = "Партия удалена"
                Log.d(TAG, "Game $gameId and dependent data deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка удаления: ${e.message}"
                Log.e(TAG, "Error deleting game", e)
            }
        }
    }

    fun clearMessage() {
        message.value = ""
    }

    /** Сбросить признак «разбор запущен из списка партий» после перехода в сводку. */
    fun forgetLibraryAnalysisRequest() {
        libraryAnalysisRequestedGameId.value = null
    }
}
