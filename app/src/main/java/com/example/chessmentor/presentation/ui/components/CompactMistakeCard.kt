// presentation/ui/components/CompactMistakeCard.kt
package com.example.chessmentor.presentation.ui.components

import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.analysis.GameReviewHelper
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.util.ChessMoveUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Интерактивная карточка ошибки игрока на экране разбора партии:
 * - читабельна и в светлой, и в тёмной теме;
 * - автоматически переводит UCI в SAN по [fenBefore];
 * - показывает полное тренерское пояснение и пометку о цейтноте;
 * - отделяет самостоятельную попытку от просмотра ответа и линии Stockfish.
 */
@Composable
fun CompactMistakeCard(
    mistake: Mistake,
    modifier: Modifier = Modifier,
    moveQuality: MoveQuality? = null,
    fenBefore: String? = mistake.fenBefore,
    clockSeconds: Int? = null,
    onRevealAnswer: (() -> Unit)? = null,
    onRetryMove: (() -> Unit)? = null,
    onShowEngineLine: (() -> Unit)? = null,
    onPracticeInTrainer: (() -> Unit)? = null
) {
    val accentColor = LocalChessPalette.current.run { moveQuality?.let { accent(it) } ?: accent(mistake.mistakeType) }
    val title = moveQuality?.getDisplayName() ?: mistake.mistakeType.getDisplayName()

    val effectiveFen = fenBefore ?: mistake.fenBefore
    val playedSan = remember(mistake.userMove, effectiveFen) {
        toDisplaySan(mistake.userMove, effectiveFen)
    }
    val bestSan = remember(mistake.bestMove, effectiveFen) {
        toDisplaySan(mistake.bestMove, effectiveFen)
    }
    val inTimeTrouble = GameReviewHelper.isTimeTrouble(clockSeconds)

    Card(
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.11f)),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Заголовок: качество + номер хода + цейтнот + потеря
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = mistake.mistakeType.getIcon(),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "· Ход ${mistake.moveNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (inTimeTrouble && clockSeconds != null) {
                        Text(
                            text = "· Осталось: ${GameReviewHelper.formatClock(clockSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalChessPalette.current.negative
                        )
                    }
                }

                Text(
                    text = formatLoss(mistake),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            // Сравнение: сыграно → лучше
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactMovePair(
                    label = "Сыграно",
                    move = playedSan,
                    color = LocalChessPalette.current.negative
                )
                if (onRevealAnswer == null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    CompactMovePair(
                        label = "Лучше",
                        move = bestSan,
                        color = LocalChessPalette.current.positive
                    )
                }
            }

            // Полное тренерское пояснение
            val explanation = mistake.comment?.trim()?.takeIf { onRevealAnswer == null && it.isNotEmpty() }
            if (explanation != null) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    lineHeight = 16.sp
                )
            }

            MistakeReviewActions(onRetryMove, onRevealAnswer, onShowEngineLine, onPracticeInTrainer)
        }
    }
}

/** Both review cards use the same order and only expose a continuation after disclosure. */
@Composable
internal fun MistakeReviewActions(
    onRetryMove: (() -> Unit)?,
    onRevealAnswer: (() -> Unit)?,
    onShowEngineLine: (() -> Unit)?,
    onPracticeInTrainer: (() -> Unit)?
) {
    val action = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (onRetryMove != null) {
        Button(onClick = onRetryMove, modifier = action) { Text("Попробовать снова") }
    }
    if (onRevealAnswer != null) {
        OutlinedButton(onClick = onRevealAnswer, modifier = action) { Text("Показать лучший ход") }
    } else if (onShowEngineLine != null) {
        OutlinedButton(onClick = onShowEngineLine, modifier = action) { Text("Вариант до ошибки") }
    }
    if (onPracticeInTrainer != null) {
        TextButton(onClick = onPracticeInTrainer, modifier = action) { Text("В тренажёр") }
    }
}

@Composable
private fun CompactMovePair(
    label: String,
    move: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = move,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun toDisplaySan(move: String, fenBefore: String?): String {
    val trimmed = move.trim()
    if (trimmed.isEmpty()) return "—"
    if (!fenBefore.isNullOrBlank() && ChessMoveUtils.isUciMove(trimmed)) {
        return ChessMoveUtils.uciToSan(trimmed, fenBefore)
    }
    return trimmed
}

private fun formatLoss(mistake: Mistake): String {
    return if (ChessEngine.isMateScore(mistake.evaluationLoss) || abs(mistake.evaluationLoss) > 50_000) {
        "-Мат"
    } else {
        String.format(Locale.US, "-%.1f", mistake.getEvaluationLossInPawns())
    }
}

/**
 * Компактная карточка текущей позиции (стартовая позиция или ход без аннотации анализа).
 */
@Composable
fun CompactPositionCard(
    evaluation: Int,
    playerColor: ChessColor,
    modifier: Modifier = Modifier,
    openingLabel: String? = null,
    subtitle: String? = null,
    onAnalyzePosition: (() -> Unit)? = null
) {
    val playerEval = if (playerColor == ChessColor.WHITE) evaluation else -evaluation
    val (icon, text, color) = getPositionInfo(playerEval)

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                val infoLine = openingLabel?.let { "Дебют: $it" } ?: subtitle
                if (!infoLine.isNullOrBlank()) {
                    Text(
                        text = infoLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onAnalyzePosition != null) {
                OutlinedButton(
                    onClick = onAnalyzePosition,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stockfish", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun getPositionInfo(playerEval: Int): Triple<ImageVector, String, Color> {
    return when {
        ChessEngine.isMateScore(playerEval) && playerEval > 0 -> Triple(
            Icons.Default.EmojiEvents,
            "Форсированный мат в вашу пользу",
            LocalChessPalette.current.positive
        )
        ChessEngine.isMateScore(playerEval) && playerEval < 0 -> Triple(
            Icons.Default.Warning,
            "Угроза форсированного мата",
            LocalChessPalette.current.negative
        )
        playerEval > 300 -> Triple(
            Icons.Default.CheckCircle,
            "Выигранная позиция",
            LocalChessPalette.current.positive
        )
        playerEval > 100 -> Triple(
            Icons.Default.TrendingUp,
            "Устойчивый перевес",
            LocalChessPalette.current.positive
        )
        playerEval > 35 -> Triple(
            Icons.Default.Add,
            "Небольшое преимущество",
            LocalChessPalette.current.positive
        )
        playerEval < -300 -> Triple(
            Icons.Default.Warning,
            "Тяжёлая позиция",
            LocalChessPalette.current.negative
        )
        playerEval < -100 -> Triple(
            Icons.Default.TrendingDown,
            "Перевес у соперника",
            LocalChessPalette.current.negative
        )
        playerEval < -35 -> Triple(
            Icons.Default.Remove,
            "Небольшой перевес у соперника",
            LocalChessPalette.current.caution
        )
        else -> Triple(
            Icons.Default.Balance,
            "Примерное равенство",
            LocalChessPalette.current.neutral
        )
    }
}
