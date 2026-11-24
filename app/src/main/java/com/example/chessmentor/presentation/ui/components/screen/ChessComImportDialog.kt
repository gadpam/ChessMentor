package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chessmentor.data.chesscom.ArchiveGame
import com.example.chessmentor.data.chesscom.ChessComArchive
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CHESSCOM_PAGE_SIZE = 10

@OptIn(ExperimentalLayoutApi::class)
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
    var timeFilter by remember(source, period) { mutableStateOf<String?>(null) }
    var visibleLimit by remember(source, period, timeFilter) { mutableIntStateOf(CHESSCOM_PAGE_SIZE) }
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
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(16.dp)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Партии с Chess.com", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Публичный архив завершённых партий. Пароль не нужен. ChessMentor не связан с Chess.com.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    if (source == null) {
                        val canSubmitNick = ChessComArchive.nickname.matches(name.trim())
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Ник на Chess.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { if (canSubmitNick) source = name.trim() }
                            )
                        )
                        Button(
                            onClick = { source = name.trim() },
                            enabled = canSubmitNick,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Загрузить архив")
                        }
                    } else {
                        Text("Игрок: $source", style = MaterialTheme.typography.titleMedium)
                    }
                    if (source != null || remembered != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (source != null) {
                                TextButton(
                                    onClick = { source = null },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Сменить ник") }
                            }
                            if (remembered != null) {
                                TextButton(
                                    onClick = { source = null; name = ""; remembered = null; rememberSource(null) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Забыть ник")
                                }
                            }
                        }
                    }
                    val month = period
                    if (month != null) {
                        HorizontalDivider()
                        Text("Месяц: $month", style = MaterialTheme.typography.titleMedium)
                        val index = months.indexOf(month)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { period = months[index + 1] },
                                enabled = index >= 0 && index < months.lastIndex,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Раньше") }
                            TextButton(
                                onClick = { period = months[index - 1] },
                                enabled = index > 0,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Позже") }
                        }
                    }
                    when {
                        busy -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Загружаем публичный архив…", style = MaterialTheme.typography.bodyMedium)
                        }
                        error != null -> Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = colors.errorContainer,
                            contentColor = colors.onErrorContainer
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Архив не загружен", style = MaterialTheme.typography.titleMedium)
                                Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = { retry++ },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)
                                ) { Text("Повторить") }
                            }
                        }
                        source == null -> Unit
                        month == null -> Text("В публичном архиве пока нет партий.")
                        games.isEmpty() -> Text("За этот месяц нет доступных партий в обычные шахматы.")
                        else -> {
                            val availableClasses = remember(games) {
                                listOf("rapid", "blitz", "bullet", "daily").filter { tc ->
                                    games.any { it.timeClass == tc }
                                }
                            }
                            if (availableClasses.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = timeFilter == null,
                                        onClick = { timeFilter = null },
                                        label = { Text("Все (${games.size})") }
                                    )
                                    for (tc in availableClasses) {
                                        val label = ChessComArchive.localizeTimeClass(tc) ?: tc
                                        val count = games.count { it.timeClass == tc }
                                        FilterChip(
                                            selected = timeFilter == tc,
                                            onClick = { timeFilter = if (timeFilter == tc) null else tc },
                                            label = { Text("$label ($count)") }
                                        )
                                    }
                                }
                            }
                            val filtered = remember(games, timeFilter) {
                                val f = timeFilter
                                if (f == null) games else games.filter { it.timeClass == f }
                            }
                            val displayed = remember(filtered, visibleLimit) {
                                filtered.take(visibleLimit)
                            }
                            Text(
                                text = if (filtered.size > displayed.size) {
                                    "Показано ${displayed.size} из ${filtered.size} партий за месяц. Выберите нужные."
                                } else {
                                    "Партий за месяц: ${filtered.size}. Выберите нужные."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            for (game in displayed) {
                                val checked = game.fingerprint in selected
                                val fill = if (checked) colors.primaryContainer else colors.surfaceVariant
                                val ink = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant
                                val tcBadge = ChessComArchive.localizeTimeClass(game.timeClass)?.let { " · $it" }.orEmpty()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(fill)
                                        .toggleable(
                                            value = checked,
                                            enabled = !game.inLibrary,
                                            role = Role.Checkbox,
                                            onValueChange = {
                                                selected = if (it) selected + game.fingerprint else selected - game.fingerprint
                                            }
                                        )
                                        .heightIn(min = 48.dp)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked, onCheckedChange = null, enabled = !game.inLibrary)
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(game.opponent, style = MaterialTheme.typography.titleMedium, color = ink)
                                        Text(
                                            "${dateFormat.format(Instant.ofEpochSecond(game.ended))} · ${game.side.getDisplayName()} · ${game.outcome}$tcBadge",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ink
                                        )
                                        if (game.inLibrary) {
                                            Text("Уже в библиотеке", style = MaterialTheme.typography.labelMedium, color = ink)
                                        }
                                    }
                                }
                                game.libraryId?.let { id ->
                                    TextButton(
                                        onClick = { onOpenSaved(id) },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text("Открыть партию №$id") }
                                }
                            }
                            if (filtered.size > displayed.size) {
                                val remaining = filtered.size - displayed.size
                                val nextStep = minOf(CHESSCOM_PAGE_SIZE, remaining)
                                OutlinedButton(
                                    onClick = { visibleLimit += CHESSCOM_PAGE_SIZE },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("Показать ещё $nextStep (осталось $remaining)")
                                }
                            }
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { onImport(games.filter { it.fingerprint in selected && !it.inLibrary }) },
                        enabled = !busy && selected.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Импортировать выбранные (${selected.size})")
                    }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}
