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
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.domain.usecase.UploadGameUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    var message = mutableStateOf("")
    var isLoading = mutableStateOf(false)

    private val _analysisProgress = MutableStateFlow<AnalyzeGameUseCase.AnalysisProgress?>(null)
    val analysisProgress: StateFlow<AnalyzeGameUseCase.AnalysisProgress?> =
        _analysisProgress.asStateFlow()

    private var analysisJob: Job? = null

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Установить текущего пользователя и загрузить его данные
     */
    fun setUser(user: User) {
        val previousId = currentUser.value?.id
        currentUser.value = user
        if (previousId != user.id || userGames.isEmpty()) {
            loadUserData()
        }
    }

    /**
     * Обновить данные текущего пользователя (после сохранения настроек или изменения рейтинга)
     */
    fun updateCurrentUser(user: User) {
        currentUser.value = user
    }

    /**
     * Выход из аккаунта с очисткой сессии
     */
    fun logout() {
        cancelAnalysis()
        currentUser.value = null
        userGames.clear()
        _userMistakes.clear()
        _mistakesByGameId.value = emptyMap()
        closeGameView()
        pgnInput.value = ""
        timeControl.value = ""
        message.value = ""
        isLoading.value = false
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
     * Загрузить и анализировать партию С ПРОГРЕССОМ
     */
    fun uploadAndAnalyzeGame() {
        val userId = currentUser.value?.id
        if (userId == null) {
            message.value = "❌ Необходимо войти в систему"
            return
        }

        val pgn = pgnInput.value.trim()
        val color = selectedColor.value
        val time = timeControl.value.trim().ifEmpty { null }

        if (pgn.isBlank()) {
            message.value = "❌ Введите PGN партии"
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
                    playedAt = System.currentTimeMillis()
                )

                when (val uploadResult = container.uploadGameUseCase.execute(input)) {
                    is UploadGameUseCase.Result.Success -> {
                        val gameId = uploadResult.game.id
                        if (gameId == null) {
                            message.value = "❌ Не удалось сохранить партию"
                            isLoading.value = false
                            return@launch
                        }

                        pgnInput.value = ""
                        timeControl.value = ""

                        analyzeGameWithProgress(gameId)
                    }

                    is UploadGameUseCase.Result.Error -> {
                        message.value = "❌ ${uploadResult.message}"
                        isLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка: ${e.message}"
                isLoading.value = false
                Log.e(TAG, "Error uploading game", e)
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

                                    val errorsCount = result.analyzedMoves.count { it.isMistake() }
                                    val goodMovesCount = result.analyzedMoves.count { it.isGoodMove() }
                                    message.value =
                                        "✅ Анализ завершён! Ошибок: $errorsCount, хороших ходов: $goodMovesCount"

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
