package com.example.chessmentor.presentation.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.statistics.PlayerAnalyticsCalculator.StatsPeriod
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StatisticsSection(val title: String) {
    OVERVIEW("Обзор"),
    OPENINGS("Дебюты"),
    TACTICS("Тактика")
}

/**
 * ViewModel для экрана аналитики и дебютного репертуара.
 */
class StatisticsViewModel(
    private val container: AppContainer
) : ViewModel() {

    var statistics = mutableStateOf<GetUserStatisticsUseCase.UserStatistics?>(null)
        private set

    var selectedSection = mutableStateOf(StatisticsSection.OVERVIEW)
    var selectedPeriod = mutableStateOf(StatsPeriod.ALL)
        private set
    var selectedRepertoireColor = mutableStateOf(ChessColor.WHITE)

    var isLoading = mutableStateOf(false)
        private set
    var errorMessage = mutableStateOf<String?>(null)
        private set

    private var activeUser: User? = null
    private var loadJob: Job? = null

    /** Загрузить статистику для пользователя по выбранному [period]. */
    fun loadStatistics(user: User, period: StatsPeriod = selectedPeriod.value) {
        loadJob?.cancel()
        activeUser = user
        selectedPeriod.value = period
        if (statistics.value?.userId != user.id) statistics.value = null
        val userId = user.id
        if (userId == null) {
            isLoading.value = false
            errorMessage.value = "Некорректный идентификатор пользователя"
            return
        }
        isLoading.value = true
        errorMessage.value = null
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    container.getUserStatisticsUseCase.execute(
                        GetUserStatisticsUseCase.Input(
                            userId = userId,
                            period = period
                        )
                    )
                }
                when (result) {
                    is GetUserStatisticsUseCase.Result.Success -> {
                        val loaded = result.statistics
                        statistics.value = loaded
                        // Если за белых пока нет партий, а за чёрных есть — сразу показываем чёрный репертуар
                        val rep = loaded.gameAnalytics.repertoire
                        if (rep.whiteOpenings.isEmpty() && rep.blackOpenings.isNotEmpty()) {
                            selectedRepertoireColor.value = ChessColor.BLACK
                        }
                    }
                    is GetUserStatisticsUseCase.Result.Error -> {
                        errorMessage.value = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "Ошибка загрузки статистики"
            } finally {
                if (isActive) isLoading.value = false
            }
        }
    }

    /** Переключить временной срез (Всё время / 30 дней / 7 дней). */
    fun selectPeriod(period: StatsPeriod) {
        if (selectedPeriod.value == period && (isLoading.value ||
                (statistics.value?.period == period && errorMessage.value == null))) return
        val user = activeUser ?: return
        loadStatistics(user, period)
    }

    /** Принудительно обновить все показатели. */
    fun refresh() {
        val user = activeUser ?: return
        loadStatistics(user, selectedPeriod.value)
    }
}
