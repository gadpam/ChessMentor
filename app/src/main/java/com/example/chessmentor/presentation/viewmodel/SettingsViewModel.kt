package com.example.chessmentor.presentation.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.BoardThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    var currentUser = mutableStateOf<User?>(null)

    // Состояния полей для редактирования
    var tempNickname = mutableStateOf("")
    var isSoundEnabled = mutableStateOf(true)
    var selectedThemeName = mutableStateOf(BoardThemes.Classic.name)

    var message = mutableStateOf<String?>(null)
    private var initializedUserId: Long? = null

    fun init(user: User) {
        if (initializedUserId != user.id || currentUser.value == null) {
            initializedUserId = user.id
            currentUser.value = user
            tempNickname.value = user.nickname
            isSoundEnabled.value = user.isSoundEnabled
            selectedThemeName.value = BoardThemes.getByName(user.preferredTheme).name
        }
    }

    fun saveSettings(onUserUpdated: (User) -> Unit = {}) {
        val user = currentUser.value ?: return
        val nickname = tempNickname.value.trim()

        if (nickname.length < 3) {
            message.value = "❌ Никнейм должен содержать не менее 3 символов"
            return
        }
        if (nickname.length > 50) {
            message.value = "❌ Никнейм должен содержать не более 50 символов"
            return
        }

        viewModelScope.launch {
            try {
                // Проверяем уникальность никнейма, если он изменился
                if (nickname != user.nickname) {
                    val existing = container.userRepository.findByNickname(nickname)
                    if (existing != null && existing.id != user.id) {
                        message.value = "❌ Этот никнейм уже занят"
                        return@launch
                    }
                }

                val updatedUser = user.copy(
                    nickname = nickname,
                    isSoundEnabled = isSoundEnabled.value,
                    preferredTheme = selectedThemeName.value
                )

                container.userRepository.update(updatedUser)

                tempNickname.value = nickname
                currentUser.value = updatedUser
                message.value = "✅ Настройки сохранены"
                onUserUpdated(updatedUser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка сохранения: ${e.message}"
            }
        }
    }

    fun logout(onLogout: () -> Unit) {
        initializedUserId = null
        currentUser.value = null
        onLogout()
    }

    fun clearMessage() {
        message.value = null
    }
}
