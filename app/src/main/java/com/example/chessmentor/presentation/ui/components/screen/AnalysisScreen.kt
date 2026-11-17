package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.chessmentor.domain.entity.Mistake

@Composable
fun AnalysisScreen(
    gameId: Long,
    viewModel: GameViewModel,
    onNavigateBack: () -> Unit = {}
) {
    // Состояния из ViewModel
    val isLoading = viewModel.isLoading.value
    val message = viewModel.message.value
    val selectedGame = viewModel.selectedGame.value
    val mistakes = viewModel.selectedGameMistakes

    // Запускаем загрузку партии при первом показе
    LaunchedEffect(gameId) {
        // Находим партию в списке
        val game = viewModel.userGames.find { it.id == gameId }
        if (game != null) {
            viewModel.selectGame(game)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Заголовок с кнопкой назад
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад"
                )
            }
            Text(
                text = "Анализ партии",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            // Показываем индикатор загрузки
            isLoading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Анализируем партию...")
                    }
                }
            }

            // Показываем сообщение об ошибке
            message.startsWith("❌") -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                viewModel.clearMessage()
                                // Повторный анализ
                                if (selectedGame?.id != null) {
                                    viewModel.analyzeGame(selectedGame.id!!)
                                }
                            }
                        ) {
                            Text("Повторить")
                        }
                    }
                }
            }

            // Показываем результаты анализа
            selectedGame != null -> {
                // Статистика
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatItem("Точность", "${selectedGame.accuracy?.toInt() ?: 0}%")
                            StatItem("Ошибок", "${mistakes.size}")
                            StatItem("Зевков", "${mistakes.count { it.mistakeType == MistakeType.BLUNDER }}")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Список ошибок
                if (mistakes.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Отличная игра! Ошибок не найдено 🎉",
                                fontSize = 18.sp
                            )
                        }
                    }
                } else {
                    Text(
                        "Найденные ошибки:",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    LazyColumn {
                        items(mistakes.toList()) { mistake ->
                            MistakeCard(mistake)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // Показываем сообщение об успехе
                if (message.startsWith("✅")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Партия не найдена
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "Партия не найдена",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// Остальные @Composable функции остаются теми же
@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MistakeCard(mistake: Mistake) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (mistake.mistakeType) {
                MistakeType.BLUNDER -> MaterialTheme.colorScheme.errorContainer
                MistakeType.MISTAKE -> MaterialTheme.colorScheme.secondaryContainer
                MistakeType.INACCURACY -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Ход ${mistake.moveNumber}",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when (mistake.mistakeType) {
                        MistakeType.BLUNDER -> "Зевок"
                        MistakeType.MISTAKE -> "Ошибка"
                        MistakeType.INACCURACY -> "Неточность"
                    },
                    color = when (mistake.mistakeType) {
                        MistakeType.BLUNDER -> MaterialTheme.colorScheme.error
                        MistakeType.MISTAKE -> MaterialTheme.colorScheme.secondary
                        MistakeType.INACCURACY -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row {
                Text("Сыграно: ", fontSize = 14.sp)
                Text(
                    mistake.userMove,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (mistake.bestMove.isNotEmpty()) {
                Row {
                    Text("Лучше: ", fontSize = 14.sp)
                    Text(
                        mistake.bestMove,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = if (mistake.evaluationLoss > 50_000) {
                    "Потеря: форсированный мат"
                } else {
                    "Потеря: ${String.format(java.util.Locale.US, "%.1f", mistake.evaluationLoss / 100.0)} пешки"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}