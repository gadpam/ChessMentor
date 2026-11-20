// presentation/ui/screen/GamesListScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.presentation.ui.components.util.MISSING_ACCURACY
import com.example.chessmentor.presentation.ui.components.util.formatGameAccuracy
import com.example.chessmentor.domain.entity.Mistake
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
    val mistakesByGame = gameViewModel.mistakesByGameId
    val message by gameViewModel.message
    val isLoading by gameViewModel.isLoading

    val engineStatus by container.chessEngine.status.collectAsState()
    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    var requestedGameId by rememberSaveable { mutableStateOf<Long?>(null) }

    var searchQuery by remember { mutableStateOf(initialSearchQuery.orEmpty()) }
    var activeFilter by remember {
        mutableStateOf(
            when (initialColorFilter) {
                ChessColor.WHITE -> LibraryFilter.WHITE
                ChessColor.BLACK -> LibraryFilter.BLACK
                null -> LibraryFilter.ALL
            }
        )
    }
    var activeSort by remember { mutableStateOf(LibrarySort.NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val showAllGames = {
        searchQuery = ""
        activeFilter = LibraryFilter.ALL
        focusManager.clearFocus()
    }

    LaunchedEffect(initialSearchQuery, initialColorFilter) {
        if (!initialSearchQuery.isNullOrBlank() || initialColorFilter != null) {
            searchQuery = initialSearchQuery.orEmpty()
            activeFilter = when (initialColorFilter) {
                ChessColor.WHITE -> LibraryFilter.WHITE
                ChessColor.BLACK -> LibraryFilter.BLACK
                null -> LibraryFilter.ALL
            }
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Сводная шапка библиотеки (всего, W/D/L, разобрано, ср. точность)
        item {
            GamesLibraryHeader(indexedGames, onNavigateToUpload)
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
                GamesListMessage(
                    message = message,
                    onDismiss = { gameViewModel.clearMessage() }
                )
            }
        }

        if (indexedGames.isEmpty()) {
            item {
                EmptyGamesPlaceholder(onImportClick = onNavigateToUpload)
            }
        } else {
            // Поиск + сортировка + быстрые фильтры
            item {
                LibrarySearchAndFilters(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    activeFilter = activeFilter,
                    onFilterChange = { activeFilter = it },
                    activeSort = activeSort,
                    sortMenuExpanded = sortMenuExpanded,
                    onSortMenuToggle = { sortMenuExpanded = it },
                    onSortChange = {
                        activeSort = it
                        sortMenuExpanded = false
                    },
                    shownCount = filteredGames.size,
                    totalCount = indexedGames.size,
                    onShowAll = showAllGames
                )
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
                items(
                    items = filteredGames,
                    key = { item -> item.game.id ?: item.game.hashCode() }
                ) { item ->
                    val game = item.game
                    val gameMistakes = game.id?.let { mistakesByGame[it] }.orEmpty()

                    GameCard(
                        game = game,
                        info = item.info,
                        mistakes = gameMistakes,
                        isBusy = isLoading,
                        onClick = { onGameClick(game) },
                        onAnalyze = { id, retry ->
                            if (gameViewModel.analyzeGameWithProgress(id, forceReanalyze = retry)) {
                                requestedGameId = id
                            }
                        },
                        onDelete = { game.id?.let { gameViewModel.deleteGame(it) } }
                    )
                }
            }
        }
    }

    LibraryAnalysisFeedback(
        requestedGameId = requestedGameId,
        progress = analysisProgress,
        forgetRequest = { requestedGameId = null },
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
private fun GamesLibraryHeader(indexedGames: List<IndexedGame>, onAdd: () -> Unit) {
    val total = indexedGames.size
    val analyzedGames = indexedGames.filter { it.game.isAnalyzed() }
    val pendingCount = indexedGames.count { it.game.analysisStatus == AnalysisStatus.PENDING }
    val wins = indexedGames.count { it.info.outcome == PgnParser.PlayerOutcome.WIN }
    val draws = indexedGames.count { it.info.outcome == PgnParser.PlayerOutcome.DRAW }
    val losses = indexedGames.count { it.info.outcome == PgnParser.PlayerOutcome.LOSS }
    val hasResults = wins + draws + losses > 0
    val avgAccuracy = analyzedGames
        .mapNotNull { it.game.accuracy }
        .takeIf { it.isNotEmpty() }
        ?.average()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Библиотека партий", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (total > 0) AddGameButton(onAdd)
            }
            if (avgAccuracy != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "Ср. %.1f%%", avgAccuracy),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            val statsLine = buildList {
                add("Всего: $total")
                add("разобрано: ${analyzedGames.size}")
                if (pendingCount > 0) add("в очереди: $pendingCount")
                if (hasResults) add("+$wins =$draws −$losses")
            }.joinToString(" · ")

            Text(
                text = statsLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
internal fun AddGameButton(onAdd: () -> Unit) {
    TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.Add, contentDescription = null)
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
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить поиск")
                        }
                    }
                } else {
                    null
                }
            )

            Box {
                FilledTonalIconButton(onClick = { onSortMenuToggle(true) },
                    modifier = Modifier.semantics { stateDescription = activeSort.label }) {
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
                                if (option == activeSort) Icon(Icons.Default.Check, contentDescription = null,
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
private fun GamesListMessage(
    message: String,
    onDismiss: () -> Unit
) {
    val isError = message.startsWith("Ошибка")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            )
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Clear, contentDescription = "Закрыть сообщение")
            }
        }
    }
}

