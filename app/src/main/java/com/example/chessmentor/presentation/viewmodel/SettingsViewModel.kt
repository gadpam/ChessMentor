package com.example.chessmentor.presentation.viewmodel

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnArchiveExporter
import com.example.chessmentor.domain.session.AppThemeMode
import com.example.chessmentor.domain.usecase.ChangePasswordUseCase
import com.example.chessmentor.presentation.ui.components.BoardThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    var currentUser = mutableStateOf<User?>(null)
        private set

    // Редактируемые параметры профиля
    var tempNickname = mutableStateOf("")
    var isSoundEnabled = mutableStateOf(true)
    var selectedThemeName = mutableStateOf(BoardThemes.Classic.name)

    // Смена пароля (не сохраняется в SavedState)
    var currentPassword = mutableStateOf("")
    var newPassword = mutableStateOf("")
    var confirmPassword = mutableStateOf("")
    var isSavingPassword = mutableStateOf(false)
        private set

    // Сводка данных для карточки резервного копирования
    var totalGamesCount = mutableIntStateOf(0)
        private set
    var analyzedGamesCount = mutableIntStateOf(0)
        private set
    var isExporting = mutableStateOf(false)
        private set

    var message = mutableStateOf<String?>(null)
    private var initializedUserId: Long? = null

    val hasProfileChanges: Boolean
        get() {
            val user = currentUser.value ?: return false
            return tempNickname.value.trim() != user.nickname ||
                isSoundEnabled.value != user.isSoundEnabled ||
                selectedThemeName.value != BoardThemes.getByName(user.preferredTheme).name
        }

    fun init(user: User) {
        val isNewUser = initializedUserId != user.id || currentUser.value == null
        currentUser.value = user
        if (isNewUser) {
            initializedUserId = user.id
            tempNickname.value = user.nickname
            isSoundEnabled.value = user.isSoundEnabled
            selectedThemeName.value = BoardThemes.getByName(user.preferredTheme).name
            clearPasswordFields()
        }
        refreshLibraryStats(user.id)
    }

    private fun refreshLibraryStats(userId: Long?) {
        if (userId == null) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.gameRepository.findByUserId(userId)
                }
            }.onSuccess { games ->
                totalGamesCount.intValue = games.size
                analyzedGamesCount.intValue = games.count { it.isAnalyzed() }
            }
        }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        container.sessionRepository.setThemeMode(mode)
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
                if (!nickname.equals(user.nickname, ignoreCase = true)) {
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
                message.value = "✅ Настройки профиля сохранены"
                onUserUpdated(updatedUser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка сохранения: ${e.message}"
            }
        }
    }

    fun changePassword(
        onUserUpdated: (User) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        val userId = currentUser.value?.id ?: return
        if (isSavingPassword.value) return

        isSavingPassword.value = true
        viewModelScope.launch {
            try {
                val res = container.changePasswordUseCase.execute(
                    ChangePasswordUseCase.Input(
                        userId = userId,
                        currentPassword = currentPassword.value,
                        newPassword = newPassword.value,
                        confirmPassword = confirmPassword.value
                    )
                )
                when (res) {
                    is ChangePasswordUseCase.Result.Success -> {
                        currentUser.value = res.user
                        clearPasswordFields()
                        message.value = "✅ Пароль успешно обновлён"
                        onUserUpdated(res.user)
                        onSuccess()
                    }
                    is ChangePasswordUseCase.Result.Error -> {
                        message.value = "❌ ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Ошибка смены пароля: ${e.message}"
            } finally {
                isSavingPassword.value = false
            }
        }
    }

    fun preparePgnArchive(onReady: (PgnArchiveExporter.ExportArchive) -> Unit) {
        val user = currentUser.value ?: return
        val uid = user.id ?: return
        if (isExporting.value) return

        isExporting.value = true
        viewModelScope.launch {
            try {
                val archive = withContext(Dispatchers.IO) {
                    val games = container.gameRepository.findByUserId(uid)
                    PgnArchiveExporter.buildArchive(
                        games = games,
                        playerNickname = user.nickname
                    )
                }
                if (archive.gamesCount == 0) {
                    message.value = "❌ В библиотеке пока нет партий для экспорта"
                } else {
                    onReady(archive)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "❌ Не удалось подготовить архив: ${e.message}"
            } finally {
                isExporting.value = false
            }
        }
    }

    fun clearPasswordFields() {
        currentPassword.value = ""
        newPassword.value = ""
        confirmPassword.value = ""
    }

    fun logout(onLogout: () -> Unit) {
        initializedUserId = null
        currentUser.value = null
        clearPasswordFields()
        onLogout()
    }

    fun clearMessage() {
        message.value = null
    }
}
