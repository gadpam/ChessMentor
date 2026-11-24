// presentation/ui/screen/EngineSettingsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.presentation.ui.components.NnueManagementCard
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun EngineSettingsScreen(
    paddingValues: PaddingValues,
    engineSettingsRepository: EngineSettingsRepository,
    onBack: () -> Unit,
    chessEngine: ChessEngine? = null,
    onRetryDownload: (() -> Unit)? = null,
    onForceRedownload: (() -> Unit)? = null,
    onVerifyNetwork: (() -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val availableCores = remember {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    var settings by remember {
        mutableStateOf(engineSettingsRepository.getSettings())
    }
    var hasChanges by remember { mutableStateOf(false) }

    val engineStatus by if (chessEngine != null) {
        chessEngine.status.collectAsState()
    } else {
        remember { mutableStateOf<EngineStatus>(EngineStatus.Idle) }
    }

    val layoutDirection = LocalLayoutDirection.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(
                top = 4.dp + paddingValues.calculateTopPadding(),
                bottom = 28.dp + paddingValues.calculateBottomPadding(),
                start = 16.dp + paddingValues.calculateStartPadding(layoutDirection),
                end = 16.dp + paddingValues.calculateEndPadding(layoutDirection)
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        if (chessEngine != null) {
            NnueManagementCard(
                status = engineStatus,
                onDownloadOrRetry = {
                    if (onRetryDownload != null) {
                        onRetryDownload()
                    } else {
                        scope.launch(Dispatchers.IO) {
                            runCatching { chessEngine.retryInit(forceRedownload = false) }
                        }
                    }
                },
                onCancelDownload = {
                    if (onCancelDownload != null) {
                        onCancelDownload()
                    } else {
                        chessEngine.cancelInit()
                    }
                },
                onVerifyChecksum = {
                    if (onVerifyNetwork != null) {
                        onVerifyNetwork()
                    } else {
                        scope.launch(Dispatchers.IO) {
                            runCatching { chessEngine.verifyNetwork() }
                        }
                    }
                },
                onForceRedownload = {
                    if (onForceRedownload != null) {
                        onForceRedownload()
                    } else {
                        scope.launch(Dispatchers.IO) {
                            runCatching { chessEngine.retryInit(forceRedownload = true) }
                        }
                    }
                }
            )
        }

        ProfilesSection(
            currentSettings = settings,
            availableCores = availableCores,
            onProfileSelected = { newSettings ->
                settings = newSettings
                hasChanges = true
            }
        )

        DepthSetting(
            depth = settings.depth,
            onDepthChange = { newDepth ->
                settings = settings.copy(depth = newDepth)
                hasChanges = true
            }
        )

        ThreadsSetting(
            threads = settings.threads,
            availableCores = availableCores,
            onThreadsChange = { newThreads ->
                settings = settings.copy(threads = newThreads)
                hasChanges = true
            }
        )

        HashSetting(
            hashSizeMb = settings.hashSizeMb,
            onHashChange = { newHash ->
                settings = settings.copy(hashSizeMb = newHash)
                hasChanges = true
            }
        )

        SettingsInfo(
            settings = settings,
            availableCores = availableCores
        )

        Spacer(modifier = Modifier.height(8.dp))

        BottomButtons(
            hasChanges = hasChanges,
            onSave = {
                val validated = settings.validate()
                engineSettingsRepository.saveSettings(validated)
                hasChanges = false
                if (chessEngine != null) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { chessEngine.applySettings(validated) }
                    }
                }
                onBack()
            },
            onReset = {
                settings = EngineSettings.default()
                hasChanges = true
            }
        )
    }
}

