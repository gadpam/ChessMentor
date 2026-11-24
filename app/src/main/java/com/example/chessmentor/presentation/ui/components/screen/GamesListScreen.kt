// presentation/ui/screen/GamesListScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.chessmentor.R
import kotlinx.coroutines.CoroutineScope
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.presentation.ui.components.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.ListSkeleton
import com.example.chessmentor.presentation.ui.components.MessageCard
import com.example.chessmentor.presentation.ui.components.MessageType
import com.example.chessmentor.presentation.ui.components.util.MISSING_ACCURACY
import com.example.chessmentor.presentation.ui.components.util.formatGameAccuracy
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
import com.example.chessmentor.presentation.ui.components.EngineStatusBanner
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import java.util.Locale

private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    ANALYZED("Разобраны"),
    PENDING("В очереди"),
    WINS("Победы"),
    LOSSES("Поражения"),
    DRAWS("Ничьи"),
    WHITE("Белые"),
    BLACK("Чёрные")
}

private enum class LibrarySort(val label: String) {
    NEWEST("Сначала новые"),
    OLDEST("Сначала старые"),
    ACCURACY_DESC("Точность ↓"),
    ACCURACY_ASC("Точность ↑"),
    MOST_BLUNDERS("Больше зевков")
}

private data class IndexedGame(
    val game: Game,
    val info: PgnParser.GameCardInfo
)

