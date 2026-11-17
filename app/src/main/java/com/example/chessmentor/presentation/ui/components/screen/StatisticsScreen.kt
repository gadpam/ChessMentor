// presentation/ui/screen/StatisticsScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.chessmentor.domain.training.PatternMastery
import com.example.chessmentor.domain.usecase.GetUserStatisticsUseCase
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.StatisticsViewModel

@Composable
fun StatisticsScreen(
    paddingValues: PaddingValues,
    user: User,
    container: AppContainer,
    onPracticeWeakPatterns: (() -> Unit)? = null
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
        item {
            StatisticsHeader(skillLevel = stats?.skillLevel)
        }

        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        errorMessage?.let { err ->
            item {
                ErrorCard(message = err)
            }
        }

        stats?.let { s ->
            // Рейтинг
            item {
                StatCard(
                    icon = Icons.Default.TrendingUp,
                    title = "Рейтинг Эло",
                    value = "${s.currentRating}",
                    subValue = if (s.ratingChange >= 0) "+${s.ratingChange}" else "${s.ratingChange}",
                    subValueColor = if (s.ratingChange >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            // Тактическая тренировка (прогресс, точность, серия, слабые/сильные мотивы)
            item {
                TrainingProgressCard(
                    training = s.training,
                    onPracticeWeakPatterns = onPracticeWeakPatterns
                )
            }

            // Точность в партиях
            item {
                StatCard(
                    icon = Icons.Default.TrackChanges,
                    title = "Точность в партиях",
                    value = "${s.averageAccuracy.toInt()}%",
                    subValue = s.accuracyTrend
                )
            }

            // Партии
            item {
                StatCard(
                    icon = Icons.Default.SportsEsports,
                    title = "Проанализировано партий",
                    value = "${s.analyzedGames}",
                    subValue = "из ${s.totalGames}"
                )
            }

            // Анализ ошибок в партиях
            item {
                MistakesAnalysisCard(
                    blunders = s.blunders,
                    mistakes = s.mistakes,
                    inaccuracies = s.inaccuracies
                )
            }

            // Проблемные темы в партиях
            item {
                ProblemThemesCard(themes = s.problemThemes)
            }

            // Персональные рекомендации
            item {
                RecommendationsCard(recommendations = s.recommendations)
            }
        }
    }
}

@Composable
private fun StatisticsHeader(skillLevel: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = "Моя статистика",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (!skillLevel.isNullOrBlank()) {
                        "Прогресс обучения • $skillLevel"
                    } else {
                        "Прогресс обучения"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun TrainingProgressCard(
    training: GetUserStatisticsUseCase.TrainingStatistics,
    onPracticeWeakPatterns: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Тактическая тренировка",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${training.uniqueExercisesSolved}/${training.totalAvailableExercises}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val progress = if (training.totalAvailableExercises > 0) {
                (training.uniqueExercisesSolved.toFloat() / training.totalAvailableExercises.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            if (training.totalAttempts == 0) {
                Text(
                    text = "Доступно ${training.totalAvailableExercises} задач" +
                        if (training.personalMistakeExercises > 0) {
                            " (из них ${training.personalMistakeExercises} по вашим партиям)."
                        } else {
                            ". Решите первую задачу, чтобы увидеть аналитику по тактическим мотивам!"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onPracticeWeakPatterns != null) {
                    Button(
                        onClick = onPracticeWeakPatterns,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Начать тренировку")
                    }
                }
            } else {
                // Ключевые показатели тренировки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TrainingMetricItem(
                        label = "Точность",
                        value = "${training.solveAccuracyPercent}%"
                    )
                    TrainingMetricItem(
                        label = "Решено",
                        value = "${training.solvedAttempts}/${training.totalAttempts}"
                    )
                    TrainingMetricItem(
                        label = "Серия",
                        value = "${training.currentStreak} (макс ${training.bestStreak})"
                    )
                }

                if (training.averageSolveTimeSeconds > 0 || training.unresolvedFailedCount > 0 || training.personalMistakeExercises > 0) {
                    val details = buildList {
                        if (training.averageSolveTimeSeconds > 0) {
                            add("Ср. время: ${training.averageSolveTimeSeconds} с")
                        }
                        if (training.personalMistakeExercises > 0) {
                            add("Из партий: ${training.personalMistakeExercises}")
                        }
                        if (training.unresolvedFailedCount > 0) {
                            add("К повторению: ${training.unresolvedFailedCount}")
                        }
                    }.joinToString(" • ")

                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Слабые тактические мотивы
                if (training.weakPatterns.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text(
                        text = "Требуют отработки",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    training.weakPatterns.forEach { pm ->
                        PatternMasteryRow(mastery = pm, isWeak = true)
                    }
                    if (onPracticeWeakPatterns != null) {
                        Button(
                            onClick = onPracticeWeakPatterns,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Отработать слабые темы")
                        }
                    }
                }

                // Сильные тактические мотивы
                if (training.strongPatterns.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text(
                        text = "Сильные мотивы",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    training.strongPatterns.forEach { pm ->
                        PatternMasteryRow(mastery = pm, isWeak = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainingMetricItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PatternMasteryRow(
    mastery: PatternMastery,
    isWeak: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (!isWeak) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = mastery.pattern.displayName,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Surface(
            color = if (isWeak) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = "${mastery.accuracyPercent}% (${mastery.solvedAttempts}/${mastery.totalAttempts})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isWeak) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
    subValueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Ошибки в партиях", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            if (blunders == 0 && mistakes == 0 && inaccuracies == 0) {
                Text(
                    text = "Пока нет проанализированных ошибок",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            } else {
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("Частые ошибки в партиях", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            if (themes.isEmpty()) {
                Text(
                    text = "Пока нет данных",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            } else {
                themes.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${theme.category}: ${theme.themeName}",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${theme.mistakeCount} раз",
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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Рекомендации",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            recommendations.forEach { recommendation ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = recommendation,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
