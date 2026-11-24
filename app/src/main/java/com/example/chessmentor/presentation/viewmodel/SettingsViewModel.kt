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
import com.example.chessmentor.domain.usecase.ExportUserBackupUseCase
import com.example.chessmentor.domain.usecase.ImportUserBackupUseCase
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
    var totalAttemptsCount = mutableIntStateOf(0)
        private set
    var isExporting = mutableStateOf(false)
        private set
    var isImportingBackup = mutableStateOf(false)
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
                    val games = container.gameRepository.findByUserId(userId)
                    val attempts = container.exerciseRepository.getAttemptsByUserId(userId).size
                    games to attempts
                }
            }.onSuccess { (games, attempts) ->
                totalGamesCount.intValue = games.size
                analyzedGamesCount.intValue = games.count { it.isAnalyzed() }
                totalAttemptsCount.intValue = attempts
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
            message.value = "Ошибка: Никнейм должен содержать не менее 3 символов"
            return
        }
        if (nickname.length > 50) {
            message.value = "Ошибка: Никнейм должен содержать не более 50 символов"
            return
        }

        viewModelScope.launch {
            try {
                if (!nickname.equals(user.nickname, ignoreCase = true)) {
                    val existing = container.userRepository.findByNickname(nickname)
                    if (existing != null && existing.id != user.id) {
                        message.value = "Ошибка: Этот никнейм уже занят"
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
                message.value = "Настройки профиля сохранены"
                onUserUpdated(updatedUser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка сохранения: ${e.message}"
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
                        message.value = "Пароль успешно обновлён"
                        onUserUpdated(res.user)
                        onSuccess()
                    }
                    is ChangePasswordUseCase.Result.Error -> {
                        message.value = "Ошибка: ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка смены пароля: ${e.message}"
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
                    message.value = "Ошибка: В библиотеке пока нет партий для экспорта"
                } else {
                    onReady(archive)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка: Не удалось подготовить архив: ${e.message}"
            } finally {
                isExporting.value = false
            }
        }
    }

    fun prepareFullJsonBackup(onReady: (ExportUserBackupUseCase.ExportedBackup) -> Unit) {
        val uid = currentUser.value?.id ?: return
        if (isExporting.value || isImportingBackup.value) return

        isExporting.value = true
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    container.exportUserBackupUseCase.execute(
                        ExportUserBackupUseCase.Input(userId = uid)
                    )
                }
                when (res) {
                    is ExportUserBackupUseCase.Result.Success -> onReady(res.backup)
                    is ExportUserBackupUseCase.Result.Error -> {
                        message.value = "Ошибка: ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка создания копии: ${e.message}"
            } finally {
                isExporting.value = false
            }
        }
    }

    fun restoreFromJsonBackup(
        rawJson: String,
        onUserUpdated: (User) -> Unit = {},
        onDataRestored: () -> Unit = {}
    ) {
        val uid = currentUser.value?.id ?: return
        if (isImportingBackup.value || isExporting.value) return

        isImportingBackup.value = true
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    container.importUserBackupUseCase.execute(
                        ImportUserBackupUseCase.Input(userId = uid, rawJson = rawJson)
                    )
                }
                when (res) {
                    is ImportUserBackupUseCase.Result.Success -> {
                        val s = res.summary
                        val updated = s.updatedUser
                        currentUser.value = updated
                        isSoundEnabled.value = updated.isSoundEnabled
                        selectedThemeName.value = BoardThemes.getByName(updated.preferredTheme).name
                        refreshLibraryStats(uid)
                        onUserUpdated(updated)
                        onDataRestored()

                        message.value = when {
                            s.totalChanges == 0 && s.preferencesUpdated -> {
                                "Применены настройки профиля и оформления; все партии и попытки уже присутствуют на устройстве"
                            }
                            s.totalChanges == 0 -> {
                                "Копия проверена: все данные уже присутствуют на устройстве (без дублей)"
                            }
                            else -> {
                                buildString {
                                    append("Восстановлено: ")
                                    val parts = buildList {
                                        if (s.gamesImported > 0) add("партий ${s.gamesImported}")
                                        if (s.gamesAnalysisUpgraded > 0) add("разборов ${s.gamesAnalysisUpgraded}")
                                        if (s.exercisesImported > 0) add("задач ${s.exercisesImported}")
                                        if (s.attemptsImported > 0) add("попыток ${s.attemptsImported}")
                                        if (s.preferencesUpdated) add("настройки профиля")
                                    }
                                    append(parts.joinToString(", "))
                                }
                            }
                        }
                    }
                    is ImportUserBackupUseCase.Result.Error -> {
                        message.value = "Ошибка: ${res.message}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Ошибка восстановления: ${e.message}"
            } finally {
                isImportingBackup.value = false
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
