// presentation/ui/components/AnalysisProgressDialog.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate  // Добавлен импорт
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase

@Composable
fun AnalysisProgressDialog(
    progress: AnalyzeGameUseCase.AnalysisProgress?,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    if (progress == null || progress is AnalyzeGameUseCase.AnalysisProgress.Completed) {
        return
    }

    Dialog(
        onDismissRequest = { /* Нельзя случайно закрыть тапом мимо окна */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (progress) {
                    is AnalyzeGameUseCase.AnalysisProgress.Starting -> {
                        StartingContent()
                        if (onCancel != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = onCancel) {
                                Text("Отменить")
                            }
                        }
                    }
                    is AnalyzeGameUseCase.AnalysisProgress.InProgress -> {
                        InProgressContent(
                            current = progress.currentMove,
                            total = progress.totalMoves,
                            moveNotation = progress.currentMoveNotation
                        )
                        if (onCancel != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = onCancel) {
                                Text("Отменить анализ")
                            }
                        }
                    }
                    is AnalyzeGameUseCase.AnalysisProgress.Failed -> {
                        FailedContent(
                            error = progress.error,
                            onDismiss = onDismiss
                        )
                    }
                    else -> { /* Completed обрабатывается выше */ }
                }
            }
        }
    }
}

@Composable
private fun StartingContent() {
    Icon(Icons.Default.Flag, contentDescription = null,
        modifier = Modifier.padding(bottom = 16.dp).size(48.dp))

    Text(
        text = "Запуск анализа",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Инициализация движка...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InProgressContent(
    current: Int,
    total: Int,
    moveNotation: String
) {
    // Конвертируем полуходы в полные ходы для отображения
    val currentFullMove = (current + 1) / 2
    val totalFullMoves = (total + 1) / 2

    val progress = if (total > 0) current.toFloat() / total else 0f
    val percentage = (progress * 100).toInt()

    // Анимированная иконка
    val infiniteTransition = rememberInfiniteTransition(label = "chess")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Text(
        text = "♔",
        fontSize = 48.sp,
        modifier = Modifier
            .padding(bottom = 16.dp)
            .rotate(rotation)
    )

    Text(
        text = "Анализ партии",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Показываем полные ходы вместо полуходов
    Text(
        text = "Ход $currentFullMove из $totalFullMoves",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (moveNotation.isNotEmpty()) {
        Text(
            text = moveNotation,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Прогресс-бар (оставляем на основе полуходов для точности)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "$percentage%",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(16.dp))

    LoadingDots()
}

@Composable
private fun FailedContent(
    error: String,
    onDismiss: () -> Unit
) {
    Icon(Icons.Default.ErrorOutline, contentDescription = null,
        modifier = Modifier.padding(bottom = 16.dp).size(48.dp))

    Text(
        text = "Ошибка анализа",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text("Закрыть")
    }
}

@Composable
private fun LoadingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(3) { index ->
            val animationDelay = index * 100
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        0.3f at animationDelay with LinearEasing
                        1f at animationDelay + 200 with LinearEasing
                        0.3f at animationDelay + 400 with LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    )
            )
        }
    }
}