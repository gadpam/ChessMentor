package com.example.chessmentor.presentation.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * ViewModel для экрана статистики
 */
class StatisticsViewModel(
    private val container: AppContainer
) : ViewModel() {

    var statistics = mutableStateOf<GetUserStatisticsUseCase.UserStatistics?>(null)
    var isLoading = mutableStateOf(false)
    var errorMessage = mutableStateOf<String?>(null)

    /**
     * Загрузить статистику для пользователя
     */
    fun loadStatistics(user: User) {
        val userId = user.id
        if (userId == null) {
            errorMessage.value = "Некорректный идентификатор пользователя"
            return
        }

        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null

            try {
                when (val result = container.getUserStatisticsUseCase.execute(
                    GetUserStatisticsUseCase.Input(userId)
                )) {
                    is GetUserStatisticsUseCase.Result.Success -> {
                        statistics.value = result.statistics
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
                isLoading.value = false
            }
        }
    }
}
