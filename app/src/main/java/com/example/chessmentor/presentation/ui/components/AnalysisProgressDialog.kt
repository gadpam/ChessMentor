package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase.AnalysisProgress

@Composable
fun AnalysisProgressDialog(
    progress: AnalysisProgress?,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)? = null,
    gameId: Long? = null
) {
    if (progress == null || progress is AnalysisProgress.Completed) return
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = colors.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (gameId != null) Text("Партия №$gameId", style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
                Text(when (progress) {
                    is AnalysisProgress.Starting -> "Запуск анализа"
                    is AnalysisProgress.Failed -> "Ошибка анализа"
                    else -> "Анализ партии"
                }, style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (progress) {
                    is AnalysisProgress.Starting -> StartingContent()
                    is AnalysisProgress.InProgress -> InProgressContent(progress.currentMove, progress.totalMoves,
                        progress.currentMoveNotation)
                    is AnalysisProgress.Failed -> FailedContent(progress.error)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (progress is AnalysisProgress.Failed) {
                Button(onClick = onDismiss) { Text("Закрыть") }
            } else if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("Отменить анализ") }
            }
        }
    )
}

@Composable
private fun StartingContent() {
    Text("Инициализация движка…", style = MaterialTheme.typography.bodyLarge)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun InProgressContent(current: Int, total: Int, moveNotation: String) {
    val fraction = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
    Text("Ход ${(current + 1) / 2} из ${(total + 1) / 2}", style = MaterialTheme.typography.bodyLarge)
    if (moveNotation.isNotEmpty()) {
        Text(moveNotation, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FailedContent(error: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium) {
        Text(error, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
