// presentation/ui/screen/SettingsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    onOpenEngineSettings: (() -> Unit)? = null
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
    val isExporting by viewModel.isExporting
    val message by viewModel.message

    val appThemeMode by container.sessionRepository.themeMode.collectAsState()
    val engineStatus by container.chessEngine.status.collectAsState()
    val engineSettings = remember { container.engineSettingsRepository.getSettings() }

    var showLogoutDialog by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<PgnArchiveExporter.ExportArchive?>(null) }

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
                        "✅ Экспортировано партий: ${archive.gamesCount} (в файл PGN)"
                },
                onFailure = { err ->
                    viewModel.message.value =
                        "❌ Ошибка записи файла: ${err.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Выйти из аккаунта?") },
            text = {
                Text(
                    "Все ваши партии, разборы и прогресс тренировок останутся сохранены на устройстве и восстановятся при следующем входе."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout(onLogout)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Выйти")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Отмена")
                }
            }
        )
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
                rating = user.rating,
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
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
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

        // Резервная копия PGN-библиотеки
        item {
            DataBackupCard(
                totalGames = totalGames,
                analyzedGames = analyzedGames,
                isExporting = isExporting,
                onExportClick = {
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
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Выйти из аккаунта")
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
    val isError = message.startsWith("❌") || message.startsWith("Ошибка")
    val cleanMessage = message.replace("✅ ", "").replace("❌ ", "")

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
                    contentDescription = null,
                    tint = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = cleanMessage,
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
    rating: Int,
    onNicknameChange: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Профиль", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Рейтинг $rating",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 50) onNicknameChange(it) },
                label = { Text("Никнейм (3–50 символов)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                isError = nickname.trim().length < 3
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
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
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = appThemeMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onAppThemeChange(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSoundEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = null
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
        status.isReady -> Color(0xFF4CAF50)
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
                contentDescription = null,
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
                    contentDescription = null,
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
    var showPasswords by remember { mutableStateOf(false) }

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
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Безопасность и пароль", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                val transformation = if (showPasswords) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                }

                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = onCurrentChange,
                    label = { Text("Текущий пароль") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPasswords = !showPasswords }) {
                            Icon(
                                imageVector = if (showPasswords) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = "Показать или скрыть пароль"
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
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    isError = newPassword.isNotEmpty() && newPassword.length < 6,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Spacer(Modifier.height(10.dp))

                val mismatch = confirmPassword.isNotEmpty() && confirmPassword != newPassword
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmChange,
                    label = { Text("Повторите новый пароль") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
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
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
    isExporting: Boolean,
    onExportClick: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Данные и резервная копия", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = if (totalGames > 0) {
                    "В вашей библиотеке $totalGames партий (проанализировано: $analyzedGames). Вы можете сохранить их все в единый файл .pgn."
                } else {
                    "Загруженные партии хранятся локально на устройстве и доступны для экспорта в стандартный формат .pgn."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onExportClick,
                enabled = totalGames > 0 && !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isExporting) {
                        "Подготовка архива…"
                    } else {
                        "Экспортировать все партии (.pgn)"
                    }
                )
            }
        }
    }
}
