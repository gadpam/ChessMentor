// presentation/ui/components/HorizontalMoveList.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette

/**
 * Горизонтальная лента ходов разбора партии на базе `LazyRow`.
 */
@Composable
fun HorizontalMoveList(
    moves: List<String>,
    currentMoveIndex: Int,
    mistakes: List<Mistake>,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    analyzedMoves: List<AnalyzedMove> = emptyList(),
    startMoveNumber: Int = 1,
    firstMoveIsWhite: Boolean = true
) {
    val listState = rememberLazyListState()

    val qualityByPly = remember(analyzedMoves, mistakes, moves.size, startMoveNumber, firstMoveIsWhite) {
        val map = HashMap<Int, MoveQuality>(analyzedMoves.size + mistakes.size)
        for (am in analyzedMoves) {
            map[am.moveIndex] = am.quality
        }
        if (map.isEmpty() && mistakes.isNotEmpty()) {
            for (idx in moves.indices) {
                val isWhite = if (firstMoveIsWhite) idx % 2 == 0 else idx % 2 != 0
                val moveNo = startMoveNumber + ((idx + if (firstMoveIsWhite) 0 else 1) / 2)
                val color = if (isWhite) ChessColor.WHITE else ChessColor.BLACK
                val m = mistakes.find { it.moveNumber == moveNo && it.color == color }
                if (m != null) {
                    map[idx] = when (m.mistakeType) {
                        MistakeType.BLUNDER -> MoveQuality.BLUNDER
                        MistakeType.MISTAKE -> MoveQuality.MISTAKE
                        MistakeType.INACCURACY -> MoveQuality.INACCURACY
                    }
                }
            }
        }
        map
    }

    LaunchedEffect(currentMoveIndex, moves.size) {
        val targetListIndex = (currentMoveIndex + 1).coerceIn(0, moves.size)
        val scrollTarget = (targetListIndex - 2).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(scrollTarget) }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item(key = "start_pos") {
            StartPositionChip(
                isSelected = currentMoveIndex == -1,
                onClick = { onMoveClick(-1) }
            )
        }

        itemsIndexed(
            items = moves,
            key = { index, _ -> index }
        ) { index, san ->
            val isWhiteMove = if (firstMoveIsWhite) index % 2 == 0 else index % 2 != 0
            val moveNumber = startMoveNumber + ((index + if (firstMoveIsWhite) 0 else 1) / 2)
            val isSelected = index == currentMoveIndex
            val quality = qualityByPly[index]

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isWhiteMove) {
                    Text(
                        text = "$moveNumber.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                    )
                } else if (index == 0) {
                    Text(
                        text = "$moveNumber...",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }

                MoveQualityChip(
                    san = san,
                    moveNumber = moveNumber,
                    isWhiteMove = isWhiteMove,
                    isSelected = isSelected,
                    quality = quality,
                    onClick = { onMoveClick(index) }
                )
            }
        }
    }
}

@Composable
private fun StartPositionChip(
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                selected = isSelected
                contentDescription = "Начальная позиция"
                if (isSelected) stateDescription = "Выбрана"
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▷",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

@Composable
private fun MoveQualityChip(
    san: String,
    moveNumber: Int,
    isWhiteMove: Boolean,
    isSelected: Boolean,
    quality: MoveQuality?,
    onClick: () -> Unit
) {
    val badgeSymbol = when (quality) {
        MoveQuality.BRILLIANT -> "!!"
        MoveQuality.GREAT_MOVE -> "!"
        MoveQuality.BLUNDER -> "??"
        MoveQuality.MISTAKE, MoveQuality.MISSED_WIN -> "?"
        MoveQuality.INACCURACY -> "?!"
        else -> ""
    }
    val qualityDescription = when (quality) {
        MoveQuality.BRILLIANT -> ", блестящий ход"
        MoveQuality.GREAT_MOVE -> ", сильный ход"
        MoveQuality.BLUNDER -> ", зевок"
        MoveQuality.MISTAKE -> ", ошибка"
        MoveQuality.MISSED_WIN -> ", упущенный шанс"
        MoveQuality.INACCURACY -> ", неточность"
        else -> ""
    }
    val spokenMove = "${moveNumber}${if (isWhiteMove) "." else "..."} $san$qualityDescription"

    val accent = when (quality) {
        MoveQuality.BRILLIANT,
        MoveQuality.GREAT_MOVE,
        MoveQuality.BLUNDER,
        MoveQuality.MISSED_WIN,
        MoveQuality.MISTAKE,
        MoveQuality.INACCURACY -> LocalChessPalette.current.accent(quality)
        else -> null
    }

    val backgroundColor = when {
        isSelected && accent != null -> accent
        isSelected -> MaterialTheme.colorScheme.primary
        accent != null -> accent.copy(alpha = 0.16f)
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected && accent != null -> LocalChessPalette.current.onAccent
        isSelected -> MaterialTheme.colorScheme.onPrimary
        accent != null -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                selected = isSelected
                contentDescription = spokenMove
                if (isSelected) stateDescription = "Выбран"
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(backgroundColor)
                .then(
                    if (!isSelected && accent != null) {
                        Modifier.border(1.dp, accent.copy(alpha = 0.35f), shape)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = "$san$badgeSymbol",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected || accent != null) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                color = textColor
            )
        }
    }
}
