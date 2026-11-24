// presentation/ui/screen/UploadGameScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
import com.example.chessmentor.presentation.ui.components.MessageCard
import com.example.chessmentor.presentation.ui.components.MessageType
import com.example.chessmentor.presentation.ui.components.EngineStatusBanner
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.example.chessmentor.presentation.viewmodel.PgnImportPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_INLINE_TEXTFIELD_CHARS = 24_000

@Composable
fun UploadGameScreen(
    paddingValues: PaddingValues,
    gameViewModel: GameViewModel,
    engineSettingsRepository: EngineSettingsRepository,
    onGameUploaded: () -> Unit,
    onAnalysisComplete: () -> Unit,
    onOpenEngineSettings: () -> Unit,
    onNavigateToLibrary: () -> Unit = {},
    onOpenExistingGame: (Long) -> Unit = {},
    chessEngine: ChessEngine? = null
) {
    val pgnInput by gameViewModel.pgnInput
    val selectedColor by gameViewModel.selectedColor
    val timeControl by gameViewModel.timeControl
    val usernameHint by gameViewModel.chessUsernameHint
    val colorAutoDetected by gameViewModel.colorAutoDetected
    val importPreview by gameViewModel.importPreview
    val pendingChessComRetry = gameViewModel.pendingChessComRetry
    val chessComPartialSavedCount by gameViewModel.chessComPartialSavedCount
    val message by gameViewModel.message
    val isLoading by gameViewModel.isLoading

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val analysisProgress by gameViewModel.analysisProgress.collectAsState(initial = null)
    val currentOnAnalysisComplete by rememberUpdatedState(onAnalysisComplete)
    val currentOnGameUploaded by rememberUpdatedState(onGameUploaded)
    val currentOnNavigateToLibrary by rememberUpdatedState(onNavigateToLibrary)

    // Системный выбор файла .pgn / .txt без лишних разрешений хранилища (SAF)
    val pgnFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                readPgnFileFromUri(context, uri)
            }
            result.fold(
                onSuccess = { (fileName, content) ->
                    gameViewModel.clearMessage()
                    gameViewModel.onPgnInputChanged(
                        newPgn = content,
                        sourceFileName = fileName,
                        forceAutoApply = true
                    )
                },
                onFailure = { err ->
                    gameViewModel.message.value = "Ошибка: ${err.message ?: "Не удалось прочитать файл"}"
                }
            )
        }
    }

    // Автоматический переход к сводке после успешного завершения анализа
    LaunchedEffect(analysisProgress) {
        val completed = analysisProgress as? AnalyzeGameUseCase.AnalysisProgress.Completed
        if (completed != null) {
            gameViewModel.clearAnalysisProgress()
            if (completed.result is AnalyzeGameUseCase.Result.Success) {
                currentOnGameUploaded()
                currentOnAnalysisComplete()
            }
        }
    }

    val engineSettings = remember { engineSettingsRepository.getSettings() }
    val engineStatus by if (chessEngine != null) {
        chessEngine.status.collectAsState()
    } else {
        remember { mutableStateOf<EngineStatus>(EngineStatus.Idle) }
    }

    val userId = gameViewModel.currentUser.value?.id
    var showChessCom by rememberSaveable(userId) { mutableStateOf(false) }
    if (showChessCom && userId != null) {
        ChessComImportDialog(
            savedName = gameViewModel.savedChessComSource(userId),
            rememberSource = { gameViewModel.setChessComSource(userId, it) },
            monthsFor = gameViewModel::archiveMonths,
            gamesFor = { name, period -> gameViewModel.archiveGames(userId, name, period) },
            onOpenSaved = { id -> showChessCom = false; onOpenExistingGame(id) },
            onImport = { selected ->
                showChessCom = false
                gameViewModel.importChessComGames(userId, selected) {
                    currentOnNavigateToLibrary()
                }
            },
            onClose = { showChessCom = false }
        )
    }

    val layoutDirection = LocalLayoutDirection.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(
            top = 16.dp + paddingValues.calculateTopPadding(),
            bottom = 16.dp + paddingValues.calculateBottomPadding(),
            start = 16.dp + paddingValues.calculateStartPadding(layoutDirection),
            end = 16.dp + paddingValues.calculateEndPadding(layoutDirection)
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (userId != null) item {
            OutlinedButton(onClick = { showChessCom = true }, enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("С Chess.com")
            }
        }

        // Живой прогресс/ошибка загрузки NNUE прямо перед запуском анализа партии
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

        if (pendingChessComRetry.isNotEmpty()) {
            item(key = "chesscom-retry") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (chessComPartialSavedCount > 0) {
                                "Сохранено $chessComPartialSavedCount · осталось несохранённых: ${pendingChessComRetry.size}"
                            } else {
                                "Осталось несохранённых партий Chess.com: ${pendingChessComRetry.size}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (chessComPartialSavedCount > 0) {
                                OutlinedButton(
                                    onClick = { currentOnNavigateToLibrary() },
                                    enabled = !isLoading,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Показать добавленные")
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    gameViewModel.retryPendingChessComImport {
                                        currentOnNavigateToLibrary()
                                    }
                                },
                                enabled = !isLoading,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Повторить оставшиеся (${pendingChessComRetry.size})")
                            }
                            TextButton(
                                onClick = { gameViewModel.dismissPendingChessComRetry() },
                                enabled = !isLoading,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Скрыть")
                            }
                        }
                    }
                }
            }
        }

        // Живой предпросмотр распознанной партии или мульти-PGN пакета
        val preview = importPreview
        if (preview != null) {
            item(key = "preview") {
                PgnLivePreviewCard(
                    preview = preview,
                    selectedColor = selectedColor,
                    colorAutoDetected = colorAutoDetected,
                    isLoading = isLoading,
                    onCopy = { gameViewModel.uploadAndAnalyzeGame(allowDuplicate = true) },
                    onOpenExistingGame = onOpenExistingGame,
                    onNextBatchWindow = { gameViewModel.showNextBatchWindow() },
                    onResetBatchWindow = { gameViewModel.resetBatchWindow() },
                    onUpdateNotes = { id ->
                        gameViewModel.updateExistingGamePgnNotes(id) {
                            onOpenExistingGame(id)
                        }
                    },
                    onAddDifferentGame = {
                        gameViewModel.saveGameWithoutAnalysis(
                            allowDuplicate = true,
                            onSavedToLibrary = { currentOnNavigateToLibrary() }
                        )
                    },
                    onFixFailedRecord = { block ->
                        gameViewModel.loadFailedBatchRecordForEdit(block)
                    }
                )
            }
        }

        item(key = "pgn-form") {
            UploadForm(
                pgnInput = pgnInput,
                preview = preview,
                selectedColor = selectedColor,
                timeControl = timeControl,
                usernameHint = usernameHint,
                isLoading = isLoading,
                onPasteFromClipboard = {
                    val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                    if (clipText.isEmpty()) {
                        gameViewModel.message.value = "Ошибка: Буфер обмена пуст — скопируйте PGN из Lichess или Chess.com"
                    } else {
                        gameViewModel.clearMessage()
                        gameViewModel.onPgnInputChanged(
                            newPgn = clipText,
                            sourceFileName = null,
                            forceAutoApply = true
                        )
                    }
                },
                onPickFile = {
                    pgnFileLauncher.launch(
                        arrayOf(
                            "application/x-chess-pgn",
                            "application/vnd.chess-pgn",
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onClear = {
                    gameViewModel.clearMessage()
                    gameViewModel.clearImportForm()
                },
                onPgnChange = { gameViewModel.onPgnInputChanged(it) },
                onColorChange = { gameViewModel.selectPlayerColorManually(it) },
                onTimeControlChange = gameViewModel::setImportTimeControl,
                onUsernameHintChange = { gameViewModel.onChessUsernameHintChanged(it) },
                onUploadAndAnalyze = { allowDuplicate ->
                    gameViewModel.uploadAndAnalyzeGame(allowDuplicate = allowDuplicate)
                },
                onSaveToLibrary = { allowDuplicate ->
                    gameViewModel.saveGameWithoutAnalysis(
                        allowDuplicate = allowDuplicate,
                        onSavedToLibrary = { currentOnNavigateToLibrary() }
                    )
                },
                onImportBatch = { analyzeNewest ->
                    gameViewModel.importBatchGames(
                        analyzeNewest = analyzeNewest,
                        onImportedToLibrary = { currentOnNavigateToLibrary() }
                    )
                }
            )
        }

        item {
            EngineSettingsCard(
                settings = engineSettings,
                onClick = onOpenEngineSettings
            )
        }
    }

    AnalysisProgressDialog(
        progress = analysisProgress,
        onDismiss = { gameViewModel.clearAnalysisProgress() },
        onCancel = { gameViewModel.cancelAnalysis() }
    )
}

private fun readPgnFileFromUri(context: Context, uri: Uri): Result<Pair<String, String>> {
    return runCatching {
        val resolver = context.contentResolver
        var fileName = "games.pgn"
        var fileSize = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        fileName = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }

        val maxBytes = PgnParser.MAX_ARCHIVE_CHARS.toLong()
        if (fileSize > maxBytes) {
            throw IllegalArgumentException("Файл слишком большой (максимум 1.5 МБ)")
        }

        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть выбранный файл")

        val bytes = stream.use { input ->
            val buffer = ByteArray(16_384)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > PgnParser.MAX_ARCHIVE_CHARS) {
                    throw IllegalArgumentException("Файл слишком большой (максимум 1.5 МБ)")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isEmpty()) {
            throw IllegalArgumentException("Выбранный файл пуст")
        }
        // Защита от случайного выбора бинарного файла (картинки, PDF, архива)
        if (text.take(512).any { it == '\u0000' }) {
            throw IllegalArgumentException("Это не текстовый файл PGN — выберите файл с расширением .pgn")
        }

        fileName to text
    }
}

