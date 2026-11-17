// presentation/ui/components/CompactMistakeCard.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType

/**
 * Компактная карточка ошибки (уменьшенная версия)
 */
@Composable
fun CompactMistakeCard(
    mistake: Mistake,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (mistake.mistakeType) {
        MistakeType.BLUNDER -> Color(0xFFFFEBEE)
        MistakeType.MISTAKE -> Color(0xFFFFF3E0)
        MistakeType.INACCURACY -> Color(0xFFFFFDE7)
    }
    val accentColor = mistake.mistakeType.getColor()

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Заголовок: тип ошибки + потеря
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = mistake.mistakeType.getIcon(),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = mistake.mistakeType.getDisplayName(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• Ход ${mistake.moveNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                // Потеря оценки
                Text(
                    text = formatLoss(mistake),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ходы: сыграно vs лучше (более компактно)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color.White.copy(alpha = 0.6f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Сыгранный ход
                CompactMoveColumn(
                    label = "Сыграно",
                    move = mistake.userMove,
                    color = Color(0xFFD32F2F)
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )

                // Лучший ход
                CompactMoveColumn(
                    label = "Лучше",
                    move = mistake.bestMove,
                    color = Color(0xFF2E7D32)
                )
            }

            // Комментарий (если есть, показываем компактно)
            if (!mistake.comment.isNullOrBlank() && mistake.comment.length < 60) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = mistake.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun CompactMoveColumn(
    label: String,
    move: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color.Gray
        )
        Text(
            text = move,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun formatLoss(mistake: Mistake): String {
    return if (mistake.evaluationLoss > 50000) {
        "Мат!"
    } else {
        "-${String.format(java.util.Locale.US, "%.1f", mistake.getEvaluationLossInPawns())}"
    }
}

/**
 * Компактная карточка позиции (когда нет ошибки)
 */
@Composable
fun CompactPositionCard(
    evaluation: Int,
    playerColor: ChessColor,
    modifier: Modifier = Modifier
) {
    val playerEval = if (playerColor == ChessColor.WHITE) evaluation else -evaluation
    val (icon, text, color) = getPositionInfo(playerEval)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color,
                fontSize = 13.sp
            )
        }
    }
}

private fun getPositionInfo(playerEval: Int): Triple<ImageVector, String, Color> {
    return when {
        playerEval > 300 -> Triple(
            Icons.Default.CheckCircle,
            "Выигранная позиция",
            Color(0xFF2E7D32)
        )
        playerEval > 100 -> Triple(
            Icons.Default.TrendingUp,
            "Хорошая позиция",
            Color(0xFF4CAF50)
        )
        playerEval > 30 -> Triple(
            Icons.Default.Add,
            "Небольшое преимущество",
            Color(0xFF8BC34A)
        )
        playerEval < -300 -> Triple(
            Icons.Default.Warning,
            "Тяжёлая позиция",
            Color(0xFFC62828)
        )
        playerEval < -100 -> Triple(
            Icons.Default.TrendingDown,
            "Соперник лучше",
            Color(0xFFE53935)
        )
        playerEval < -30 -> Triple(
            Icons.Default.Remove,
            "Небольшой перевес у соперника",
            Color(0xFFFF9800)
        )
        else -> Triple(
            Icons.Default.Balance,
            "Равная позиция",
            Color(0xFF757575)
        )
    }
}