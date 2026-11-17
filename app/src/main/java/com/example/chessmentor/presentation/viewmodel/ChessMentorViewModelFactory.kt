// presentation/viewmodel/ChessMentorViewModelFactory.kt
package com.example.chessmentor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.chessmentor.di.AppContainer

/**
 * Единая фабрика ViewModel для сохранения состояния при повороте экрана и смене конфигурации.
 */
class ChessMentorViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(GameViewModel::class.java) ->
                GameViewModel(container) as T
            modelClass.isAssignableFrom(BoardViewModel::class.java) ->
                BoardViewModel() as T
            modelClass.isAssignableFrom(TrainingViewModel::class.java) ->
                TrainingViewModel(container) as T
            modelClass.isAssignableFrom(StatisticsViewModel::class.java) ->
                StatisticsViewModel(container) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(container) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