@Composable
fun GamesListScreen(
    paddingValues: PaddingValues,
    gameViewModel: GameViewModel,
    container: AppContainer,
    onGameClick: (Game) -> Unit,
    onNavigateToUpload: () -> Unit = {},
    onAnalysisComplete: () -> Unit = {},
    onOpenEngineSettings: (() -> Unit)? = null,
    initialSearchQuery: String? = null,
    initialColorFilter: ChessColor? = null,
    onInitialFilterConsumed: () -> Unit = {}
) {
    val games = gameViewModel.userGames.toList()
    val message by gameViewModel.message
    val isLoading by gameViewModel.isLoading

    val engineStatus by container.chessEngine.status.collectAsState()
    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    // Запрос живёт во ViewModel, а не в rememberSaveable: после пересоздания Activity
    // (поворот экрана с сохранением ViewModel) разбор не теряет «свою» партию и
    // диалог прогресса по-прежнему привязан к нужной карточке.
    val requestedGameId by gameViewModel.libraryAnalysisRequestedGameId

    var searchQuery by rememberSaveable { mutableStateOf(initialSearchQuery.orEmpty()) }
    var activeFilterName by rememberSaveable {
        mutableStateOf(
            when (initialColorFilter) {
                ChessColor.WHITE -> LibraryFilter.WHITE.name
                ChessColor.BLACK -> LibraryFilter.BLACK.name
                null -> LibraryFilter.ALL.name
            }
        )
    }
    var activeSortName by rememberSaveable { mutableStateOf(LibrarySort.NEWEST.name) }
    val activeFilter = LibraryFilter.entries.firstOrNull { it.name == activeFilterName } ?: LibraryFilter.ALL
    val activeSort = LibrarySort.entries.firstOrNull { it.name == activeSortName } ?: LibrarySort.NEWEST
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val showAllGames = {
        searchQuery = ""
        activeFilterName = LibraryFilter.ALL.name
        focusManager.clearFocus()
    }

    var searchOpen by rememberSaveable {
        mutableStateOf(!initialSearchQuery.isNullOrBlank() || initialColorFilter != null)
    }

    LaunchedEffect(initialSearchQuery, initialColorFilter) {
        if (!initialSearchQuery.isNullOrBlank() || initialColorFilter != null) {
            searchQuery = initialSearchQuery.orEmpty()
            activeFilterName = when (initialColorFilter) {
                ChessColor.WHITE -> LibraryFilter.WHITE.name
                ChessColor.BLACK -> LibraryFilter.BLACK.name
                null -> LibraryFilter.ALL.name
            }
            searchOpen = true
            onInitialFilterConsumed()
        }
    }

    // Кэшируем разбор метаданных карточек по (id, hash(pgn), color, timeControl, playedAt) — O(1) при прокрутке и поиске
    val infoCache = remember { mutableMapOf<String, PgnParser.GameCardInfo>() }
    val indexedGames = remember(games) {
        val activeKeys = HashSet<String>(games.size)
        val list = games.map { g ->
            val key = "${g.id}_${g.pgnData.hashCode()}_${g.playerColor}_${g.timeControl}_${g.playedAt}"
            activeKeys.add(key)
            val info = infoCache.getOrPut(key) { PgnParser.extractGameCardInfo(g) }
            IndexedGame(g, info)
        }
        infoCache.keys.retainAll(activeKeys)
        list
    }

    val filteredGames = remember(indexedGames, searchQuery, activeFilter, activeSort) {
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = indexedGames.filter { item ->
            val matchesFilter = when (activeFilter) {
                LibraryFilter.ALL -> true
                LibraryFilter.ANALYZED -> item.game.isAnalyzed()
                LibraryFilter.PENDING -> item.game.analysisStatus == AnalysisStatus.PENDING
                LibraryFilter.WINS -> item.info.outcome == PgnParser.PlayerOutcome.WIN
                LibraryFilter.LOSSES -> item.info.outcome == PgnParser.PlayerOutcome.LOSS
                LibraryFilter.DRAWS -> item.info.outcome == PgnParser.PlayerOutcome.DRAW
                LibraryFilter.WHITE -> item.game.playerColor == ChessColor.WHITE
                LibraryFilter.BLACK -> item.game.playerColor == ChessColor.BLACK
            }
            val matchesQuery = query.isEmpty() || query in item.info.searchBlob
            matchesFilter && matchesQuery
        }

        when (activeSort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.game.playedAt }
            LibrarySort.OLDEST -> filtered.sortedBy { it.game.playedAt }
            LibrarySort.ACCURACY_DESC -> filtered.sortedWith(
                compareByDescending<IndexedGame> { it.game.isAnalyzed() }
                    .thenByDescending { it.game.accuracy ?: -1.0 }
                    .thenByDescending { it.game.playedAt }
            )
            LibrarySort.ACCURACY_ASC -> filtered.sortedWith(
                compareByDescending<IndexedGame> { it.game.isAnalyzed() }
                    .thenBy { it.game.accuracy ?: 101.0 }
                    .thenByDescending { it.game.playedAt }
            )
            LibrarySort.MOST_BLUNDERS -> filtered.sortedWith(
                compareByDescending<IndexedGame> { it.game.isAnalyzed() }
                    .thenByDescending { it.game.blundersCount }
                    .thenByDescending { it.game.totalMistakes }
                    .thenByDescending { it.game.playedAt }
            )
        }
    }

    val chronological = activeSort == LibrarySort.NEWEST || activeSort == LibrarySort.OLDEST
    val duplicateKeys = remember(indexedGames) {
        indexedGames.groupingBy { it.info.title to it.info.dateLabel }.eachCount().filterValues { it > 1 }.keys
    }
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(
            top = 12.dp + paddingValues.calculateTopPadding(),
            bottom = 12.dp + paddingValues.calculateBottomPadding(),
            start = 16.dp + paddingValues.calculateStartPadding(layoutDirection),
            end = 16.dp + paddingValues.calculateEndPadding(layoutDirection)
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (indexedGames.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Партий: ${indexedGames.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = { searchOpen = !searchOpen; focusManager.clearFocus() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск и фильтры")
                }
                AddGameButton(onNavigateToUpload)
            }
        }
        // Живой прогресс/ошибка загрузки NNUE (скрывается автоматически при готовности Stockfish)
        if (engineStatus !is EngineStatus.Idle && engineStatus !is EngineStatus.Ready) {
            item {
                EngineStatusBanner(
                    status = engineStatus,
                    onRetry = { gameViewModel.retryEngineInit(forceRedownload = false) },
                    onCancel = { gameViewModel.cancelEngineInit() },
                    onOpenSettings = onOpenEngineSettings,
                    showWhenReady = false
                )
            }
        }

        if (message.isNotEmpty()) {
            item {
                MessageCard(
                    type = if (message.startsWith("Ошибка")) MessageType.Error else MessageType.Info,
                    title = if (message.startsWith("Ошибка")) "Ошибка" else "Информация",
                    message = message,
                    onDismiss = { gameViewModel.clearMessage() }
                )
            }
        }

        if (isLoading && indexedGames.isEmpty()) {
            item {
                ListSkeleton(itemCount = 3)
            }
        } else if (indexedGames.isEmpty()) {
            item {
                BrandEmptyState(
                    painter = painterResource(R.drawable.ic_empty_games),
                    title = "Партий пока нет",
                    subtitle = "Вставьте PGN из буфера или загрузите файл .pgn из Lichess / Chess.com",
                    onAction = onNavigateToUpload,
                    actionLabel = "Импортировать партии"
                )
            }
        } else {
            // Поиск + сортировка + быстрые фильтры
            if (searchOpen) item {
                LibrarySearchAndFilters(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    activeFilter = activeFilter,
                    onFilterChange = { activeFilterName = it.name },
                    activeSort = activeSort,
                    sortMenuExpanded = sortMenuExpanded,
                    onSortMenuToggle = { sortMenuExpanded = it },
                    onSortChange = {
                        activeSortName = it.name
                        sortMenuExpanded = false
                    },
                    shownCount = filteredGames.size,
                    totalCount = indexedGames.size,
                    onShowAll = showAllGames
                )
            }

            if (!searchOpen && (activeFilter != LibraryFilter.ALL || searchQuery.isNotBlank() || activeSort != LibrarySort.NEWEST)) item {
                TextButton(onClick = { searchOpen = true }) {
                    Text(listOfNotNull(searchQuery.takeIf { it.isNotBlank() }?.let { "«$it»" },
                        activeFilter.label.takeIf { activeFilter != LibraryFilter.ALL }, activeSort.label,
                        "${filteredGames.size} из ${games.size}").joinToString(" · "))
                }
            }
            if (filteredGames.isEmpty()) {
                item {
                    NoMatchingGamesCard(
                        query = searchQuery.trim(),
                        filter = activeFilter,
                        onShowAll = showAllGames
                    )
                }
            } else {
                itemsIndexed(
                    items = filteredGames,
                    key = { _, item -> item.game.id ?: item.game.hashCode() }
                ) { index, item ->
                    val game = item.game
                    if (chronological && filteredGames.getOrNull(index - 1)?.info?.dateLabel != item.info.dateLabel) {
                        Text(item.info.dateLabel, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }

                    GameCard(
                        game = game,
                        info = item.info,
                        showDate = !chronological,
                        showNumber = (item.info.title to item.info.dateLabel) in duplicateKeys,
                        isBusy = isLoading,
                        onClick = { onGameClick(game) },
                        onAnalyze = { id, retry ->
                            gameViewModel.analyzeGameWithProgress(
                                id,
                                forceReanalyze = retry,
                                requestedByLibrary = true
                            )
                        },
                        onDelete = {
                            game.id?.let { gameViewModel.deleteGame(it) }
                        }
                    )
                }
            }
        }
    }

    LibraryAnalysisFeedback(
        requestedGameId = requestedGameId,
        progress = analysisProgress,
        forgetRequest = gameViewModel::forgetLibraryAnalysisRequest,
        onDismiss = gameViewModel::clearAnalysisProgress,
        onCancel = gameViewModel::cancelAnalysis,
        onOpenSummary = onAnalysisComplete
    )
}

