// presentation/ui/screen/SettingsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.chessmentor.presentation.ui.components.SkeletonBox
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnArchiveExporter
import com.example.chessmentor.domain.session.AppThemeMode
import com.example.chessmentor.domain.usecase.ExportUserBackupUseCase
import com.example.chessmentor.presentation.ui.components.BoardTheme
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    user: User,
    container: AppContainer,
    onLogout: () -> Unit,
    onUserUpdated: (User) -> Unit = {},
    onOpenEngineSettings: (() -> Unit)? = null,
    onDataRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val viewModel: SettingsViewModel = viewModel(factory = factory)

    LaunchedEffect(user.id, user.rating, user.nickname, user.preferredTheme, user.isSoundEnabled) {
        viewModel.init(user)
    }

    val tempNickname by viewModel.tempNickname
    val isSoundEnabled by viewModel.isSoundEnabled
    val selectedThemeName by viewModel.selectedThemeName
    val currentPassword by viewModel.currentPassword
    val newPassword by viewModel.newPassword
    val confirmPassword by viewModel.confirmPassword
    val isSavingPassword by viewModel.isSavingPassword
    val totalGames by viewModel.totalGamesCount
    val analyzedGames by viewModel.analyzedGamesCount
    val totalAttempts by viewModel.totalAttemptsCount
    val isExporting by viewModel.isExporting
    val isImportingBackup by viewModel.isImportingBackup
    val message by viewModel.message

    val appThemeMode by container.sessionRepository.themeMode.collectAsState()
    val engineStatus by container.chessEngine.status.collectAsState()
    val engineSettings = remember { container.engineSettingsRepository.getSettings() }

    var showLogoutDialog by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<PgnArchiveExporter.ExportArchive?>(null) }
    var pendingJsonBackup by remember { mutableStateOf<ExportUserBackupUseCase.ExportedBackup?>(null) }

    val jsonBackupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val backup = pendingJsonBackup
        pendingJsonBackup = null
        if (uri == null || backup == null) return@rememberLauncherForActivityResult
        scope.launch {
            val writeResult = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Не удалось открыть файл для записи")
                    stream.use { out ->
                        out.write(backup.jsonContent.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
            writeResult.fold(
                onSuccess = {
                    viewModel.message.value =
                        "Копия сохранена (партий: ${backup.gamesCount}, задач: ${backup.personalExercisesCount}, попыток: ${backup.attemptsCount})"
                },
                onFailure = { err ->
                    viewModel.message.value =
                        "Ошибка записи копии: ${err.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    val jsonBackupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val readResult = withContext(Dispatchers.IO) {
                runCatching {
                    val maxBytes = 16 * 1024 * 1024
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть выбранный файл")
                    stream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                error("Файл превышает допустимый размер (16 МБ)")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                }
            }
            readResult.fold(
                onSuccess = { rawJson ->
                    viewModel.restoreFromJsonBackup(
                        rawJson = rawJson,
                        onUserUpdated = onUserUpdated,
                        onDataRestored = onDataRestored
                    )
                },
                onFailure = { err ->
                    viewModel.message.value =
                        "Ошибка чтения файла: ${err.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-chess-pgn")
    ) { uri: Uri? ->
        val archive = pendingArchive
        pendingArchive = null
        if (uri == null || archive == null) return@rememberLauncherForActivityResult
        scope.launch {
            val writeResult = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Не удалось открыть выбранный файл для записи")
                    stream.use { out ->
                        out.write(archive.content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
            writeResult.fold(
                onSuccess = {
                    viewModel.message.value =
                        "Экспортировано партий: ${archive.gamesCount} (в файл PGN)"
                },
                onFailure = { err ->
                    viewModel.message.value =
                        "Ошибка записи файла: ${err.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (message != null) {
            item {
                SettingsMessage(
                    message = message!!,
                    onDismiss = { viewModel.clearMessage() }
                )
            }
        }

        // Профиль
        item {
            ProfileCard(
                nickname = tempNickname,
                email = user.email,
                onNicknameChange = { viewModel.tempNickname.value = it }
            )
        }

        // Внешний вид, тема доски и звук
        item {
            ParametersCard(
                appThemeMode = appThemeMode,
                isSoundEnabled = isSoundEnabled,
                selectedThemeName = selectedThemeName,
                onAppThemeChange = { viewModel.setAppThemeMode(it) },
                onSoundToggle = { viewModel.isSoundEnabled.value = it },
                onThemeSelect = { viewModel.selectedThemeName.value = it }
            )
        }

        // Сохранение профиля и параметров доски
        item {
            Button(
                onClick = { viewModel.saveSettings(onUserUpdated = onUserUpdated) },
                enabled = viewModel.hasProfileChanges,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Сохранить изменения")
                Spacer(Modifier.width(8.dp))
                Text(if (viewModel.hasProfileChanges) "Сохранить изменения" else "Все изменения сохранены")
            }
        }

        // Быстрый переход к настройкам Stockfish и NNUE
        if (onOpenEngineSettings != null) {
            item {
                EngineShortcutCard(
                    status = engineStatus,
                    depth = engineSettings.depth,
                    threads = engineSettings.threads,
                    hashMb = engineSettings.hashSizeMb,
                    onClick = onOpenEngineSettings
                )
            }
        }

        // Смена пароля
        item {
            ChangePasswordCard(
                currentPassword = currentPassword,
                newPassword = newPassword,
                confirmPassword = confirmPassword,
                isSaving = isSavingPassword,
                onCurrentChange = { viewModel.currentPassword.value = it },
                onNewChange = { viewModel.newPassword.value = it },
                onConfirmChange = { viewModel.confirmPassword.value = it },
                onSubmit = { viewModel.changePassword(onUserUpdated = onUserUpdated) }
            )
        }

        // Полная резервная копия (JSON + SHA-256) и экспорт PGN
        item {
            DataBackupCard(
                totalGames = totalGames,
                analyzedGames = analyzedGames,
                totalAttempts = totalAttempts,
                isExporting = isExporting,
                isImporting = isImportingBackup,
                onExportJsonClick = {
                    viewModel.prepareFullJsonBackup { backup ->
                        pendingJsonBackup = backup
                        jsonBackupExportLauncher.launch(backup.suggestedFileName)
                    }
                },
                onImportJsonClick = {
                    jsonBackupImportLauncher.launch(
                        arrayOf("application/json", "text/plain", "*/*")
                    )
                },
                onExportPgnClick = {
                    viewModel.preparePgnArchive { archive ->
                        pendingArchive = archive
                        exportLauncher.launch(archive.suggestedFileName)
                    }
                }
            )
        }

        // Выход из аккаунта
        item {
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти из аккаунта")
                Spacer(Modifier.width(8.dp))
                Text("Выйти из аккаунта")
            }
        }

        if (showLogoutDialog) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Выйти из аккаунта?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Все ваши партии, разборы и прогресс тренировок останутся сохранены на устройстве и восстановятся при следующем входе.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 4
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = {
                                    showLogoutDialog = false
                                    viewModel.logout(onLogout)
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                modifier = Modifier.width(110.dp)
                            ) {
                                Text("Выйти")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = { showLogoutDialog = false }) {
                                Text("Отмена")
                            }
                        }
                    }
                }
            }
        }

        // Информация о приложении
        item {
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "ChessMentor 1.0 · Stockfish 19 (NNUE) · Локальная БД Room v5",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsMessage(
    message: String,
    onDismiss: () -> Unit
) {
    val isError = message.startsWith("Ошибка")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = if (isError) "Ошибка" else "Успешно",
                    tint = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }
    }
}

@Composable
private fun ProfileCard(
    nickname: String,
    email: String,
    onNicknameChange: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = "Профиль", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Профиль", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 50) onNicknameChange(it) },
                label = { Text("Никнейм (3–50 символов)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Редактировать никнейм") },
                isError = nickname.trim().length < 3
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = "Электронная почта",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametersCard(
    appThemeMode: AppThemeMode,
    isSoundEnabled: Boolean,
    selectedThemeName: String,
    onAppThemeChange: (AppThemeMode) -> Unit,
    onSoundToggle: (Boolean) -> Unit,
    onThemeSelect: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = "Оформление и звук", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Оформление и звук", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Тема приложения",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = appThemeMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onAppThemeChange(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSoundEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = if (isSoundEnabled) "Звук включён" else "Звук выключен"
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Звуковые эффекты ходов")
                }
                Switch(
                    checked = isSoundEnabled,
                    onCheckedChange = onSoundToggle
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "Тема шахматной доски",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BoardThemes.getAll().chunked(2).forEach { rowThemes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            val isSelected = selectedThemeName == theme.name
                            FilterChip(
                                selected = isSelected,
                                onClick = { onThemeSelect(theme.name) },
                                label = {
                                    Text(
                                        text = theme.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    BoardThemeMiniSwatch(theme = theme, isSelected = isSelected)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardThemeMiniSwatch(
    theme: BoardTheme,
    isSelected: Boolean
) {
    Column(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(
                width = if (isSelected) 1.5.dp else 0.75.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(3.dp)
            )
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.lightSquare))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.darkSquare))
        }
        Row(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.darkSquare))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.lightSquare))
        }
    }
}

