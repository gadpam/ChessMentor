package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.chessmentor.data.chesscom.ArchiveGame
import com.example.chessmentor.data.chesscom.ChessComArchive
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ChessComImportDialog(
    savedName: String?, rememberSource: (String?) -> Unit,
    monthsFor: suspend (String) -> List<String>, gamesFor: suspend (String, String) -> List<ArchiveGame>,
    onOpenSaved: (Long) -> Unit, onImport: (List<ArchiveGame>) -> Unit, onClose: () -> Unit
) {
    var name by remember { mutableStateOf(savedName.orEmpty()) }
    var source by remember { mutableStateOf(savedName) }
    var remembered by remember { mutableStateOf(savedName) }
    var months by remember(source) { mutableStateOf(emptyList<String>()) }
    var period by remember(source) { mutableStateOf<String?>(null) }
    var games by remember(source, period) { mutableStateOf(emptyList<ArchiveGame>()) }
    var selected by remember(source, period) { mutableStateOf(emptySet<String>()) }
    var busy by remember(source, period) { mutableStateOf(source != null) }
    var error by remember(source, period) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()) }

    LaunchedEffect(source, period, retry) {
        val nick = source ?: return@LaunchedEffect
        val month = period
        busy = true
        error = null
        try {
            if (month == null) {
                months = monthsFor(nick)
                rememberSource(nick)
                remembered = nick
                period = months.firstOrNull()
            } else {
                games = gamesFor(nick, month)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is JSONException) "Не удалось прочитать ответ Chess.com." else e.localizedMessage ?: "Ошибка загрузки архива."
        } finally {
            busy = false
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Партии с Chess.com", style = MaterialTheme.typography.headlineSmall)
                Text("Публичный архив завершённых партий. Пароль не нужен. ChessMentor не связан с Chess.com.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (source == null) {
                    OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it }, label = { Text("Ник на Chess.com") }, singleLine = true)
                    Button(onClick = { source = name.trim() }, enabled = ChessComArchive.nickname.matches(name.trim())) {
                        Text("Загрузить архив")
                    }
                } else {
                    Text("Игрок: $source", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { source = null }) { Text("Сменить ник") }
                }
                if (remembered != null) {
                    TextButton(onClick = { source = null; name = ""; remembered = null; rememberSource(null) }) { Text("Забыть ник") }
                }
                val month = period
                if (month != null) {
                    HorizontalDivider()
                    Text("Месяц: $month", style = MaterialTheme.typography.titleMedium)
                    val index = months.indexOf(month)
                    Row {
                        TextButton(onClick = { period = months[index + 1] }, enabled = index >= 0 && index < months.lastIndex) { Text("Раньше") }
                        TextButton(onClick = { period = months[index - 1] }, enabled = index > 0) { Text("Позже") }
                    }
                }
                when {
                    busy -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Загружаем публичный архив…", style = MaterialTheme.typography.bodyMedium)
                    }
                    error != null -> Surface(shape = MaterialTheme.shapes.medium,
                        color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Архив не загружен", style = MaterialTheme.typography.titleMedium)
                            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { retry++ }, colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.onErrorContainer)) { Text("Повторить") }
                        }
                    }
                    source == null -> Unit
                    month == null -> Text("В публичном архиве пока нет партий.")
                    games.isEmpty() -> Text("За этот месяц нет доступных партий в обычные шахматы.")
                    else -> {
                        Text("До 10 последних партий за месяц. Выберите нужные. Новые партии могут появляться с задержкой.")
                        for (game in games) {
                            val checked = game.fingerprint in selected
                            val fill = if (checked) colors.primaryContainer else colors.surfaceVariant
                            val ink = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(fill).toggleable(value = checked, enabled = !game.inLibrary,
                                role = Role.Checkbox, onValueChange = { selected = if (it) selected + game.fingerprint else selected - game.fingerprint })
                                .heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null, enabled = !game.inLibrary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(game.opponent, style = MaterialTheme.typography.titleMedium, color = ink)
                                    Text("${dateFormat.format(Instant.ofEpochSecond(game.ended))} · ${game.side.getDisplayName()} · ${game.outcome}", style = MaterialTheme.typography.bodySmall, color = ink)
                                    if (game.inLibrary) Text("Уже в библиотеке", style = MaterialTheme.typography.labelMedium, color = ink)
                                }
                            }
                            game.libraryId?.let { id ->
                                TextButton(onClick = { onOpenSaved(id) }) { Text("Открыть партию №$id") }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onImport(games.filter { it.fingerprint in selected && !it.inLibrary }) },
                    enabled = !busy && selected.isNotEmpty()
                ) {
                    Text("Импортировать выбранные (${selected.size})")
                }
                TextButton(onClick = onClose) {
                    Text("Закрыть")
                }
            }
        }
