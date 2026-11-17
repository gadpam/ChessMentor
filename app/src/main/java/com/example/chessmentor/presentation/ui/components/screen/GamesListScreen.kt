// presentation/ui/screen/GamesListScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.*
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import kotlin.collections.get

@Composable
fun GamesListScreen(
    paddingValues: PaddingValues,
    gameViewModel: GameViewModel,
    @Suppress("unused") container: AppContainer,
    onGameClick: (Game) -> Unit
) {
    val games = gameViewModel.userGames
    val mistakesByGame = gameViewModel.mistakesByGameId
    val message by gameViewModel.message

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Заголовок
        item {
            GamesListHeader(gamesCount = games.size)
        }

        // Сообщение
        if (message.isNotEmpty()) {
            item {
                GamesListMessage(
                    message = message,
                    onDismiss = { gameViewModel.clearMessage() }
                )
            }
        }

        // Список партий (без N+1 запросов к БД на каждую карточку)
        if (games.isEmpty()) {
            item {
                EmptyGamesPlaceholder()
            }
        } else {
            items(
                items = games,
                key = { game -> game.id ?: game.hashCode() }
            ) { game ->
                val gameMistakes = game.id?.let { mistakesByGame[it] }.orEmpty()

                GameCard(
                    game = game,
                    mistakes = gameMistakes,
                    onClick = { onGameClick(game) },
                    onDelete = { game.id?.let { gameViewModel.deleteGame(it) } }
                )
            }
        }
    }
}

@Composable
private fun GamesListHeader(gamesCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📋 Мои партии",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Всего партий: $gamesCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun GamesListMessage(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            TextButton(onClick = onDismiss) {
                Text("✕")
            }
        }
    }
}

@Composable
private fun EmptyGamesPlaceholder() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📭", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Партий пока нет",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Загрузите свою первую партию!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GameCard(
    game: Game,
    mistakes: List<Mistake>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок с кнопкой удаления
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Цвет игрока
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(
                                if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black,
                                MaterialTheme.shapes.small
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Партия #${game.id}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = game.timeControl ?: "Без контроля времени",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(game.analysisStatus)

                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Результаты анализа
            if (game.isAnalyzed()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Точность
                    AccuracyBadge(accuracy = game.accuracy?.toInt() ?: 0)

                    // Ошибки
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MistakeCountBadge(
                            emoji = "❌",
                            label = "Зевки",
                            count = game.blundersCount
                        )
                        MistakeCountBadge(
                            emoji = "⚠️",
                            label = "Ошибки",
                            count = game.mistakesCount
                        )
                        MistakeCountBadge(
                            emoji = "⚡",
                            label = "Неточности",
                            count = game.inaccuraciesCount
                        )
                    }
                }
            } else if (game.analysisStatus == AnalysisStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "⏳ Ожидает анализа",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (game.analysisStatus == AnalysisStatus.FAILED) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "❌ Анализ не удался",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить партию?") },
            text = { Text("Это действие нельзя отменить. Все данные анализа будут потеряны.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun StatusChip(status: AnalysisStatus) {
    val (color, text) = when (status) {
        AnalysisStatus.COMPLETED -> Color(0xFF4CAF50) to "✓ Готово"
        AnalysisStatus.IN_PROGRESS -> Color(0xFFFFA726) to "⏳ Анализ..."
        AnalysisStatus.PENDING -> Color(0xFF9E9E9E) to "⏸ Очередь"
        AnalysisStatus.FAILED -> Color(0xFFE53935) to "✗ Ошибка"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AccuracyBadge(accuracy: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Точность",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$accuracy%",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                accuracy >= 90 -> Color(0xFF4CAF50)
                accuracy >= 80 -> Color(0xFF8BC34A)
                accuracy >= 70 -> Color(0xFFFFA726)
                accuracy >= 60 -> Color(0xFFFF9800)
                else -> Color(0xFFE53935)
            }
        )
    }
}

@Composable
fun MistakeCountBadge(
    emoji: String,
    label: String,
    count: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = emoji,
            fontSize = 16.sp
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}