@Composable
internal fun PgnLivePreviewCard(
    preview: PgnImportPreview,
    selectedColor: ChessColor,
    colorAutoDetected: Boolean,
    isLoading: Boolean,
    onCopy: () -> Unit,
    onOpenExistingGame: (Long) -> Unit,
    onNextBatchWindow: () -> Unit = {},
    onResetBatchWindow: () -> Unit = {},
    onUpdateNotes: (Long) -> Unit = {},
    onAddDifferentGame: () -> Unit = {},
    onFixFailedRecord: (String) -> Unit = {}
) {
    // 1. Ошибка разбора одиночного PGN
    if (!preview.isBatch && preview.singleError != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Проверьте запись PGN",
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Проверьте запись PGN",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = preview.singleError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        return
    }

    // 2. Пакет из нескольких партий (мульти-PGN)
    if (preview.isBatch) {
        var showErrors by remember(preview.batchOffset, preview.batchErrorDetails.size) { mutableStateOf(false) }
        val windowStart = preview.batchOffset + 1
        val windowEnd = preview.batchOffset + preview.batchWindowCount.coerceAtLeast(1)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LibraryAdd,
                        contentDescription = "Архив PGN",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Архив PGN: найдено партий — ${preview.totalGames}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (preview.totalGames > PgnParser.MAX_BATCH_GAMES || preview.batchOffset > 0) {
                            Text(
                                text = "Проверены записи $windowStart–$windowEnd из ${preview.totalGames}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        preview.sourceFileName?.let { name ->
                            Text(
                                text = "Файл: $name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                val metaSummary = buildList {
                    add("новых в этой части: ${preview.validGamesCount}")
                    if (preview.batchDuplicatesCount > 0) {
                        add("повторов: ${preview.batchDuplicatesCount}")
                    }
                    if (preview.validGamesCount > 0) {
                        add("цвет определён по нику: ${preview.batchAutoColorCount}/${preview.validGamesCount}")
                    }
                    if (preview.batchInvalidCount > 0) {
                        add("с ошибками: ${preview.batchInvalidCount}")
                    }
                    if (preview.batchRemainingCount > 0) {
                        add("далее в файле: ${preview.batchRemainingCount}")
                    }
                }.joinToString(" · ")

                Text(
                    text = metaSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                if (preview.batchSampleLines.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    preview.batchSampleLines.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (preview.batchErrorDetails.isNotEmpty()) {
                    TextButton(
                        onClick = { showErrors = !showErrors },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (showErrors) {
                                "Скрыть ошибки (${preview.batchInvalidCount})"
                            } else {
                                "Посмотреть ошибки (${preview.batchInvalidCount})"
                            }
                        )
                    }
                    if (showErrors) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (preview.batchFailedRecords.isNotEmpty()) {
                                    preview.batchFailedRecords.forEach { record ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "• ${record.summary}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            TextButton(
                                                onClick = { onFixFailedRecord(record.rawPgnBlock) },
                                                enabled = !isLoading,
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) {
                                                Text("Исправить запись #${record.recordNumber}")
                                            }
                                        }
                                    }
                                } else {
                                    preview.batchErrorDetails.forEach { errLine ->
                                        Text(
                                            text = "• $errLine",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (preview.batchRemainingCount > 0 || preview.batchOffset > 0) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (preview.batchRemainingCount > 0) {
                            val nextStep = minOf(PgnParser.MAX_BATCH_GAMES, preview.batchRemainingCount)
                            OutlinedButton(
                                onClick = onNextBatchWindow,
                                enabled = !isLoading,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Проверить следующие $nextStep")
                            }
                        }
                        if (preview.batchOffset > 0) {
                            TextButton(
                                onClick = onResetBatchWindow,
                                enabled = !isLoading,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("К записям 1–${PgnParser.MAX_BATCH_GAMES}")
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // 3. Одиночная корректно распознанная партия
    val parsed = preview.singleParsed ?: return
    val meta = parsed.metadata
    val outcome = meta.result.forPlayer(selectedColor)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Распознана одна партия",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append("Распознана одна партия")
                            if (meta.platform != PgnParser.GamePlatform.OTHER) {
                                append(" · ${meta.platform.displayName}")
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (parsed.hasResultConflict) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Расхождение результата в PGN",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "В заголовке [Result]: ${parsed.headerResult.displaySymbol()}, в конце ходов: ${parsed.trailingResult.displaySymbol()}. До исправления исход не будет засчитан в статистике.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (meta.result != PgnParser.PgnResult.ONGOING) {
                    val colors = MaterialTheme.colorScheme
                    val (fill, ink) = when (outcome) {
                        PgnParser.PlayerOutcome.WIN -> colors.primaryContainer to colors.onPrimaryContainer
                        PgnParser.PlayerOutcome.LOSS -> colors.errorContainer to colors.onErrorContainer
                        else -> colors.secondaryContainer to colors.onSecondaryContainer
                    }
                    Surface(
                        color = fill,
                        contentColor = ink,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${meta.result.displaySymbol()} · ${outcome.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Игроки и рейтинги
            if (meta.whiteName != null || meta.blackName != null) {
                val wText = formatSidePreview(meta.whiteTitle, meta.whiteName ?: "Белые", meta.whiteElo)
                val bText = formatSidePreview(meta.blackTitle, meta.blackName ?: "Чёрные", meta.blackElo)
                Text(
                    text = "Белые: $wText · Чёрные: $bText",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Дебют
            parsed.openingLabel?.let { opening ->
                Text(
                    text = "Дебют: $opening",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Ходы, контроль, дата, часы, причина окончания
            val details = buildList {
                add("${parsed.fullMoveCount} х.")
                meta.timeControl?.fullLabel?.let { add(it) }
                meta.dateLabel?.let { add(it) }
                if (parsed.clocks.hasData) {
                    val trouble = parsed.clocks.timeTroubleMovesFor(selectedColor)
                    if (trouble > 0) add("часы (цейтнот: $trouble х.)") else add("с часами")
                }
                meta.terminationRu?.let { add(it) }
            }.joinToString(" · ")

            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (colorAutoDetected) {
                Text(
                    text = "Ваш цвет (${selectedColor.getDisplayName().lowercase()}) определён автоматически по нику",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Предупреждение о дубликате / обновлении заметок / совпадении ходов в другой партии
            preview.duplicateExistingGame?.id?.let { id ->
                var confirmCopy by remember(id, parsed.fingerprint) { mutableStateOf(false) }
                val matchInfo = preview.duplicateMatchInfo
                when (matchInfo?.kind) {
                    PgnParser.DuplicateMatchKind.ANNOTATED_REVISION -> {
                        Text(
                            text = "В партии №$id отличаются комментарии или варианты",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Можно обновить заметки в сохранённой партии без создания дубликата и без повторного разбора Stockfish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onUpdateNotes(id) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Обновить заметки в партии №$id")
                        }
                        OutlinedButton(
                            onClick = { onOpenExistingGame(id) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Открыть партию №$id")
                        }
                    }

                    PgnParser.DuplicateMatchKind.DIFFERENT_CONTEXT -> {
                        Text(
                            text = "Совпали ходы с партией №$id, но различаются соперник или дата",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "В библиотеке №$id: ${matchInfo.existingSummary}\nВ этом PGN: ${matchInfo.incomingSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onAddDifferentGame,
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Добавить как другую партию")
                        }
                        OutlinedButton(
                            onClick = { onOpenExistingGame(id) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Открыть партию №$id")
                        }
                    }

                    else -> {
                        Text("Эта партия уже в библиотеке", style = MaterialTheme.typography.labelLarge)
                        Button(
                            onClick = { onOpenExistingGame(id) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Открыть партию №$id")
                        }
                        if (confirmCopy) {
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Создать копию партии №$id?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("В библиотеке появится вторая запись. Stockfish разберёт копию; сохранённая партия не изменится.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                    TextButton(onClick = { confirmCopy = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                                    Button(
                                        onClick = { confirmCopy = false; onCopy() },
                                        enabled = !isLoading,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Создать и разобрать")
                                    }
                                }
                            }
                        } else {
                            TextButton(
                                onClick = { confirmCopy = true },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Создать отдельную копию")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSidePreview(title: String?, name: String, elo: Int?): String {
    val prefix = if (!title.isNullOrBlank()) "$title " else ""
    val suffix = if (elo != null) " ($elo)" else ""
    return "$prefix$name$suffix"
}

@Composable
private fun EngineSettingsCard(
    settings: EngineSettings,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Настройки импорта",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Настройки движка",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Глубина: ${settings.depth} • Потоки: ${settings.threads} • Хеш: ${settings.hashSizeMb}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = settings.getProfileName(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Открыть",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
internal fun UploadForm(
    pgnInput: String,
    preview: PgnImportPreview?,
    selectedColor: ChessColor,
    timeControl: String,
    usernameHint: String,
    isLoading: Boolean,
    onPasteFromClipboard: () -> Unit,
    onPickFile: () -> Unit,
    onClear: () -> Unit,
    onPgnChange: (String) -> Unit,
    onColorChange: (ChessColor) -> Unit,
    onTimeControlChange: (String) -> Unit,
    onUsernameHintChange: (String) -> Unit,
    onUploadAndAnalyze: (allowDuplicate: Boolean) -> Unit,
    onSaveToLibrary: (allowDuplicate: Boolean) -> Unit,
    onImportBatch: (analyzeNewest: Boolean) -> Unit
) {
    val isBatch = preview?.isBatch == true
    val validSingle = preview?.singleParsed != null
    val hasDuplicate = preview?.duplicateExistingGame != null
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val isHugeFileLoaded = pgnInput.length > MAX_INLINE_TEXTFIELD_CHARS

    val whitePlayerName = preview?.singleParsed?.metadata?.whiteName
    val blackPlayerName = preview?.singleParsed?.metadata?.blackName

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Панель быстрых действий: Вставить из буфера / Файл .pgn / Очистить
            @OptIn(ExperimentalLayoutApi::class)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val minSingleRowWidth = if (pgnInput.isNotEmpty()) 330 else 280
                val wrapActions = maxWidth < (minSingleRowWidth * fontScale).dp
                if (wrapActions) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPasteFromClipboard,
                            enabled = !isLoading,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Вставить из буфера обмена",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Вставить", maxLines = 1)
                        }

                        FilledTonalButton(
                            onClick = onPickFile,
                            enabled = !isLoading,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Выбрать файл .pgn",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Файл .pgn", maxLines = 1)
                        }

                        if (pgnInput.isNotEmpty()) {
                            OutlinedIconButton(
                                onClick = onClear,
                                enabled = !isLoading,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onPasteFromClipboard,
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Вставить из буфера обмена",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Вставить", maxLines = 1)
                        }

                        FilledTonalButton(
                            onClick = onPickFile,
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Выбрать файл .pgn",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Файл .pgn", maxLines = 1)
                        }

                        if (pgnInput.isNotEmpty()) {
                            OutlinedIconButton(
                                onClick = onClear,
                                enabled = !isLoading,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    }
                }
            }

            // Поле ввода PGN (или компактная плашка для крупного .pgn-архива, чтобы не тормозить UI)
            if (isHugeFileLoaded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Исходный файл PGN",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preview?.sourceFileName ?: "Текст PGN",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Объём: ${pgnInput.length / 1024} КБ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = pgnInput,
                    onValueChange = onPgnChange,
                    label = { Text("Текст PGN") },
                    placeholder = { Text("[White \"...\"] [Black \"...\"]\n1. e4 e5 2. Nf3 Nc6 ...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 175.dp),
                    minLines = 2,
                    maxLines = 6,
                    enabled = !isLoading
                )
            }

            // Keep this field mounted while a nickname edit triggers a fresh preview.
            if (pgnInput.isNotBlank()) {
                OutlinedTextField(
                    value = usernameHint,
                    onValueChange = onUsernameHintChange,
                    label = { Text("Ник для выбора цвета") },
                    placeholder = { Text("Например: DrNykterstein") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = "Ник для выбора цвета")
                    }
                )
            }

            // Выбор цвета (с подстановкой реальных имён белых и чёрных из PGN)
            if (validSingle || isBatch) Column {
                Text(
                    text = if (isBatch) "Цвет по умолчанию (если ник не найден в партии):" else "Ваш цвет:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ColorChoiceChip(
                        label = if (!isBatch && !whitePlayerName.isNullOrBlank()) {
                            "Белые · $whitePlayerName"
                        } else {
                            "Белые"
                        },
                        pieceColor = Color.White,
                        selected = selectedColor == ChessColor.WHITE,
                        enabled = !isLoading && preview != null,
                        onClick = { onColorChange(ChessColor.WHITE) },
                        modifier = Modifier.weight(1f)
                    )
                    ColorChoiceChip(
                        label = if (!isBatch && !blackPlayerName.isNullOrBlank()) {
                            "Чёрные · $blackPlayerName"
                        } else {
                            "Чёрные"
                        },
                        pieceColor = Color.Black,
                        selected = selectedColor == ChessColor.BLACK,
                        enabled = !isLoading && preview != null,
                        onClick = { onColorChange(ChessColor.BLACK) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Контроль времени (для одиночной партии заполняется автоматически из [TimeControl])
            if (validSingle) {
                OutlinedTextField(
                    value = timeControl,
                    onValueChange = onTimeControlChange,
                    label = { Text("Контроль времени") },
                    placeholder = { Text("3+2, 5+3, 10+0, 15+10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && validSingle,
                    leadingIcon = {
                        Icon(Icons.Default.Timer, contentDescription = "Контроль времени")
                    }
                )
            }

            // Кнопки действия: одиночный режим vs пакетный мульти-PGN
            if (pgnInput.isNotBlank() && preview == null) {
                Text("Проверяем PGN…", style = MaterialTheme.typography.bodyMedium)
            } else if (isBatch) {
                val importableCount = preview?.validGamesCount ?: 0
                Button(
                    onClick = { onImportBatch(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    enabled = !isLoading && importableCount > 0
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импортируем...")
                    } else {
                        Icon(Icons.Default.LibraryAdd, contentDescription = "Импортировать в библиотеку")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (importableCount > 0) {
                                "Импортировать в библиотеку ($importableCount)"
                            } else {
                                "Новых партий нет"
                            }
                        )
                    }
                }

                if (importableCount > 0) {
                    OutlinedButton(
                        onClick = { onImportBatch(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "Импортировать и разобрать",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импортировать ($importableCount) и разобрать самую свежую")
                    }
                    if (preview != null && !preview.batchHasDatedGames) {
                        Text(
                            text = "В этих записях нет даты игры — будет разобрана первая добавленная партия.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!hasDuplicate && pgnInput.isNotBlank()) {
                Button(
                    onClick = { onUploadAndAnalyze(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    enabled = !isLoading && validSingle
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Загружаем...")
                    } else {
                        Icon(Icons.Default.Analytics, contentDescription = "Импортировать партию и разобрать")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импортировать партию и разобрать")
                    }
                }

                if (validSingle) {
                    OutlinedButton(
                        onClick = { onSaveToLibrary(false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Сохранить в библиотеку без анализа",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сохранить в библиотеку без анализа")
                    }
                }
            }

            TextButton(
                onClick = { showHelp = !showHelp },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (showHelp) "Скрыть справку" else "Что такое PGN?")
            }
            if (showHelp) {
                Text("PGN — текстовая запись партии. Вставьте её или выберите файл .pgn. " +
                    "Из архива можно загрузить до ${PgnParser.MAX_BATCH_GAMES} партий за раз.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColorChoiceChip(
    label: String,
    pieceColor: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(pieceColor, CircleShape)
                    .border(1.dp, Color.Gray, CircleShape)
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Выбрано",
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        }
    )
}
