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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления партиями
 */
class GameViewModel(
    private val container: AppContainer
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
    }

    // ==================== СОСТОЯНИЯ ====================

    // Текущий пользователь
    var currentUser = mutableStateOf<User?>(null)

    // Список партий пользователя
    val userGames = mutableStateListOf<Game>()

    // Все ошибки пользователя
    private val _userMistakes = mutableStateListOf<Mistake>()
    val userMistakes: List<Mistake> = _userMistakes

    // Текущая партия для просмотра
    var selectedGame = mutableStateOf<Game?>(null)

    // Ошибки в текущей партии (для обратной совместимости)
    val selectedGameMistakes = mutableStateListOf<Mistake>()

    // Анализированные ходы текущей партии
    val selectedGameAnalyzedMoves = mutableStateListOf<AnalyzedMove>()

    // ✅ НОВОЕ: Оценки текущей партии (сохраняются в БД)
    val selectedGameEvaluations = mutableStateListOf<Int>()

    // Поля формы загрузки
    var pgnInput = mutableStateOf("")
    var selectedColor = mutableStateOf(ChessColor.WHITE)
    var timeControl = mutableStateOf("")

    // Сообщения
    var message = mutableStateOf("")
    var isLoading = mutableStateOf(false)

    // Прогресс анализа
    private val _analysisProgress = MutableStateFlow<AnalyzeGameUseCase.AnalysisProgress?>(null)
    val analysisProgress: StateFlow<AnalyzeGameUseCase.AnalysisProgress?> =
        _analysisProgress.asStateFlow()

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    /**
     * Установить текущего пользователя и загрузить его данные
     */
    fun setUser(user: User) {
        currentUser.value = user
        loadUserData()
    }

    /**
     * Загрузить все данные пользователя (партии и ошибки)
     */
    fun loadUserData() {
        val userId = currentUser.value?.id ?: return

        viewModelScope.launch {
            try {
                // Загружаем партии
                val games = container.gameRepository.findByUserId(userId)
                userGames.clear()
                userGames.addAll(games.sortedByDescending { it.playedAt })

                // Загружаем ошибки
                val mistakes = container.mistakeRepository.findByUserId(userId)
                _userMistakes.clear()
                _userMistakes.addAll(mistakes)

                Log.d(
                    TAG,
                    "Loaded ${games.size} games and ${mistakes.size} mistakes for user $userId"
                )
            } catch (e: Exception) {
                message.value = "❌ Ошибка загрузки данных: ${e.message}"
                Log.e(TAG, "Error loading user data", e)
            }
        }
    }

    /**
     * Получить ошибки для конкретной игры
     */
    fun getMistakesForGame(gameId: Long): List<Mistake> {
        return _userMistakes.filter { it.gameId == gameId }
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
                // Загрузка партии
                val input = UploadGameUseCase.Input(
                    userId = userId,
                    pgnData = pgn,
                    playerColor = color,
                    timeControl = time,
                    playedAt = System.currentTimeMillis()
                )

                when (val uploadResult = container.uploadGameUseCase.execute(input)) {
                    is UploadGameUseCase.Result.Success -> {
                        val gameId = uploadResult.game.id!!

                        // Очищаем форму
                        pgnInput.value = ""
                        timeControl.value = ""

                        // Запускаем анализ с прогрессом
                        analyzeGameWithProgress(gameId)
                    }

                    is UploadGameUseCase.Result.Error -> {
                        message.value = "❌ ${uploadResult.message}"
                        isLoading.value = false
                    }
                }

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
    fun analyzeGameWithProgress(gameId: Long) {
        viewModelScope.launch {
            container.analyzeGameUseCase.executeWithProgress(
                AnalyzeGameUseCase.Input(gameId)
            ).collect { progress: AnalyzeGameUseCase.AnalysisProgress ->
                _analysisProgress.value = progress

                when (progress) {
                    is AnalyzeGameUseCase.AnalysisProgress.Starting -> {
                        Log.d(TAG, "Analysis starting for game $gameId")
                    }

                    is AnalyzeGameUseCase.AnalysisProgress.InProgress -> {
                        // Прогресс обновляется автоматически
                    }

                    is AnalyzeGameUseCase.AnalysisProgress.Completed -> {
                        isLoading.value = false
                        when (val result = progress.result) {
                            is AnalyzeGameUseCase.Result.Success -> {
                                // Обновляем данные
                                loadUserData()

                                // ✅ ОБНОВЛЕНО: Выбираем партию со всеми данными
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
                        message.value = "❌ ${progress.error}"
                        Log.e(TAG, "Analysis failed: ${progress.error}")
                    }
                }
            }
        }
    }

    /**
     * Загрузить партию на анализ (старый метод, без прогресса)
     */
    fun uploadGame() {
        val userId = currentUser.value?.id
        if (userId == null) {
            message.value = "❌ Необходимо войти в систему"
            return
        }

        if (pgnInput.value.isBlank()) {
            message.value = "❌ Введите PGN партии"
            return
        }

        isLoading.value = true

        viewModelScope.launch {
            try {
                val input = UploadGameUseCase.Input(
                    userId = userId,
                    pgnData = pgnInput.value,
                    playerColor = selectedColor.value,
                    timeControl = timeControl.value.ifBlank { null },
                    playedAt = System.currentTimeMillis()
                )

                when (val result = container.uploadGameUseCase.execute(input)) {
                    is UploadGameUseCase.Result.Success -> {
                        message.value = "✅ Партия загружена! Запускаем анализ..."

                        pgnInput.value = ""
                        timeControl.value = ""

                        analyzeGame(result.game.id!!)
                    }

                    is UploadGameUseCase.Result.Error -> {
                        message.value = "❌ Ошибка: ${result.message}"
                        isLoading.value = false
                    }
                }
            } catch (e: Exception) {
                message.value = "❌ Ошибка: ${e.message}"
                isLoading.value = false
            }
        }
    }

    /**
     * Анализировать партию (старый метод, без прогресса)
     */
    fun analyzeGame(gameId: Long) {
        viewModelScope.launch {
            try {
                val input = AnalyzeGameUseCase.Input(gameId = gameId)

                when (val result = container.analyzeGameUseCase.execute(input)) {
                    is AnalyzeGameUseCase.Result.Success -> {
                        val errorsCount = result.analyzedMoves.count { it.isMistake() }
                        val goodMovesCount = result.analyzedMoves.count { it.isGoodMove() }
                        message.value =
                            "✅ Анализ завершён! Ошибок: $errorsCount, хороших ходов: $goodMovesCount"
                        isLoading.value = false

                        loadUserData()

                        selectGameWithData(
                            game = result.game,
                            mistakes = result.mistakes,
                            analyzedMoves = result.analyzedMoves,
                            evaluations = result.evaluations
                        )
                    }

                    is AnalyzeGameUseCase.Result.Error -> {
                        message.value = "❌ Ошибка анализа: ${result.message}"
                        isLoading.value = false
                    }
                }
            } catch (e: Exception) {
                message.value = "❌ Ошибка анализа: ${e.message}"
                isLoading.value = false
            }
        }
    }

    /**
     * Сброс прогресса анализа
     */
    fun clearAnalysisProgress() {
        _analysisProgress.value = null
    }

    /**
     * Выбрать партию для просмотра (из списка игр)
     * ✅ ОБНОВЛЕНО: Загружает evaluations из Game.evaluationsJson
     */
    /**
     * Выбрать партию для просмотра (из списка игр)
     * ✅ ОБНОВЛЕНО: Загружает evaluations из Game.evaluationsJson
     */
    fun selectGame(game: Game) {
        viewModelScope.launch {
            selectedGame.value = game
            selectedGameMistakes.clear()
            selectedGameAnalyzedMoves.clear()
            selectedGameEvaluations.clear()

            if (game.id != null) {
                try {
                    // Загрузка ошибок
                    val mistakes = getMistakesForGame(game.id)
                    selectedGameMistakes.clear()
                    selectedGameMistakes.addAll(mistakes.sortedBy { it.moveNumber })

                    // ✅ ИСПРАВЛЕНО: Загружаем analyzedMoves сразу при выборе игры
                    val analyzedMoves = container.analyzedMoveRepository.findByGameId(game.id)
                    selectedGameAnalyzedMoves.clear()
                    selectedGameAnalyzedMoves.addAll(analyzedMoves.sortedBy { it.moveIndex })

                    // ✅ Загрузка evaluations из Game
                    val evaluations = game.getEvaluations()
                    selectedGameEvaluations.clear()
                    selectedGameEvaluations.addAll(evaluations)

                    Log.d(
                        TAG, "Selected game ${game.id}: " +
                                "${mistakes.size} mistakes, " +
                                "${analyzedMoves.size} analyzed moves, " +
                                "${evaluations.size} evaluations (hasEvaluations=${game.hasEvaluations()})"
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Error loading game data", e)
                }
            }
        }
    }

    /**
     * Выбрать партию с полными данными (после анализа)
     */
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

        // ✅ НОВОЕ: Сохраняем evaluations
        selectedGameEvaluations.clear()
        selectedGameEvaluations.addAll(evaluations)

        Log.d(
            TAG, "Selected game with data: ${game.id}, " +
                    "${mistakes.size} mistakes, ${analyzedMoves.size} moves, ${evaluations.size} evals"
        )
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Получить evaluations для текущей партии
     */
    fun getEvaluationsForCurrentGame(): List<Int> {
        return selectedGameEvaluations.toList()
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Проверить, есть ли сохранённые evaluations
     */
    fun hasEvaluations(): Boolean {
        return selectedGameEvaluations.isNotEmpty()
    }

    /**
     * Закрыть просмотр партии
     */
    fun closeGameView() {
        selectedGame.value = null
        selectedGameMistakes.clear()
        selectedGameAnalyzedMoves.clear()
        selectedGameEvaluations.clear()  // ✅ Очищаем evaluations
    }

    /**
     * Удалить партию
     */
    fun deleteGame(gameId: Long) {
        viewModelScope.launch {
            try {
                // Удаляем analyzedMoves из БД
                container.analyzedMoveRepository.deleteByGameId(gameId)

                // Удаляем ошибки из БД
                container.mistakeRepository.deleteByGameId(gameId)

                // Удаляем игру из БД (CASCADE должен сработать, но на всякий случай)
                container.gameRepository.deleteById(gameId)

                // Удаляем из локальных списков
                userGames.removeAll { it.id == gameId }
                _userMistakes.removeAll { it.gameId == gameId }

                // Если удаляем текущую выбранную игру
                if (selectedGame.value?.id == gameId) {
                    closeGameView()
                }

                message.value = "✅ Партия удалена"
                Log.d(TAG, "Game $gameId deleted")
            } catch (e: Exception) {
                message.value = "❌ Ошибка удаления: ${e.message}"
                Log.e(TAG, "Error deleting game", e)
            }
        }
    }

    /**
     * Очистить сообщение
     */
    fun clearMessage() {
        message.value = ""
    }

}
