// presentation/ui/components/EngineStatusCard.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.engine.EngineErrorReason
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.engine.NnueIntegrity
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme

/**
 * Компактный баннер состояния загрузки/готовности нейросети Stockfish (NNUE).
 *
 * На главном экране вызывается с `showWhenReady = false`, поэтому появляется только во время
 * первой загрузки/проверки NNUE или при ошибке сети, не занимая места при штатной работе.
 */
@Composable
fun EngineStatusBanner(
    status: EngineStatus,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    showWhenReady: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (status is EngineStatus.Idle) return
    if (status is EngineStatus.Ready && !showWhenReady) return

    val isError = status is EngineStatus.Error
    val isCancelled = (status as? EngineStatus.Error)?.reason == EngineErrorReason.CANCELLED
    val containerColor = when {
        status is EngineStatus.Ready -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        isCancelled -> MaterialTheme.colorScheme.secondaryContainer
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
    }
    val borderColor = when {
        status is EngineStatus.Ready -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
        isCancelled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (status) {
                EngineStatus.Idle -> Unit

                EngineStatus.CheckingNetwork -> {
                    BannerBusyRow(
                        title = "Проверка нейросети Stockfish (NNUE)…",
                        subtitle = NnueIntegrity.NET_NAME,
                        progress = null
                    )
                }

                is EngineStatus.DownloadingNetwork -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Загрузка нейросети Stockfish",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val pctSuffix = status.progressPercent?.let { " · $it%" }.orEmpty()
                            Text(
                                text = "Загрузка нейросети Stockfish$pctSuffix",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${status.formattedTransferred()} · ${status.formattedSource()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Приостановить загрузку",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val fraction = status.progressFraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is EngineStatus.VerifyingNetwork -> {
                    BannerBusyRow(
                        title = "Проверка контрольной суммы SHA-256…",
                        subtitle = "${NnueIntegrity.NET_NAME} (${NnueIntegrity.formatMegabytes(status.fileBytes)})",
                        progress = null
                    )
                }

                is EngineStatus.InitializingEngine -> {
                    BannerBusyRow(
                        title = "Запуск движка Stockfish 19…",
                        subtitle = "Подключение нейросети (${NnueIntegrity.formatMegabytes(status.netSizeBytes)})",
                        progress = null
                    )
                }

                is EngineStatus.Ready -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Stockfish готов к анализу",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stockfish 19 готов к анализу",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = status.formattedSummary(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is EngineStatus.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (isCancelled) {
                                Icons.Default.PauseCircleOutline
                            } else {
                                Icons.Default.ErrorOutline
                            },
                            contentDescription = if (isCancelled) "Загрузка приостановлена" else "Ошибка Stockfish NNUE",
                            tint = if (isCancelled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stockfish NNUE: ${status.message}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isCancelled) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = status.reason.userHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCancelled) {
                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                                }
                            )
                        }
                    }

                    if (status.canRetry || onOpenSettings != null) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (onOpenSettings != null) {
                                TextButton(
                                    onClick = onOpenSettings,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Настройки движка",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Настройки")
                                }
                            }
                            if (status.canRetry) {
                                FilledTonalButton(
                                    onClick = onRetry,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Повторить",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isCancelled) "Продолжить" else "Повторить")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerBusyRow(
    title: String,
    subtitle: String,
    progress: Float?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Развёрнутая карточка управления нейросетью NNUE для `EngineSettingsScreen`:
 * отображает состояние, прогресс скачивания, размер файла, контрольную сумму SHA-256
 * и действия проверки, отмены и повторной загрузки.
 */
@Composable
fun NnueManagementCard(
    status: EngineStatus,
    onDownloadOrRetry: () -> Unit,
    onCancelDownload: () -> Unit,
    onVerifyChecksum: () -> Unit,
    onForceRedownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmRedownload by remember { mutableStateOf(false) }

    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            title = { Text("Перекачать нейросеть NNUE?") },
            text = {
                Text(
                    text = "Текущий файл ${NnueIntegrity.NET_NAME} будет удалён и заново скачан с официального сервера Stockfish с проверкой SHA-256. Потребуется подключение к интернету.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRedownload = false
                        onForceRedownload()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Перекачать")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRedownload = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = "Нейросеть NNUE",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Нейросеть NNUE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = NnueIntegrity.NET_NAME,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(status = status)
            }

            when (status) {
                EngineStatus.Idle -> {
                    Text(
                        text = "Движок ещё не инициализирован. Нажмите «Проверить и запустить», чтобы подключить локальную сеть или скачать её.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = onDownloadOrRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Проверить и запустить", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверить и запустить")
                    }
                }

                EngineStatus.CheckingNetwork -> {
                    Text(
                        text = "Проверяем наличие локального файла нейросети…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is EngineStatus.DownloadingNetwork -> {
                    val pctText = status.progressPercent?.let { "$it% · " }.orEmpty()
                    Text(
                        text = "$pctText${status.formattedTransferred()} (${status.formattedSource()})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    val fraction = status.progressFraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Приостановить загрузку", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Приостановить загрузку")
                    }
                }

                is EngineStatus.VerifyingNetwork -> {
                    Text(
                        text = "Сверка контрольной суммы SHA-256 (${NnueIntegrity.EXPECTED_SHA256_PREFIX}…, ${NnueIntegrity.formatMegabytes(status.fileBytes)})…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is EngineStatus.InitializingEngine -> {
                    Text(
                        text = "Запуск Stockfish 19 и загрузка весов NNUE (${NnueIntegrity.formatMegabytes(status.netSizeBytes)})…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is EngineStatus.Ready -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val sizeLine = if (status.netSizeBytes > 0L) {
                                NnueIntegrity.formatMegabytes(status.netSizeBytes)
                            } else {
                                "установлена"
                            }
                            Text(
                                text = "• Размер на диске: $sizeLine (работает офлайн)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "• SHA-256 префикс: ${status.hashPrefix} ✓",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val stackButtons = maxWidth < (285 * LocalDensity.current.fontScale).dp
                        if (stackButtons) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onVerifyChecksum,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Проверить SHA-256",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверить SHA-256")
                                }
                                OutlinedButton(
                                    onClick = { confirmRedownload = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Перекачать",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Перекачать")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onVerifyChecksum,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Проверить SHA-256",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Проверить SHA-256", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { confirmRedownload = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Перекачать",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Перекачать", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                is EngineStatus.Error -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = status.reason.userHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (status.canRetry) {
                        Button(
                            onClick = onDownloadOrRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Возобновить загрузку NNUE",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (status.reason == EngineErrorReason.CANCELLED) {
                                    "Возобновить загрузку NNUE"
                                } else {
                                    "Скачать нейросеть NNUE"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: EngineStatus) {
    val (bg, fg) = when (status) {
        is EngineStatus.Ready -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        is EngineStatus.Error -> {
            if (status.reason == EngineErrorReason.CANCELLED) {
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
        }
        EngineStatus.Idle -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Text(
            text = status.shortBadgeLabel(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Preview(showBackground = true, name = "EngineStatus Ready")
@Composable
private fun EngineStatusBannerPreview() {
    ChessMentorTheme(darkTheme = false) {
        EngineStatusBanner(
            status = EngineStatus.Ready(netSizeBytes = 67_000_000L),
            onRetry = {},
            onCancel = {},
            showWhenReady = true,
            modifier = Modifier.padding(12.dp)
        )
    }
}