@Composable
internal fun LibraryAnalysisFeedback(
    requestedGameId: Long?,
    progress: AnalyzeGameUseCase.AnalysisProgress?,
    forgetRequest: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onOpenSummary: () -> Unit
) {
    LaunchedEffect(progress) {
        if (progress is AnalyzeGameUseCase.AnalysisProgress.Completed && requestedGameId != null) {
            val result = progress.result as? AnalyzeGameUseCase.Result.Success
            forgetRequest()
            // Завершённый разбор больше не занимает движок: гасим терминальное состояние,
            // иначе экран просмотра партии навсегда остался бы в режиме «движок занят».
            onDismiss()
            if (result?.game?.id == requestedGameId) onOpenSummary()
        }
    }
    AnalysisProgressDialog(
        progress = progress, gameId = requestedGameId,
        onDismiss = { forgetRequest(); onDismiss() },
        onCancel = { forgetRequest(); onCancel() }
    )
}

@Composable
internal fun AddGameButton(onAdd: () -> Unit) {
    TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.Add, contentDescription = "Добавить партию")
        Spacer(modifier = Modifier.width(4.dp))
        Text("Добавить")
    }
}

@Composable
private fun LibrarySearchAndFilters(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    activeFilter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    activeSort: LibrarySort,
    sortMenuExpanded: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    shownCount: Int,
    totalCount: Int,
    onShowAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Поиск партий") },
                placeholder = { Text("Соперник, дебют, ECO, контроль…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Поиск партий", modifier = Modifier.size(20.dp))
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить поиск")
                        }
                    }
                } else {
                    null
                }
            )

            Box {
                FilledTonalIconButton(
                    onClick = { onSortMenuToggle(true) },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { stateDescription = activeSort.label }
                ) {
                    Icon(Icons.Default.Sort, contentDescription = "Сортировка")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { onSortMenuToggle(false) }
                ) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            modifier = Modifier.semantics {
                                selected = option == activeSort
                                role = Role.RadioButton
                            },
                            text = { Text(option.label, style = MaterialTheme.typography.bodyLarge) },
                            trailingIcon = {
                                if (option == activeSort) Icon(Icons.Default.Check, contentDescription = "Выбрано",
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            onClick = { onSortChange(option) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = {
                        onFilterChange(if (activeFilter == filter) LibraryFilter.ALL else filter)
                    },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (activeFilter != LibraryFilter.ALL || searchQuery.isNotBlank() || activeSort != LibrarySort.NEWEST) {
            Text(
                text = "Показано: $shownCount из $totalCount · ${activeSort.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (shownCount > 0 && (activeFilter != LibraryFilter.ALL || searchQuery.isNotBlank())) {
            TextButton(onClick = onShowAll) { Text("Показать все партии") }
        }
    }
}

@Composable
private fun NoMatchingGamesCard(query: String, filter: LibraryFilter, onShowAll: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Совпадений нет",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildList {
                    if (query.isNotEmpty()) add("Запрос: «$query»")
                    if (filter != LibraryFilter.ALL) add("Фильтр: ${filter.label}")
                }.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onShowAll) {
                Text("Показать все партии")
            }
        }
    }
}

