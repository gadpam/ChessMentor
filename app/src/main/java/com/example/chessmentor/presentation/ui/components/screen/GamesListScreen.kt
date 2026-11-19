// presentation/ui/screen/GamesListScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
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
    WHITE("⚪ Белые"),
    BLACK("⚫ Чёрные")
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
    onOpenEngineSettings: (() -> Unit)? = null
) {
    val games = gameViewModel.userGames.toList()
    val mistakesByGame = gameViewModel.mistakesByGameId
    val message by gameViewModel.message
    val isLoading by gameViewModel.isLoading

    val engineStatus by container.chessEngine.status.collectAsState()
    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    val currentOnAnalysisComplete by rememberUpdatedState(onAnalysisComplete)

    // Если пользователь запустил анализ прямо из списка партий — открываем сводку по завершении
    var triggeredAnalysisFromList by remember { mutableStateOf(false) }
    LaunchedEffect(analysisProgress) {
        val completed = analysisProgress as? AnalyzeGameUseCase.AnalysisProgress.Completed
        if (completed != null && triggeredAnalysisFromList) {
            triggeredAnalysisFromList = false
            gameViewModel.clearAnalysisProgress()
            if (completed.result is AnalyzeGameUseCase.Result.Success) {
                currentOnAnalysisComplete()
            }
        } else if (analysisProgress is AnalyzeGameUseCase.AnalysisProgress.Failed) {
            triggeredAnalysisFromList = false
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var activeSort by remember { mutableStateOf(LibrarySort.NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // Кэшируем разбор метаданных карточек по (id, hash(pgn), color, timeControl) — O(1) при прокрутке и поиске
    val infoCache = remember { mutableMapOf<String, PgnParser.GameCardInfo>() }
    val indexedGames = remember(games) {
        val activeKeys = HashSet<String>(games.size)
        val list = games.map { g ->
            val key = "${g.id}_${g.pgnData.hashCode()}_${g.playerColor}_${g.timeControl}"
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
                LibraryFilter.PENDING -> !item.game.isAnalyzed()
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
            GamesLibraryHeader(indexedGames = indexedGames)
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
                    totalCount = indexedGames.size
                )
            }

            if (filteredGames.isEmpty()) {
                item {
                    NoMatchingGamesCard(
                        onResetFilters = {
                            searchQuery = ""
                            activeFilter = LibraryFilter.ALL
                        }
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
                        onAnalyze = {
                            val id = game.id
                            if (id != null) {
                                triggeredAnalysisFromList = true
                                gameViewModel.analyzeGameWithProgress(
                                    gameId = id,
                                    forceReanalyze = game.analysisStatus == AnalysisStatus.FAILED
                                )
                            }
                        },
                        onDelete = { game.id?.let { gameViewModel.deleteGame(it) } }
                    )
                }
            }
        }
    }

    AnalysisProgressDialog(
        progress = analysisProgress,
        onDismiss = {
            triggeredAnalysisFromList = false
            gameViewModel.clearAnalysisProgress()
        },
        onCancel = {
            triggeredAnalysisFromList = false
            gameViewModel.cancelAnalysis()
        }
    )
}

@Composable
private fun GamesLibraryHeader(indexedGames: List<IndexedGame>) {
    val total = indexedGames.size
    val analyzedGames = indexedGames.filter { it.game.isAnalyzed() }
    val pendingCount = total - analyzedGames.size
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📋 Библиотека партий",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (avgAccuracy != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "Ср. %.1f%%", avgAccuracy),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accuracyColor(avgAccuracy),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
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
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
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
    totalCount: Int
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
                placeholder = { Text("Соперник, дебют, ECO, контроль...") },
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
                FilledTonalIconButton(onClick = { onSortMenuToggle(true) }) {
                    Icon(Icons.Default.Sort, contentDescription = "Сортировка")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { onSortMenuToggle(false) }
                ) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (option == activeSort) FontWeight.Bold else FontWeight.Normal
                                )
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
    }
}

@Composable
private fun NoMatchingGamesCard(onResetFilters: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ничего не найдено",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Попробуйте изменить запрос или сбросить фильтр",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onResetFilters) {
                Text("Сбросить фильтры")
            }
        }
    }
}

