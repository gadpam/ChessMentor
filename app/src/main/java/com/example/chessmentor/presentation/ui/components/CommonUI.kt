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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.TileMode
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

/** Скелетон для списка карточек (партий, задач, статистики). */
@Composable
fun ListSkeleton(
    itemCount: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(itemCount) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SkeletonBox(height = 20.dp, width = 200.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonBox(height = 14.dp, width = 120.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SkeletonBox(height = 14.dp, width = 80.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        SkeletonBox(height = 14.dp, width = 60.dp)
                    }
                }
            }
        }
    }
}

/** Стеклянная карточка с градиентной границей и мягкой тенью — визуальный акцент. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ),
        start = Alignment.TopStart,
        end = Alignment.BottomRight,
        tileMode = TileMode.Clamp
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush, RoundedCornerShape(16.dp))
                .alpha(0.12f)
        )
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** Кнопка с градиентом бренда — для главных CTA на экране. */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val gradientBrush = Brush.linearGradient(
        colors = if (enabled) {
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
        },
        start = Alignment.CenterStart,
        end = Alignment.CenterEnd
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush, RoundedCornerShape(12.dp))
                .alpha(if (enabled) 1f else 0.4f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

/** Выразительное пустое состояние с декоративным градиентным фоном и большой иконкой. */
@Composable
fun BrandEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalChessPalette.current
    val bgGradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.background.copy(alpha = 0.02f)
        ),
        start = Alignment.TopCenter,
        end = Alignment.BottomCenter
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgGradient),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        ),
                        start = Alignment.TopLeft,
                        end = Alignment.BottomRight
                    ),
                    CircleShape
                )
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        subtitle?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 56.dp),
                lineHeight = 22.sp
            )
        }

        if (onAction != null && actionLabel != null) {
            Spacer(modifier = Modifier.height(28.dp))
            BrandButton(
                onClick = onAction,
                label = actionLabel,
                modifier = Modifier.padding(horizontal = 32.dp).width(200.dp)
            )
        }
    }
}

/** Декоративный брендовый акцент — тонкая цветная линия для разделения секций. */
@Composable
fun BrandAccent(
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.1f)),
                    start = Alignment.CenterStart,
                    end = Alignment.CenterEnd
                )
            )
    )
}