@Composable
private fun EngineShortcutCard(
    status: EngineStatus,
    depth: Int,
    threads: Int,
    hashMb: Int,
    onClick: () -> Unit
) {
    val badgeColor = when {
        status.isReady -> LocalChessPalette.current.positive
        status.isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Статус движка Stockfish",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Stockfish 19 и нейросеть NNUE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${status.shortBadgeLabel()} · Глубина $depth · $threads пот. · $hashMb МБ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.isError) badgeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status.isReady) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Движок готов",
                    tint = badgeColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Открыть настройки движка",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChangePasswordCard(
    currentPassword: String,
    newPassword: String,
    confirmPassword: String,
    isSaving: Boolean,
    onCurrentChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showCurrentPassword by remember { mutableStateOf(false) }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = "Безопасность и пароль", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Безопасность и пароль", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Свернуть раздел пароля" else "Развернуть раздел пароля"
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = onCurrentChange,
                    label = { Text("Текущий пароль") },
                    singleLine = true,
                    visualTransformation = if (showCurrentPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showCurrentPassword = !showCurrentPassword }) {
                            Icon(
                                imageVector = if (showCurrentPassword) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showCurrentPassword) "Скрыть текущий пароль" else "Показать текущий пароль"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = onNewChange,
                    label = { Text("Новый пароль (от 6 символов)") },
                    singleLine = true,
                    visualTransformation = if (showNewPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    isError = newPassword.isNotEmpty() && newPassword.length < 6,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                // Индикатор силы пароля
                if (newPassword.isNotEmpty()) {
                    val palette = LocalChessPalette.current
                    val strength = when {
                        newPassword.length < 6 -> Triple("Слабый", MaterialTheme.colorScheme.error, 0.33f)
                        newPassword.length < 12 -> Triple("Средний", palette.caution, 0.66f)
                        else -> Triple("Надёжный", palette.positive, 1f)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Надёжность пароля: ${strength.first}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = strength.second
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .background(
                                        strength.second.copy(alpha = 0.2f),
                                        RoundedCornerShape(2.dp)
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(strength.third.coerceIn(0.05f, 1f))
                                        .fillMaxHeight()
                                        .background(strength.second, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val mismatch = confirmPassword.isNotEmpty() && confirmPassword != newPassword
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmChange,
                    label = { Text("Повторите новый пароль") },
                    singleLine = true,
                    visualTransformation = if (showConfirmPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) {
                            Icon(
                                imageVector = if (showConfirmPassword) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showConfirmPassword) "Скрыть подтверждение пароля" else "Показать подтверждение пароля"
                            )
                        }
                    },
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("Пароли не совпадают") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(Modifier.height(12.dp))

                val canSubmit = !isSaving &&
                    currentPassword.isNotEmpty() &&
                    newPassword.length >= 6 &&
                    newPassword == confirmPassword

                FilledTonalButton(
                    onClick = onSubmit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSaving) {
                        SkeletonBox(height = 18.dp, width = 18.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Обновить пароль")
                }
            }
        }
    }
}

@Composable
private fun DataBackupCard(
    totalGames: Int,
    analyzedGames: Int,
    totalAttempts: Int,
    isExporting: Boolean,
    isImporting: Boolean,
    onExportJsonClick: () -> Unit,
    onImportJsonClick: () -> Unit,
    onExportPgnClick: () -> Unit
) {
    val isBusy = isExporting || isImporting

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = "Данные и резервная копия", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Данные и резервная копия", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "Партий: $totalGames (разобрано: $analyzedGames) · Попыток в тренажёре: $totalAttempts. " +
                    "JSON — партии и прогресс. PGN — только партии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = onExportJsonClick,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExporting) {
                    SkeletonBox(height = 18.dp, width = 18.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Скачать резервную копию JSON",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isExporting) "Подготовка копии…" else "Полная резервная копия (.json)")
            }

            OutlinedButton(
                onClick = onImportJsonClick,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImporting) {
                    SkeletonBox(height = 18.dp, width = 18.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "Загрузить резервную копию JSON",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isImporting) "Восстановление…" else "Восстановить из копии (.json)")
            }

            OutlinedButton(
                onClick = onExportPgnClick,
                enabled = totalGames > 0 && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Экспортировать партии в PGN",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Экспортировать все партии (.pgn)")
            }
        }
    }
}
