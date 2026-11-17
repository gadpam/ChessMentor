// presentation/ui/screen/SettingsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    user: User,
    container: AppContainer,
    onLogout: () -> Unit,
    onUserUpdated: (User) -> Unit = {}
) {
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val viewModel: SettingsViewModel = viewModel(factory = factory)

    LaunchedEffect(user.id) {
        viewModel.init(user)
    }

    val tempNickname by viewModel.tempNickname
    val isSoundEnabled by viewModel.isSoundEnabled
    val selectedThemeName by viewModel.selectedThemeName
    val message by viewModel.message

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        item {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // Сообщение
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

        // Параметры
        item {
            ParametersCard(
                isSoundEnabled = isSoundEnabled,
                selectedThemeName = selectedThemeName,
                onSoundToggle = { viewModel.isSoundEnabled.value = it },
                onThemeSelect = { viewModel.selectedThemeName.value = it }
            )
        }

        // Кнопка сохранения
        item {
            Button(
                onClick = { viewModel.saveSettings(onUserUpdated = onUserUpdated) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("Сохранить изменения")
            }
        }

        // Кнопка выхода
        item {
            OutlinedButton(
                onClick = { viewModel.logout(onLogout) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Выйти из аккаунта")
            }
        }

        // Версия
        item {
            Spacer(Modifier.height(32.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Версия 1.0.0 (Stockfish 19)", color = Color.Gray, fontSize = 12.sp)
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
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    null,
                    tint = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(cleanMessage)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null)
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
                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Профиль", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                label = { Text("Никнейм") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Edit, null) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Email, null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(email, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun ParametersCard(
    isSoundEnabled: Boolean,
    selectedThemeName: String,
    onSoundToggle: (Boolean) -> Unit,
    onThemeSelect: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Параметры", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Звук
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isSoundEnabled) Icons.Default.VolumeUp
                        else Icons.Default.VolumeOff,
                        null
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Звуковые эффекты")
                }
                Switch(
                    checked = isSoundEnabled,
                    onCheckedChange = onSoundToggle
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Тема доски
            Text(
                "Тема доски по умолчанию",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

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
                            label = { Text(theme.name) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}