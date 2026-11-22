// presentation/ui/components/CommonUI.kt
package com.example.chessmentor.presentation.ui.components

import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.collapseHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** Унифицированное пустое состояние для экранов без данных. */
@Composable
fun EmptyState(
    icon: ImageVector = Icons.Default.AssignmentTurnedIn,
    title: String,
    subtitle: String? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalChessPalette.current
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(palette.accentBackground)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        subtitle?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        }

        if (onAction != null && actionLabel != null) {
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Унифицированная карточка сообщения (ошибка / информация / успех). */
@Composable
fun MessageCard(
    type: MessageType,
    title: String,
    message: String?,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalChessPalette.current
    val (color, icon) = when (type) {
        MessageType.Error -> palette.negative to Icons.Default.ErrorOutline
        MessageType.Success -> palette.positive to Icons.Default.CheckCircle
        MessageType.Warning -> palette.caution to Icons.Default.Warning
        MessageType.Info -> LocalChessPalette.current.run {
            Color(0xFF2196F3) to Icons.Default.Info
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                onDismiss?.let {
                    IconButton(onClick = it) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

enum class MessageType { Error, Success, Warning, Info }

/** Скелетон-заглушка для контента, который загружается. */
@Composable
fun SkeletonBox(
    height: Dp = 16.dp,
    width: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier
) {
    val shade = remember { Random.nextFloat() }
    Box(
        modifier = modifier
            .height(height)
            .width(width)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // shimmer effect placeholder — kept minimal to avoid heavy animation cost
    }
}

/** Ряд метрики: большое моноширинное число + подпись снизу. */
@Composable
fun MetricRow(
    value: String,
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Карточка метрики с иконкой слева и числом справа. */
@Composable
fun MetricCard(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Анимированное переключение контента между экранами (fade + вертикальный slide).
 *
 * [key] используется для определения момента смены контента — при изменении ключа
 * старый контент анимированно уходит, новый — появляется.
 */
@Composable
fun AnimatedContentSwitcher(
    key: Any?,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            fadeIn(animationSpec = tween(250)) +
                slideInVertically(
                    initialOffsetY = { introHeight -> introHeight * 0.15f },
                    animationSpec = tween(250)
                ) +
                slideOutVertically(
                    targetOffsetY = { targetHeight -> targetHeight * 0.15f },
                    animationSpec = tween(250)
                )
        }
    ) { targetState ->
        content()
    }
}