@Composable
private fun ProfilesSection(
    currentSettings: EngineSettings,
    availableCores: Int,
    onProfileSelected: (EngineSettings) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Профиль анализа",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            @OptIn(ExperimentalLayoutApi::class)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackProfiles = maxWidth < (290 * LocalDensity.current.fontScale).dp
                val fastPreset = EngineSettings.fast()
                val defaultPreset = EngineSettings.default().let {
                    it.copy(threads = it.threads.coerceAtMost(availableCores))
                }
                val deepPreset = EngineSettings.deep().let {
                    it.copy(threads = it.threads.coerceAtMost(availableCores))
                }
                val matchesAnyPreset = currentSettings == fastPreset ||
                    currentSettings == defaultPreset ||
                    currentSettings == deepPreset
                val profileData = listOf(
                    Triple("Быстрый", "Глубина 12", currentSettings == fastPreset) to {
                        onProfileSelected(fastPreset)
                    },
                    Triple("Стандарт", "Глубина 15", currentSettings == defaultPreset) to {
                        onProfileSelected(defaultPreset)
                    },
                    Triple("Глубокий", "Глубина 22", currentSettings == deepPreset) to {
                        onProfileSelected(deepPreset)
                    }
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stackProfiles) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profileData.forEach { (meta, click) ->
                                ProfileChip(
                                    name = meta.first,
                                    description = meta.second,
                                    isSelected = meta.third,
                                    onClick = click,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            profileData.forEach { (meta, click) ->
                                ProfileChip(
                                    name = meta.first,
                                    description = meta.second,
                                    isSelected = meta.third,
                                    onClick = click,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    if (!matchesAnyPreset) {
                        Text(
                            text = "Ручная настройка: глубина ${currentSettings.depth} · ${currentSettings.threads} пот. · ${currentSettings.hashSizeMb} МБ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                selected = isSelected
                role = Role.RadioButton
            },
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        subtitle = "Количество полуходов расчёта вперёд",
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
                    text = "${EngineSettings.MIN_DEPTH} (быстро)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${EngineSettings.MAX_DEPTH} (макс.)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThreadsSetting(
    threads: Int,
    availableCores: Int,
    onThreadsChange: (Int) -> Unit
) {
    val recommended = (availableCores / 2).coerceIn(1, EngineSettings.MAX_THREADS)
    SettingCard(
        icon = Icons.Default.Memory,
        title = "Потоки CPU",
        subtitle = "Ядер на устройстве: $availableCores (оптимально: $recommended)",
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${EngineSettings.MAX_THREADS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (threads > availableCores) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Выбрано больше потоков, чем ядер CPU ($availableCores) — это может замедлить анализ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HashSetting(
    hashSizeMb: Int,
    onHashChange: (Int) -> Unit
) {
    val hashOptions = listOf(16, 32, 64, 128, 256)

    SettingCard(
        icon = Icons.Default.Storage,
        title = "Размер хеш-таблицы",
        subtitle = "Оперативная память для кэширования позиций",
        value = "$hashSizeMb МБ"
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            hashOptions.forEach { size ->
                FilterChip(
                    selected = hashSizeMb == size,
                    onClick = { onHashChange(size) },
                    label = { Text("$size МБ", maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun SettingCard(
    icon: ImageVector,
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
                    contentDescription = title,
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

            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsInfo(
    settings: EngineSettings,
    availableCores: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Оценка скорости",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Оценка скорости",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val estimatedPerMove = settings.estimatedTimePerMove()
            val fullGameSec = (estimatedPerMove * 70 / settings.threads.coerceIn(1, availableCores).coerceAtMost(4))
                .roundToInt()
                .coerceAtLeast(8)

            Text(
                text = "• Профиль: ${settings.getProfileName()} (~${String.format(Locale.US, "%.1f", estimatedPerMove)} сек/ход)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Партия из 35 ходов: примерно $fullGameSec сек",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Глубина 15–18 оптимально сочетает скорость и точность тактического разбора на смартфоне.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BottomButtons(
    hasChanges: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text("По умолчанию")
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            enabled = hasChanges
        ) {
            Text("Сохранить")
        }
    }
}
