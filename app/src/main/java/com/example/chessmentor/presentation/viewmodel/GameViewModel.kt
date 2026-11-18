// presentation/viewmodel/GameViewModel.kt
package com.example.chessmentor.presentation.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.domain.usecase.UploadGameUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val container: AppContainer
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
    }

    // ==================== СОСТОЯНИЯ ====================

    var currentUser = mutableStateOf<User?>(null)

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

    private val _analysisProgress = MutableStateFlow<AnalyzeGameUseCase.AnalysisProgress?>(null)
    val analysisProgress: StateFlow<AnalyzeGameUseCase.AnalysisProgress?> =
        _analysisProgress.asStateFlow()

    private var analysisJob: Job? = null
    private var previewJob: Job? = null
    private var lastSourceFileName: String? = null

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Установить текущего пользователя и загрузить его данные
     */
    fun setUser(user: User) {
        val previousId = currentUser.value?.id
        currentUser.value = user
        if (previousId != user.id || chessUsernameHint.value.isBlank()) {
            chessUsernameHint.value = user.nickname
        }
        if (previousId != user.id || userGames.isEmpty()) {
            loadUserData()
        }
    }

    /**
     * Обновить данные текущего пользователя (после сохранения настроек или изменения рейтинга)
     */
    fun updateCurrentUser(user: User) {
        val oldNick = currentUser.value?.nickname
        currentUser.value = user
        if (chessUsernameHint.value.isBlank() || chessUsernameHint.value == oldNick) {
            chessUsernameHint.value = user.nickname
        }
    }

    /**
     * Выход из аккаунта с очисткой сессии
     */
    fun logout() {
        cancelAnalysis()
        previewJob?.cancel()
        currentUser.value = null
        userGames.clear()
        _userMistakes.clear()
        _mistakesByGameId.value = emptyMap()
        closeGameView()
        clearImportForm()
        chessUsernameHint.value = ""
        message.value = ""
        isLoading.value = false
    }

    fun clearImportForm() {
        previewJob?.cancel()
        pgnInput.value = ""
        timeControl.value = ""
        colorAutoDetected.value = false
        importPreview.value = null
        lastSourceFileName = null
    }

    fun selectPlayerColorManually(color: ChessColor) {
        selectedColor.value = color
        colorAutoDetected.value = false
    }

    fun onChessUsernameHintChanged(hint: String) {
        chessUsernameHint.value = hint
        if (pgnInput.value.isNotBlank()) {
            schedulePgnPreview(pgnInput.value, lastSourceFileName, autoApplyFields = true)
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
        if (sourceFileName != null || newPgn.isBlank()) {
            lastSourceFileName = sourceFileName
        }
        if (newPgn.isBlank()) {
            previewJob?.cancel()
            importPreview.value = null
            colorAutoDetected.value = false
            return
        }
        val shouldAutoApply = forceAutoApply || wasBlank || sourceFileName != null
        schedulePgnPreview(newPgn, lastSourceFileName, autoApplyFields = shouldAutoApply)
    }

    private fun schedulePgnPreview(
        rawPgn: String,
        sourceFileName: String?,
        autoApplyFields: Boolean
    ) {
        previewJob?.cancel()
        val userSnapshot = currentUser.value
        val hintSnapshot = chessUsernameHint.value
        val existingSnapshot = userGames.toList()

        previewJob = viewModelScope.launch {
            if (!autoApplyFields) {
                delay(180L)
            }
            val preview = withContext(Dispatchers.Default) {
                buildPreviewInternal(rawPgn, userSnapshot, hintSnapshot, existingSnapshot, sourceFileName)
            }
            importPreview.value = preview

            if (autoApplyFields && preview != null && !preview.isBatch) {
                preview.autoDetectedColor?.let { detected ->
                    selectedColor.value = detected
                    colorAutoDetected.value = true
                }
                val parsedTc = preview.singleParsed?.metadata?.timeControl?.fullLabel
                if (!parsedTc.isNullOrBlank()) {
                    timeControl.value = parsedTc
                }
            }
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
        if (blocks.isEmpty()) return null

        val candidateNames = buildList {
            usernameHint.trim().takeIf { it.isNotEmpty() }?.let(::add)
            user?.nickname?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            user?.email?.substringBefore('@')?.trim()?.takeIf { it.length >= 3 }?.let(::add)
        }

        val existingByFingerprint = HashMap<String, Game>()
        for (g in existingGames) {
            PgnParser.computeFingerprintForStoredPgn(g.pgnData)?.let { fp ->
                existingByFingerprint.putIfAbsent(fp, g)
            }
        }

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
                if (userFromDb != null) {
                    currentUser.value = userFromDb
                }

                val games = container.gameRepository.findByUserId(userId)
                userGames.clear()
                userGames.addAll(games.sortedByDescending { it.playedAt })

                val mistakes = container.mistakeRepository.findByUserId(userId)
                _userMistakes.clear()
                _userMistakes.addAll(mistakes)
                _mistakesByGameId.value = mistakes.groupBy { it.gameId }

                Log.d(
                    TAG,
                    "Loaded ${games.size} games and ${mistakes.size} mistakes for user $userId"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка загрузки данных: ${e.message}"
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
            message.value = "❌ Необходимо войти в систему"
            return
        }

        val pgn = pgnInput.value.trim()
        val color = selectedColor.value
        val time = timeControl.value.trim().ifEmpty { null }
        val hint = chessUsernameHint.value.trim().ifEmpty { null }

        if (pgn.isBlank()) {
            message.value = "❌ Введите или вставьте PGN партии"
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
                            message.value = "❌ Не удалось сохранить партию"
                            isLoading.value = false
                            return@launch
                        }

                        clearImportForm()
                        analyzeGameWithProgress(gameId)
                    }

                    is UploadGameUseCase.Result.Duplicate -> {
                        message.value = "❌ Эта партия уже есть в вашей библиотеке"
                        isLoading.value = false
                    }

                    is UploadGameUseCase.Result.Error -> {
                        message.value = "❌ ${uploadResult.message}"
                        isLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка: ${e.message}"
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
            message.value = "❌ Необходимо войти в систему"
            return
        }

        val pgn = pgnInput.value.trim()
        if (pgn.isBlank()) {
            message.value = "❌ Введите или вставьте PGN партии"
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
                        message.value = "✅ Партия добавлена в библиотеку (можно разобрать в любой момент)"
                        onSavedToLibrary()
                    }
                    is UploadGameUseCase.Result.Duplicate -> {
                        message.value = "❌ Эта партия уже есть в вашей библиотеке"
                    }
                    is UploadGameUseCase.Result.Error -> {
                        message.value = "❌ ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                isLoading.value = false
                message.value = "❌ Ошибка сохранения: ${e.message}"
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
            message.value = "❌ Необходимо войти в систему"
            return
        }

        val rawArchive = pgnInput.value.trim()
        if (rawArchive.isBlank()) {
            message.value = "❌ Вставьте PGN или выберите файл .pgn"
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
                            message.value = "ℹ️ Все партии из файла (${batchRes.skippedDuplicates}) уже есть в библиотеке"
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
                            message.value = "✅ Пакет загружен ($details). Запускаем анализ последней партии..."
                            analyzeGameWithProgress(newestId)
                        } else {
                            isLoading.value = false
                            message.value = "✅ Готово — $details"
                            onImportedToLibrary()
                        }
                    }

                    is UploadGameUseCase.BatchResult.Error -> {
                        isLoading.value = false
                        message.value = "❌ ${batchRes.message}"
                    }
                }
            } catch (e: CancellationException) {
                isLoading.value = false
                throw e
            } catch (e: Exception) {
                isLoading.value = false
                message.value = "❌ Ошибка импорта: ${e.message}"
                Log.e(TAG, "Error importing PGN batch", e)
            }
        }
    }

    /**
     * Анализировать партию С ПРОГРЕССОМ
     */
    fun analyzeGameWithProgress(gameId: Long, forceReanalyze: Boolean = false) {
        if (analysisJob?.isActive == true) {
            message.value = "Дождитесь завершения текущего анализа"
            return
        }

        isLoading.value = true
        analysisJob = viewModelScope.launch {
            try {
                container.analyzeGameUseCase.executeWithProgress(
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
                                        "✅ Точность: ${String.format(java.util.Locale.US, "%.1f", playerAcc)}%$oppAccText (ошибок: $errorsCount)"
                                    } else {
                                        "✅ Анализ завершён! Ошибок: $errorsCount"
                                    }

                                    Log.i(
                                        TAG,
                                        "Analysis completed: ${result.evaluations.size} evaluations saved"
                                    )
                                }

                                is AnalyzeGameUseCase.Result.Error -> {
                                    message.value = "❌ ${result.message}"
                                }
                            }
                        }

                        is AnalyzeGameUseCase.AnalysisProgress.Failed -> {
                            isLoading.value = false
                            loadUserData()
                            message.value = "❌ ${progress.error}"
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
                message.value = "❌ Ошибка анализа: ${e.message}"
                Log.e(TAG, "Analysis flow crashed", e)
            }
        }
    }

    /**
     * Отменить текущий анализ и остановить поиск Stockfish
     */
    fun cancelAnalysis() {
        container.chessEngine.stop()
        analysisJob?.cancel()
        analysisJob = null
        isLoading.value = false
        _analysisProgress.value = null
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
        viewModelScope.launch {
            try {
                container.analyzedMoveRepository.deleteByGameId(gameId)
                container.mistakeRepository.deleteByGameId(gameId)
                container.gameRepository.deleteById(gameId)

                userGames.removeAll { it.id == gameId }
                _userMistakes.removeAll { it.gameId == gameId }
                _mistakesByGameId.value = _userMistakes.groupBy { it.gameId }

                if (selectedGame.value?.id == gameId) {
                    closeGameView()
                }

                message.value = "✅ Партия удалена"
                Log.d(TAG, "Game $gameId deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка удаления: ${e.message}"
                Log.e(TAG, "Error deleting game", e)
            }
        }
    }

    fun clearMessage() {
        message.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        if (analysisJob?.isActive == true) {
            container.chessEngine.stop()
        }
    }
}