@Composable
private fun GamesListMessage(
    message: String,
    onDismiss: () -> Unit
) {
    val isError = message.startsWith("❌")
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
                Text("✕")
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
            Text("📭", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Партий пока нет",
                fontSize = 18.sp,
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

@Composable
fun GameCard(
    game: Game,
    info: PgnParser.GameCardInfo = remember(game.id, game.pgnData, game.playerColor, game.timeControl) {
        PgnParser.extractGameCardInfo(game)
    },
    @Suppress("unused") mistakes: List<Mistake> = emptyList(),
    isBusy: Boolean = false,
    onClick: () -> Unit,
    onAnalyze: () -> Unit = {},
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Верхняя строка: цвет + соперник (Эло) + результат + удаление
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
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

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = info.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Контроль · ходы · дата · часы · платформа
                        val subtitle = buildList {
                            add(info.timeControlLabel)
                            if (info.fullMoveCount > 0) add("${info.fullMoveCount} х.")
                            if (info.dateLabel.isNotBlank()) add(info.dateLabel)
                            if (info.hasClockData) add("🕒")
                            if (info.platform != PgnParser.GamePlatform.OTHER) {
                                add(info.platform.displayName)
                            }
                        }.joinToString(" · ")

                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (info.outcome != PgnParser.PlayerOutcome.UNKNOWN) {
                        OutcomeChip(outcome = info.outcome)
                    } else {
                        StatusChip(status = game.analysisStatus)
                    }

                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Строка дебюта (если определён)
            if (!info.openingLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "📖 ${info.openingLabel}",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccuracyBadge(
                            accuracy = game.accuracy ?: 0.0,
                            acpl = game.averageEvaluationLoss
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            MistakeCountBadge(
                                emoji = "??",
                                label = "Зевки",
                                count = game.blundersCount,
                                activeColor = Color(0xFFD32F2F)
                            )
                            MistakeCountBadge(
                                emoji = "?",
                                label = "Ошибки",
                                count = game.mistakesCount,
                                activeColor = Color(0xFFF57C00)
                            )
                            MistakeCountBadge(
                                emoji = "?!",
                                label = "Неточн.",
                                count = game.inaccuraciesCount,
                                activeColor = Color(0xFFF9A825)
                            )
                        }
                    }
                }

                AnalysisStatus.PENDING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ещё не проанализирована",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onAnalyze,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Анализировать", style = MaterialTheme.typography.labelMedium)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Анализ не завершился",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = onAnalyze,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Повторить", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить партию?") },
            text = {
                Text("Партия «${info.title}» и результаты её анализа будут удалены без возможности восстановления.")
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
    val (color, label) = when (outcome) {
        PgnParser.PlayerOutcome.WIN -> Color(0xFF2E7D32) to "Победа"
        PgnParser.PlayerOutcome.LOSS -> Color(0xFFC62828) to "Поражение"
        PgnParser.PlayerOutcome.DRAW -> Color(0xFFEF6C00) to "Ничья"
        PgnParser.PlayerOutcome.UNKNOWN -> Color(0xFF757575) to "•"
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun StatusChip(status: AnalysisStatus) {
    val (color, text) = when (status) {
        AnalysisStatus.COMPLETED -> Color(0xFF4CAF50) to "✓ Готово"
        AnalysisStatus.IN_PROGRESS -> Color(0xFFFFA726) to "⏳ Анализ"
        AnalysisStatus.PENDING -> Color(0xFF9E9E9E) to "В очереди"
        AnalysisStatus.FAILED -> Color(0xFFE53935) to "Ошибка"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun AccuracyBadge(
    accuracy: Double,
    acpl: Int? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = String.format(Locale.US, "%.1f%%", accuracy),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = accuracyColor(accuracy)
        )
        Text(
            text = if (acpl != null) "Точность · $acpl сП" else "Точность",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun accuracyColor(accuracy: Double): Color = when {
    accuracy >= 90.0 -> Color(0xFF4CAF50)
    accuracy >= 80.0 -> Color(0xFF8BC34A)
    accuracy >= 70.0 -> Color(0xFFFFA726)
    accuracy >= 60.0 -> Color(0xFFFF9800)
    else -> Color(0xFFE53935)
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
            color = if (count > 0) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
