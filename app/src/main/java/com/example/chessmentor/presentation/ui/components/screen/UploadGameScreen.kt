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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.EngineSettings
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.EngineSettingsRepository
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import com.example.chessmentor.presentation.ui.components.AnalysisProgressDialog
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
    onOpenExistingGame: (Game) -> Unit = {},
    chessEngine: ChessEngine? = null
) {
    val pgnInput by gameViewModel.pgnInput
    val selectedColor by gameViewModel.selectedColor
    val timeControl by gameViewModel.timeControl
    val usernameHint by gameViewModel.chessUsernameHint
    val colorAutoDetected by gameViewModel.colorAutoDetected
    val importPreview by gameViewModel.importPreview
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
            onImport = { selected ->
                gameViewModel.importChessComGames(userId, selected)
                showChessCom = false
            },
            onClose = { showChessCom = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            UploadHeader()
        }

        if (userId != null) item {
            OutlinedButton(onClick = { showChessCom = true }, enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("С Chess.com")
            }
        }

        item {
            EngineSettingsCard(
                settings = engineSettings,
                onClick = onOpenEngineSettings
            )
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
                UploadMessage(
                    message = message,
                    isLoading = isLoading,
                    onDismiss = { gameViewModel.clearMessage() }
                )
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
                    onOpenExistingGame = onOpenExistingGame
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
private fun PgnLivePreviewCard(
    preview: PgnImportPreview,
    selectedColor: ChessColor,
    colorAutoDetected: Boolean,
    onOpenExistingGame: (Game) -> Unit
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
                    contentDescription = null,
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
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Архив PGN: найдено партий — ${preview.totalGames}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
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
                    add("новых к импорту: ${preview.validGamesCount}")
                    if (preview.batchDuplicatesCount > 0) {
                        add("повторов: ${preview.batchDuplicatesCount}")
                    }
                    if (preview.validGamesCount > 0) {
                        add("цвет определён по нику: ${preview.batchAutoColorCount}/${preview.validGamesCount}")
                    }
                    val invalid = preview.totalGames - preview.validGamesCount - preview.batchDuplicatesCount - preview.truncatedCount
                    if (invalid > 0) add("с ошибками: $invalid")
                    if (preview.truncatedCount > 0) {
                        add("сверх лимита: ${preview.truncatedCount}")
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
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

                if (meta.result != PgnParser.PgnResult.ONGOING) {
                    val outcomeColor = when (outcome) {
                        PgnParser.PlayerOutcome.WIN -> Color(0xFF2E7D32)
                        PgnParser.PlayerOutcome.LOSS -> Color(0xFFC62828)
                        PgnParser.PlayerOutcome.DRAW -> Color(0xFFEF6C00)
                        PgnParser.PlayerOutcome.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = outcomeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${meta.result.displaySymbol()} · ${outcome.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = outcomeColor,
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

            // Предупреждение о дубликате с быстрым переходом к уже сохранённой партии
            preview.duplicateExistingGame?.let { existing ->
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    color = Color(0xFFFFA000).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF6C00),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Эта партия уже в библиотеке",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = { onOpenExistingGame(existing) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Открыть", style = MaterialTheme.typography.labelMedium)
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
                contentDescription = null,
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
private fun UploadHeader() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Импорт партий",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Буфер обмена, файл .pgn или пакет из Lichess / Chess.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun UploadMessage(
    message: String,
    isLoading: Boolean,
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
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (!isLoading) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
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
    val isHugeFileLoaded = pgnInput.length > MAX_INLINE_TEXTFIELD_CHARS

    val whitePlayerName = preview?.singleParsed?.metadata?.whiteName
    val blackPlayerName = preview?.singleParsed?.metadata?.blackName

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Панель быстрых действий: Вставить из буфера / Файл .pgn / Очистить
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPasteFromClipboard,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Вставить", maxLines = 1)
                }

                FilledTonalButton(
                    onClick = onPickFile,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Файл .pgn", maxLines = 1)
                }

                if (pgnInput.isNotEmpty()) {
                    OutlinedIconButton(
                        onClick = onClear,
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
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
                            contentDescription = null,
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
                    label = { Text("Текст PGN (одна или несколько партий)") },
                    placeholder = { Text("[White \"...\"] [Black \"...\"]\n1. e4 e5 2. Nf3 Nc6 ...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp),
                    maxLines = 9,
                    enabled = !isLoading
                )
            }

            // Keep this field mounted while a nickname edit triggers a fresh preview.
            if (pgnInput.isNotBlank()) {
                OutlinedTextField(
                    value = usernameHint,
                    onValueChange = onUsernameHintChange,
                    label = { Text("Ваш ник на Lichess / Chess.com (для автоцвета)") },
                    placeholder = { Text("Например: DrNykterstein") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                )
            }

            // Выбор цвета (с подстановкой реальных имён белых и чёрных из PGN)
            Column {
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
            if (!isBatch) {
                OutlinedTextField(
                    value = timeControl,
                    onValueChange = onTimeControlChange,
                    label = { Text("Контроль времени (авто из PGN или вручную)") },
                    placeholder = { Text("3+2, 5+3, 10+0, 15+10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && validSingle,
                    leadingIcon = {
                        Icon(Icons.Default.Timer, contentDescription = null)
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
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импортируем...")
                    } else {
                        Icon(Icons.Default.LibraryAdd, contentDescription = null)
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
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Импортировать ($importableCount) и разобрать самую свежую")
                    }
                }
            } else {
                Button(
                    onClick = { onUploadAndAnalyze(hasDuplicate) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    enabled = !isLoading && validSingle
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Загружаем...")
                    } else {
                        Icon(Icons.Default.Analytics, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (hasDuplicate) "Загрузить копию и анализировать"
                            else "Импортировать партию и разобрать"
                        )
                    }
                }

                if (validSingle && !hasDuplicate) {
                    OutlinedButton(
                        onClick = { onSaveToLibrary(false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сохранить в библиотеку без анализа")
                    }
                }
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Поддерживаются одиночные PGN и скачанные .pgn-архивы Lichess / Chess.com " +
                        "(до ${PgnParser.MAX_BATCH_GAMES} партий за раз) с рейтингами, дебютом, результатом и часами.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        modifier = modifier,
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
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        }
    )
}