@Composable
fun GameCard(
    game: Game,
    info: PgnParser.GameCardInfo = remember(game) { PgnParser.extractGameCardInfo(game) },
    showDate: Boolean = false,
    showNumber: Boolean = false,
    isBusy: Boolean = false,
    onClick: () -> Unit,
    onAnalyze: ((Long, Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") snackbarHostState: SnackbarHostState? = null,
    @Suppress("UNUSED_PARAMETER") coroutineScope: CoroutineScope? = null
) {
    val id = game.id
    var menuOpen by rememberSaveable(id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val white = game.playerColor == ChessColor.WHITE
    val detail = listOfNotNull(id?.takeIf { showNumber }?.let { "№$it" },
        info.dateLabel.takeIf { showDate }, info.timeControlLabel).joinToString(" · ")
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.chessmentor.presentation.ui.components.ChessSideBadge(
                color = game.playerColor,
                size = 22.dp,
                semanticsDescription = if (white) "Вы белыми" else "Вы чёрными"
            )
            Column {
                Text(
                    text = info.title.removePrefix("vs "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        val outcome = when (info.outcome) {
            PgnParser.PlayerOutcome.WIN -> "+"
            PgnParser.PlayerOutcome.LOSS -> "−"
            PgnParser.PlayerOutcome.DRAW -> "="
            PgnParser.PlayerOutcome.UNKNOWN -> "—"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(outcome, Modifier.clearAndSetSemantics { contentDescription = info.outcome.displayName },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = when (info.outcome) {
                    PgnParser.PlayerOutcome.WIN -> MaterialTheme.colorScheme.primary
                    PgnParser.PlayerOutcome.LOSS -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            Spacer(modifier = Modifier.width(6.dp))
            Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
                when (game.analysisStatus) {
                    AnalysisStatus.COMPLETED -> {
                        val accuracy = game.accuracy?.let { formatGameAccuracy(it) }
                        Text(accuracy ?: "Готово", Modifier.clearAndSetSemantics {
                            contentDescription = accuracy?.let { "Точность: $it" } ?: MISSING_ACCURACY
                        }, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    AnalysisStatus.IN_PROGRESS -> Text(
                        text = "Идёт анализ",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AnalysisStatus.FAILED, AnalysisStatus.PENDING -> {
                        val failed = game.analysisStatus == AnalysisStatus.FAILED
                        if (onAnalyze != null) TextButton(
                            onClick = { id?.let { onAnalyze(it, failed) } }, enabled = !isBusy && id != null,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (failed) "Ошибка анализа" else "Ожидает анализа"
                            }, contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text(if (failed) "Повторить" else "Разобрать") }
                        else Text(
                            text = if (failed) "Ошибка анализа" else "Ожидает анализа",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (onDelete != null && id != null) Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия партии №$id")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Удалить партию") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = {
                            menuOpen = false
                            confirmDelete = true
                        }
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null && id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить партию №$id?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val summaryLine = listOfNotNull(
                        info.title.removePrefix("vs ").takeIf { it.isNotBlank() },
                        info.dateLabel.takeIf { it.isNotBlank() },
                        info.timeControlLabel,
                        info.outcome.displayName
                    ).joinToString(" · ")
                    if (summaryLine.isNotBlank()) {
                        Text(summaryLine, style = MaterialTheme.typography.bodyMedium)
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Отменить удаление нельзя",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Будут удалены локальная запись партии, её разбор Stockfish, связанные задачи и история попыток.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 2.dp)) {
                val stacked = maxWidth < (255 * LocalDensity.current.fontScale).dp
                if (stacked) {
                    Column { identity(); actions() }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { identity() }
                    actions()
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
