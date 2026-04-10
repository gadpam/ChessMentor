// presentation/ui/components/HorizontalMoveList.kt
package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType

/**
 * Горизонтальный скроллящийся список ходов с подсветкой ошибок
 *
 * Как в Chess.com Mobile — внизу экрана
 */
@Composable
fun HorizontalMoveList(
    moves: List<String>,
    currentMoveIndex: Int,
    mistakes: List<Mistake>,
    onMoveClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(currentMoveIndex) {
        if (currentMoveIndex >= 0 && moves.isNotEmpty()) {
            val scrollPosition = ((currentMoveIndex - 2) * 60).coerceAtLeast(0)  // Уменьшили с 70 до 60
            scrollState.animateScrollTo(scrollPosition)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 4.dp),  // Уменьшили с 8dp до 4dp
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Начальная позиция
        StartPositionChip(
            isSelected = currentMoveIndex == -1,
            onClick = { onMoveClick(-1) }
        )

        Spacer(modifier = Modifier.width(6.dp))  // Уменьшили с 8dp до 6dp

        moves.forEachIndexed { index, san ->
            val moveNumber = (index / 2) + 1
            val isWhiteMove = index % 2 == 0
            val isSelected = index == currentMoveIndex

            val mistake = mistakes.find {
                it.moveNumber == moveNumber &&
                        it.color == (if (isWhiteMove) ChessColor.WHITE else ChessColor.BLACK)
            }

            // Номер хода
            if (isWhiteMove) {
                Text(
                    text = "$moveNumber.",
                    fontSize = 12.sp,  // Уменьшили с 13sp до 12sp
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 2.dp)
                )
            }

            // Ход
            MoveChip(
                san = san,
                isSelected = isSelected,
                mistakeType = mistake?.mistakeType,
                onClick = { onMoveClick(index) }
            )

            Spacer(modifier = Modifier.width(4.dp))  // Было 6dp → стало 4dp
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
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)  // Уменьшили с 10dp×6dp до 8dp×4dp
    ) {
        Text(
            text = "▷",
            fontSize = 13.sp,  // Уменьшили с 14sp до 13sp
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
private fun MoveChip(
    san: String,
    isSelected: Boolean,
    mistakeType: MistakeType?,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected && mistakeType == MistakeType.BLUNDER -> Color(0xFFE53935)
        isSelected && mistakeType == MistakeType.MISTAKE -> Color(0xFFFF9800)
        isSelected && mistakeType == MistakeType.INACCURACY -> Color(0xFFFFC107)
        isSelected -> MaterialTheme.colorScheme.primary
        mistakeType == MistakeType.BLUNDER -> Color(0xFFFFCDD2)
        mistakeType == MistakeType.MISTAKE -> Color(0xFFFFE0B2)
        mistakeType == MistakeType.INACCURACY -> Color(0xFFFFF9C4)
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> Color.White
        mistakeType == MistakeType.BLUNDER -> Color(0xFFC62828)
        mistakeType == MistakeType.MISTAKE -> Color(0xFFE65100)
        mistakeType == MistakeType.INACCURACY -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val qualitySymbol = when (mistakeType) {
        MistakeType.BLUNDER -> "??"
        MistakeType.MISTAKE -> "?"
        MistakeType.INACCURACY -> "?!"
        else -> ""
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)  // Уменьшили с 8dp×6dp до 7dp×4dp
    ) {
        Text(
            text = "$san$qualitySymbol",
            fontSize = 13.sp,  // Уменьшили с 14sp до 13sp
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}
