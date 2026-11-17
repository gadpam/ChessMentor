// presentation/ui/screen/StatisticsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.ProblemTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.StatisticsViewModel

@Composable
fun StatisticsScreen(
    paddingValues: PaddingValues,
    user: User,
    container: AppContainer
) {
    val factory = remember(container) { ChessMentorViewModelFactory(container) }
    val viewModel: StatisticsViewModel = viewModel(factory = factory)

    LaunchedEffect(user.id, user.rating) {
        viewModel.loadStatistics(user)
    }

    val stats by viewModel.statistics
    val isLoading by viewModel.isLoading
    val errorMessage by viewModel.errorMessage

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        item {
            StatisticsHeader()
        }

        // Загрузка
        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // Ошибка
        if (errorMessage != null) {
            item {
                ErrorCard(message = errorMessage!!)
            }
        }

        // Статистика
        stats?.let { s ->
            // Рейтинг
            item {
                StatCard(
                    icon = Icons.Default.TrendingUp,
                    title = "Рейтинг",
                    value = "${s.currentRating}",
                    subValue = if (s.ratingChange >= 0) "+${s.ratingChange}" else "${s.ratingChange}",
                    subValueColor = if (s.ratingChange >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            // Точность
            item {
                StatCard(
                    icon = Icons.Default.TrackChanges,
                    title = "Точность",
                    value = "${s.averageAccuracy.toInt()}%",
                    subValue = s.accuracyTrend
                )
            }

            // Партии
            item {
                StatCard(
                    icon = Icons.Default.SportsEsports,
                    title = "Партии",
                    value = "${s.analyzedGames}",
                    subValue = "из ${s.totalGames}"
                )
            }

            // Анализ ошибок
            item {
                MistakesAnalysisCard(
                    blunders = s.blunders,
                    mistakes = s.mistakes,
                    inaccuracies = s.inaccuracies
                )
            }

            // Проблемные темы
            item {
                ProblemThemesCard(themes = s.problemThemes)
            }

            // Рекомендации
            item {
                RecommendationsCard(recommendations = s.recommendations)
            }
        }
    }
}

@Composable
private fun StatisticsHeader() {
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
                imageVector = Icons.Default.InsertChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Моя статистика", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Прогресс обучения", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                "Ошибка: $message",
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    subValue: String = "",
    subValueColor: Color = Color.Gray
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (subValue.isNotEmpty()) {
                Text(
                    subValue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = subValueColor
                )
            }
        }
    }
}

@Composable
private fun MistakesAnalysisCard(
    blunders: Int,
    mistakes: Int,
    inaccuracies: Int
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Анализ ошибок", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MistakeTypeCount(MistakeType.BLUNDER, blunders)
                MistakeTypeCount(MistakeType.MISTAKE, mistakes)
                MistakeTypeCount(MistakeType.INACCURACY, inaccuracies)
            }
        }
    }
}

@Composable
fun MistakeTypeCount(type: MistakeType, count: Int) {
    if (count > 0) {
        val color = when (type) {
            MistakeType.BLUNDER -> Color(0xFFD32F2F)
            MistakeType.MISTAKE -> Color(0xFFF57C00)
            MistakeType.INACCURACY -> Color(0xFFFBC02D)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = type.getIcon(),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ProblemThemesCard(
    themes: List<ProblemTheme>
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("Проблемные темы", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            if (themes.isEmpty()) {
                Text("Пока нет данных", color = Color.Gray, fontSize = 14.sp)
            } else {
                themes.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${theme.category}: ${theme.themeName}")
                        Text(
                            "${theme.mistakeCount} раз",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationsCard(recommendations: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFA000))
                Spacer(Modifier.width(8.dp))
                Text("Рекомендации", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            recommendations.forEach { recommendation ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("•", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(recommendation)
                }
            }
        }
    }
}