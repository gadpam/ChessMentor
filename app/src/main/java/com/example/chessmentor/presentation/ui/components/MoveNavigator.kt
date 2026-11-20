package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Панель навигации по ходам партии
 */
@Composable
fun MoveNavigator(
    currentMoveIndex: Int,
    totalMoves: Int,
    currentMove: String,
    onFirstMove: () -> Unit,
    onPreviousMove: () -> Unit,
    onNextMove: () -> Unit,
    onLastMove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Текущий ход
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ход ${currentMoveIndex + 1} из $totalMoves",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (currentMove.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = currentMove,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Прогресс-бар
            LinearProgressIndicator(
                progress = if (totalMoves > 0) (currentMoveIndex + 1).toFloat() / totalMoves else 0f,
                modifier = Modifier.fillMaxWidth()
            )

            // Кнопки навигации
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // К началу
                Button(
                    onClick = onFirstMove,
                    enabled = currentMoveIndex > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.FirstPage, contentDescription = "В начало партии")
                }

                // Назад
                IconButton(
                    onClick = onPreviousMove,
                    enabled = currentMoveIndex > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Назад",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Вперёд
                IconButton(
                    onClick = onNextMove,
                    enabled = currentMoveIndex < totalMoves - 1,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Вперёд",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // К концу
                Button(
                    onClick = onLastMove,
                    enabled = currentMoveIndex < totalMoves - 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.LastPage, contentDescription = "В конец партии")
                }
            }
        }
    }
}