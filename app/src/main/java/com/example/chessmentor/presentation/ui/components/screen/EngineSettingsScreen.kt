// presentation/ui/screen/EngineSettingsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import kotlin.math.roundToInt

@Composable
fun EngineSettingsScreen(
    paddingValues: PaddingValues,
    engineSettingsRepository: EngineSettingsRepository,
    onBack: () -> Unit
) {
    // Загружаем текущие настройки
    var settings by remember {
        mutableStateOf(engineSettingsRepository.getSettings())
    }

    var hasChanges by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()), // Скролл
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Заголовок
        SettingsHeader(onBack)

        // Профили
        ProfilesSection(
            currentSettings = settings,
            onProfileSelected = { newSettings ->
                settings = newSettings
                hasChanges = true
            }
        )

        // Глубина анализа
        DepthSetting(
            depth = settings.depth,
            onDepthChange = { newDepth ->
                settings = settings.copy(depth = newDepth)
                hasChanges = true
            }
        )

        // Потоки CPU
        ThreadsSetting(
            threads = settings.threads,
            onThreadsChange = { newThreads ->
                settings = settings.copy(threads = newThreads)
                hasChanges = true
            }
        )

        // Размер хеша
        HashSetting(
            hashSizeMb = settings.hashSizeMb,
            onHashChange = { newHash ->
                settings = settings.copy(hashSizeMb = newHash)
                hasChanges = true
            }
        )

        // Информация о настройках
        SettingsInfo(settings = settings)

        // ✅ ИСПРАВЛЕНО: Убран weight(1f), заменён на обычный отступ
        Spacer(modifier = Modifier.height(24.dp))

        // Кнопки
        BottomButtons(
            hasChanges = hasChanges,
            onSave = {
                engineSettingsRepository.saveSettings(settings)
                hasChanges = false
                onBack()
            },
            onReset = {
                settings = EngineSettings.default()
                hasChanges = true
            },
            onCancel = onBack
        )

        // Отступ снизу для удобства
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = "Настройки движка",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Stockfish 17",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ProfilesSection(
    currentSettings: EngineSettings,
    onProfileSelected: (EngineSettings) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Быстрый выбор",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileChip(
                    name = "⚡ Быстрый",
                    description = "Глубина 12",
                    isSelected = currentSettings.depth <= 12,
                    onClick = { onProfileSelected(EngineSettings.fast()) },
                    modifier = Modifier.weight(1f)
                )

                ProfileChip(
                    name = "⚖️ Стандарт",
                    description = "Глубина 15",
                    isSelected = currentSettings.depth in 13..17,
                    onClick = { onProfileSelected(EngineSettings.default()) },
                    modifier = Modifier.weight(1f)
                )

                ProfileChip(
                    name = "🔬 Глубокий",
                    description = "Глубина 22",
                    isSelected = currentSettings.depth >= 18,
                    onClick = { onProfileSelected(EngineSettings.deep()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ProfileChip(
    name: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            )
        } else null
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DepthSetting(
    depth: Int,
    onDepthChange: (Int) -> Unit
) {
    SettingCard(
        icon = Icons.Default.Layers,
        title = "Глубина анализа",
        subtitle = "Количество полуходов вперёд",
        value = "$depth"
    ) {
        Column {
            Slider(
                value = depth.toFloat(),
                onValueChange = { onDepthChange(it.roundToInt()) },
                valueRange = EngineSettings.MIN_DEPTH.toFloat()..EngineSettings.MAX_DEPTH.toFloat(),
                steps = EngineSettings.MAX_DEPTH - EngineSettings.MIN_DEPTH - 1,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${EngineSettings.MIN_DEPTH}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "${EngineSettings.MAX_DEPTH}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ThreadsSetting(
    threads: Int,
    onThreadsChange: (Int) -> Unit
) {
    SettingCard(
        icon = Icons.Default.Memory,
        title = "Потоки CPU",
        subtitle = "Количество ядер процессора",
        value = "$threads"
    ) {
        Column {
            Slider(
                value = threads.toFloat(),
                onValueChange = { onThreadsChange(it.roundToInt()) },
                valueRange = EngineSettings.MIN_THREADS.toFloat()..EngineSettings.MAX_THREADS.toFloat(),
                steps = EngineSettings.MAX_THREADS - EngineSettings.MIN_THREADS - 1,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${EngineSettings.MIN_THREADS}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "${EngineSettings.MAX_THREADS}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HashSetting(
    hashSizeMb: Int,
    onHashChange: (Int) -> Unit
) {
    val hashOptions = listOf(16, 32, 64, 128, 256)

    SettingCard(
        icon = Icons.Default.Storage,
        title = "Размер хеша",
        subtitle = "Память для кэширования",
        value = "$hashSizeMb MB"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hashOptions.forEach { size ->
                FilterChip(
                    selected = hashSizeMb == size,
                    onClick = { onHashChange(size) },
                    label = { Text("$size") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: String,
    content: @Composable () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

@Composable
private fun SettingsInfo(settings: EngineSettings) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Информация",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val estimatedTime = settings.estimatedTimePerMove()

            Text(text = "• Профиль: ${settings.getProfileName()}")
            Text(text = "• Время на ход: ~${"%.1f".format(estimatedTime)} сек")

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "💡 Больше глубина = точнее анализ, но дольше",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BottomButtons(
    hasChanges: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.weight(1f)
        ) {
            Text("Сброс")
        }

        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = hasChanges
        ) {
            Text("Сохранить")
        }
    }
}
