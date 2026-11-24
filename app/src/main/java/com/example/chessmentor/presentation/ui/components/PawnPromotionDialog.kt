package com.example.chessmentor.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side

/**
 * Единый прокручиваемый диалог выбора фигуры при превращении пешки
 * (используется и на экране разбора партии, и в тактическом тренажёре).
 */
@Composable
fun PawnPromotionDialog(
    side: Side,
    onSelect: (PieceType) -> Unit,
    onDismiss: () -> Unit
) {
    val sideLabel = if (side == Side.WHITE) "белых" else "чёрных"
    val options = remember(side) {
        if (side == Side.WHITE) {
            listOf(
                Triple(PieceType.QUEEN, "♕", "Ферзь"),
                Triple(PieceType.ROOK, "♖", "Ладья"),
                Triple(PieceType.BISHOP, "♗", "Слон"),
                Triple(PieceType.KNIGHT, "♘", "Конь")
            )
        } else {
            listOf(
                Triple(PieceType.QUEEN, "♛", "Ферзь"),
                Triple(PieceType.ROOK, "♜", "Ладья"),
                Triple(PieceType.BISHOP, "♝", "Слон"),
                Triple(PieceType.KNIGHT, "♞", "Конь")
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Превращение пешки") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (pieceType, symbol, title) ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .semantics {
                                contentDescription = "Превратить пешку $sideLabel в: $title"
                            }
                            .clickable(role = Role.Button) {
                                onSelect(pieceType)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$symbol $title", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Отмена")
            }
        }
    )
}