@Composable
private fun EmptyGamesPlaceholder(onImportClick: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Inbox, null, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Партий пока нет",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Вставьте PGN из буфера или загрузите файл .pgn из Lichess / Chess.com",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onImportClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импортировать партии")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameCard(
    game: Game,
    info: PgnParser.GameCardInfo = remember(game.id, game.pgnData, game.playerColor, game.timeControl, game.playedAt) {
        PgnParser.extractGameCardInfo(game)
    },
    @Suppress("unused") mistakes: List<Mistake> = emptyList(),
    isBusy: Boolean = false,
    onClick: () -> Unit,
    onAnalyze: (Long, Boolean) -> Unit = { _, _ -> },
    onDelete: () -> Unit
) {
    val id = game.id
    var showDeleteDialog by remember(id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Имени доступна вся ширина между индикатором стороны и корзиной.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )

                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = info.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (id != null) IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить партию №$id",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (info.outcome != PgnParser.PlayerOutcome.UNKNOWN) {
                OutcomeChip(info.outcome)
            } else {
                StatusChip(game.analysisStatus)
            }
            Spacer(Modifier.height(4.dp))
            // Контроль · ходы · дата · часы · платформа
            val subtitle = buildList {
                id?.let { add("№$it") }
                add(info.timeControlLabel)
                if (info.fullMoveCount > 0) add("${info.fullMoveCount} х.")
                if (info.dateLabel.isNotBlank()) add(info.dateLabel)
                if (info.hasClockData) add("С часами")
                if (info.platform != PgnParser.GamePlatform.OTHER) {
                    add(info.platform.displayName)
                }
            }.joinToString(" · ")

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Строка дебюта (если определён)
            if (!info.openingLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Дебют: ${info.openingLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))

            // Нижний блок: метрики анализа либо кнопка запуска анализа в 1 нажатие
            when (game.analysisStatus) {
                AnalysisStatus.COMPLETED -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AccuracyBadge(
                            accuracy = game.accuracy,
                            acpl = game.averageEvaluationLoss
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MistakeCountBadge(
                                emoji = "??",
                                label = "Зевки",
                                count = game.blundersCount,
                                activeColor = MaterialTheme.colorScheme.error
                            )
                            MistakeCountBadge(
                                emoji = "?",
                                label = "Ошибки",
                                count = game.mistakesCount,
                                activeColor = MaterialTheme.colorScheme.secondary
                            )
                            MistakeCountBadge(
                                emoji = "?!",
                                label = "Неточн.",
                                count = game.inaccuraciesCount,
                                activeColor = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                AnalysisStatus.PENDING -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Ещё не проанализирована",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = { id?.let { onAnalyze(it, false) } },
                            enabled = !isBusy && id != null,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Разобрать партию", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                AnalysisStatus.IN_PROGRESS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Выполняется анализ Stockfish...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                AnalysisStatus.FAILED -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Анализ не завершился",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { id?.let { onAnalyze(it, true) } },
                            enabled = !isBusy && id != null,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Повторить анализ", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && id != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить партию №$id?") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(info.dateLabel, info.timeControlLabel, info.outcome.displayName)
                        .filter { it.isNotBlank() }.joinToString(" · "))
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Отменить удаление нельзя", style = MaterialTheme.typography.titleMedium)
                            Text("Будут удалены партия и её разбор, а также связанные с ней задачи и попытки их решения.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun OutcomeChip(outcome: PgnParser.PlayerOutcome) {
    val colors = MaterialTheme.colorScheme
    when (outcome) {
        PgnParser.PlayerOutcome.WIN -> LibraryBadge("Победа", colors.primaryContainer, colors.onPrimaryContainer)
        PgnParser.PlayerOutcome.LOSS -> LibraryBadge("Поражение", colors.errorContainer, colors.onErrorContainer)
        PgnParser.PlayerOutcome.DRAW -> LibraryBadge("Ничья", colors.secondaryContainer, colors.onSecondaryContainer)
        PgnParser.PlayerOutcome.UNKNOWN -> Unit // The card shows the analysis status instead.
    }
}

@Composable
fun StatusChip(status: AnalysisStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        AnalysisStatus.COMPLETED -> LibraryBadge("Готово", colors.primaryContainer, colors.onPrimaryContainer)
        AnalysisStatus.IN_PROGRESS -> LibraryBadge("Анализ", colors.tertiaryContainer, colors.onTertiaryContainer)
        AnalysisStatus.PENDING -> LibraryBadge("В очереди", colors.secondaryContainer, colors.onSecondaryContainer)
        AnalysisStatus.FAILED -> LibraryBadge("Ошибка", colors.errorContainer, colors.onErrorContainer)
    }
}

@Composable
private fun LibraryBadge(label: String, fill: Color, ink: Color) {
    Surface(color = fill, contentColor = ink, shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun AccuracyBadge(
    accuracy: Double?,
    acpl: Int? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        if (accuracy != null) {
            Text(
                text = "Точность: ${formatGameAccuracy(accuracy)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(MISSING_ACCURACY, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (acpl != null) {
            Text("Ср. потеря: $acpl сП", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MistakeCountBadge(
    emoji: String,
    label: String,
    count: Int,
    activeColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
