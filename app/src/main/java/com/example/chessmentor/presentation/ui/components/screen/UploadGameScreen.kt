// presentation/ui/screen/UploadGameScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
import com.example.chessmentor.presentation.viewmodel.GameViewModel

@Composable
fun UploadGameScreen(
    paddingValues: PaddingValues,
    gameViewModel: GameViewModel,
    engineSettingsRepository: EngineSettingsRepository,  // ✅ НОВОЕ
    onGameUploaded: () -> Unit,
    onAnalysisComplete: () -> Unit,
    onOpenEngineSettings: () -> Unit  // ✅ НОВОЕ
) {
    val pgnInput by gameViewModel.pgnInput
    val selectedColor by gameViewModel.selectedColor
    val timeControl by gameViewModel.timeControl
    val message by gameViewModel.message
    val isLoading by gameViewModel.isLoading

    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    val currentOnAnalysisComplete by rememberUpdatedState(onAnalysisComplete)
    val currentOnGameUploaded by rememberUpdatedState(onGameUploaded)

    // Автоматический переход к сводке после успешного завершения анализа
    LaunchedEffect(analysisProgress) {
        val completed = analysisProgress as? AnalyzeGameUseCase.AnalysisProgress.Completed
        if (completed != null) {
            gameViewModel.clearAnalysisProgress()
            if (completed.result is AnalyzeGameUseCase.Result.Success) {
                currentOnGameUploaded()
                currentOnAnalysisComplete()
            }
        }
    }

    // Получаем актуальные настройки движка для отображения
    val engineSettings = remember { engineSettingsRepository.getSettings() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        item {
            UploadHeader()
        }

        // ✅ НОВОЕ: Карточка настроек движка
        item {
            EngineSettingsCard(
                settings = engineSettings,
                onClick = onOpenEngineSettings
            )
        }

        // Сообщение
        if (message.isNotEmpty()) {
            item {
                UploadMessage(
                    message = message,
                    isLoading = isLoading,
                    onDismiss = { gameViewModel.clearMessage() }
                )
            }
        }

        // Форма
        item {
            UploadForm(
                pgnInput = pgnInput,
                selectedColor = selectedColor,
                timeControl = timeControl,
                isLoading = isLoading,
                onPgnChange = { gameViewModel.pgnInput.value = it },
                onColorChange = { gameViewModel.selectedColor.value = it },
                onTimeControlChange = { gameViewModel.timeControl.value = it },
                onUpload = {
                    gameViewModel.uploadAndAnalyzeGame()
                }
            )
        }
    }

    // Диалог прогресса анализа
    AnalysisProgressDialog(
        progress = analysisProgress,
        onDismiss = {
            gameViewModel.clearAnalysisProgress()
        },
        onCancel = {
            gameViewModel.cancelAnalysis()
        }
    )
}

// ✅ НОВОЕ: Карточка с настройками движка
@Composable
private fun EngineSettingsCard(
    settings: EngineSettings,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Настройки движка",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Глубина: ${settings.depth} • Потоки: ${settings.threads} • Хеш: ${settings.hashSizeMb}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            // Индикатор профиля
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = settings.getProfileName(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Открыть",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun UploadHeader() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Загрузить партию",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Вставьте PGN для анализа",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UploadMessage(
    message: String,
    isLoading: Boolean,
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
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = cleanMessage,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (!isLoading) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        }
    }
}

@Composable
private fun UploadForm(
    pgnInput: String,
    selectedColor: ChessColor,
    timeControl: String,
    isLoading: Boolean,
    onPgnChange: (String) -> Unit,
    onColorChange: (ChessColor) -> Unit,
    onTimeControlChange: (String) -> Unit,
    onUpload: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // PGN поле
            OutlinedTextField(
                value = pgnInput,
                onValueChange = onPgnChange,
                label = { Text("PGN партии") },
                placeholder = { Text("1. e4 e5 2. Nf3 Nc6 ...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10,
                enabled = !isLoading,
                leadingIcon = {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            )

            // Выбор цвета
            Column {
                Text(
                    text = "Ваш цвет:",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // БЕЛЫЕ
                    FilterChip(
                        selected = selectedColor == ChessColor.WHITE,
                        onClick = { onColorChange(ChessColor.WHITE) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Белые")
                                if (selectedColor == ChessColor.WHITE) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(1.dp, Color.Gray, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(
                                            width = 8.dp,
                                            color = Color.White,
                                            shape = CircleShape
                                        )
                                )
                            }
                        },
                        enabled = !isLoading
                    )

                    // ЧЁРНЫЕ
                    FilterChip(
                        selected = selectedColor == ChessColor.BLACK,
                        onClick = { onColorChange(ChessColor.BLACK) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Чёрные")
                                if (selectedColor == ChessColor.BLACK) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(1.dp, Color.Gray, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(
                                            width = 8.dp,
                                            color = Color.Black,
                                            shape = CircleShape
                                        )
                                )
                            }
                        },
                        enabled = !isLoading
                    )
                }
            }

            // Контроль времени
            OutlinedTextField(
                value = timeControl,
                onValueChange = onTimeControlChange,
                label = { Text("Контроль времени (опционально)") },
                placeholder = { Text("5+3, 10+0, 15+10") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                leadingIcon = {
                    Icon(Icons.Default.Timer, contentDescription = null)
                }
            )

            // Кнопка загрузки
            Button(
                onClick = onUpload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading && pgnInput.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Загружаем...")
                } else {
                    Icon(Icons.Default.Analytics, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Загрузить и анализировать")
                }
            }

            HorizontalDivider()

            // Подсказка
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Совет:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Вы можете скопировать PGN из Chess.com или Lichess",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Пример: 1. e4 e5 2. Nf3 Nc6 3. Bb5 a6",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}