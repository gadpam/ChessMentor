// presentation/ui/components/navigation/ChessMentorNavigation.kt
package com.example.chessmentor.presentation.ui.components.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.painter.rememberVectorPainter
import androidx.compose.material3.Badge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import com.example.chessmentor.R
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.session.SessionState
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.usecase.LoginUserUseCase
import com.example.chessmentor.domain.usecase.RegisterUserUseCase
import com.example.chessmentor.presentation.ui.components.CommonUI.AnimatedContentSwitcher
import com.example.chessmentor.presentation.ui.components.screen.AuthScreen
import com.example.chessmentor.presentation.ui.components.screen.DashboardScreen
import com.example.chessmentor.presentation.ui.components.screen.EngineSettingsScreen
import com.example.chessmentor.presentation.ui.components.screen.GameViewScreen
import com.example.chessmentor.presentation.ui.components.screen.GamesListScreen
import com.example.chessmentor.presentation.ui.components.screen.SettingsScreen
import com.example.chessmentor.presentation.ui.components.screen.StatisticsScreen
import com.example.chessmentor.presentation.ui.components.screen.SummaryScreen
import com.example.chessmentor.presentation.ui.components.screen.TrainingScreen
import com.example.chessmentor.presentation.ui.components.screen.UploadGameScreen
import com.example.chessmentor.presentation.viewmodel.BoardViewModel
import com.example.chessmentor.presentation.viewmodel.ChessMentorViewModelFactory
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "ChessMentorNavigation"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessMentorApp(
    container: AppContainer,
    gameViewModel: GameViewModel
) {
    // Сессия сохраняется между перезапусками приложения и поворотами экрана
    val sessionState by gameViewModel.sessionState
    val currentUser by gameViewModel.currentUser
    val lastLoginEmail by gameViewModel.lastLoginEmail

    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var pendingTrainingModeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGamesSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGamesColorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var trainingReturnScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadReturnScreen by rememberSaveable { mutableStateOf("games") }
    var summaryReturnScreen by rememberSaveable { mutableStateOf("games") }
    var engineSettingsReturnScreen by rememberSaveable { mutableStateOf("upload") }
    var isLoading by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var dueReviewCount by remember { mutableStateOf(0) }

    // Форма регистрации (пароли не сохраняем в Bundle)
    var regNickname by rememberSaveable { mutableStateOf("") }
    var regEmail by rememberSaveable { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regRating by rememberSaveable { mutableStateOf("1200") }

    // Форма входа (подставляем последний email пользователя без сохранения пароля)
    var loginEmail by rememberSaveable { mutableStateOf(lastLoginEmail) }
    var loginPassword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        gameViewModel.restoreSessionIfNeeded()
    }

    LaunchedEffect(lastLoginEmail, currentUser) {
        if (currentUser == null && loginEmail.isBlank() && lastLoginEmail.isNotBlank()) {
            loginEmail = lastLoginEmail
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val viewModelFactory = remember(container) { ChessMentorViewModelFactory(container) }
    val boardViewModel: BoardViewModel = viewModel(factory = viewModelFactory)

    val handleNavigateBack: () -> Unit = {
        currentScreen = when (currentScreen) {
            "training" -> {
                val returnTarget = trainingReturnScreen
                trainingReturnScreen = null
                if (returnTarget != null && gameViewModel.selectedGame.value != null) {
                    returnTarget
                } else {
                    "home"
                }
            }
            "game_view" -> {
                boardViewModel.dismissEngineLine()
                "summary"
            }
            "summary" -> {
                boardViewModel.dismissEngineLine()
                gameViewModel.closeGameView()
                val target = summaryReturnScreen.takeIf { it in setOf("games", "stats", "home", "upload") } ?: "games"
                summaryReturnScreen = "games"
                target
            }
            "upload" -> uploadReturnScreen
            "engine_settings" -> {
                engineSettingsReturnScreen
                    .takeIf { it in setOf("upload", "settings", "home", "games") }
                    ?: "upload"
            }
            else -> "home"
        }
        Log.d(TAG, "Navigate back to: $currentScreen")
    }

    // Системная кнопка/жест «Назад» навигирует внутри приложения вместо закрытия Activity
    BackHandler(enabled = currentUser != null && currentScreen != "home") {
        handleNavigateBack()
    }

    Scaffold(
        topBar = {
            if (currentScreen != "training") ChessMentorTopBar(
                currentScreen = currentScreen,
                currentUser = currentUser,
                onNavigateBack = handleNavigateBack
            )
        },
        bottomBar = {
            if (currentUser != null && currentScreen !in setOf("training", "game_view", "engine_settings")) {
                ChessMentorBottomBar(
                    currentScreen = currentScreen,
                    dueReviewCount = dueReviewCount,
                    onNavigate = { target ->
                        if (currentScreen == "game_view") {
                            boardViewModel.dismissEngineLine()
                        }
                        trainingReturnScreen = null
                        summaryReturnScreen = "games"
                        currentScreen = target
                        Log.d(TAG, "Navigate to: $target")
                    }
                )
            }
        }
    ) { paddingValues ->
        NavigationHost(
            paddingValues = paddingValues,
            currentScreen = currentScreen,
            isRestoringSession = sessionState is SessionState.Restoring && currentUser == null,
            pendingTrainingModeKey = pendingTrainingModeKey,
            pendingGamesSearchQuery = pendingGamesSearchQuery,
            pendingGamesColor = pendingGamesColorKey?.let { key ->
                ChessColor.entries.firstOrNull { it.name == key }
            },
            currentUser = currentUser,
            gameViewModel = gameViewModel,
            boardViewModel = boardViewModel,
            container = container,
            message = message,
            isLoading = isLoading,
            regNickname = regNickname,
            regEmail = regEmail,
            regPassword = regPassword,
            regRating = regRating,
            loginEmail = loginEmail,
            loginPassword = loginPassword,
            onScreenChange = { next ->
                if (next == "upload" && currentScreen in setOf("games", "home", "stats", "training")) {
                    uploadReturnScreen = currentScreen
                }
                currentScreen = next
                Log.d(TAG, "Screen changed to: $next")
            },
            onOpenEngineSettings = { fromScreen ->
                engineSettingsReturnScreen = fromScreen
                currentScreen = "engine_settings"
                Log.d(TAG, "Open engine settings from: $fromScreen")
            },
            onCloseEngineSettings = {
                currentScreen = engineSettingsReturnScreen
                    .takeIf { it in setOf("upload", "settings", "home", "games") }
                    ?: "upload"
            },
            onOpenTrainingWithMode = { mode ->
                if (mode !is TrainingMode.FromGame) {
                    trainingReturnScreen = null
                }
                pendingTrainingModeKey = mode.routeKey
                currentScreen = "training"
                Log.d(TAG, "Open training with mode: ${mode.routeKey}")
            },
            onOpenGameMistakeTraining = { fromScreen, gameId, focusMoveNumber ->
                boardViewModel.dismissEngineLine()
                trainingReturnScreen = fromScreen
                pendingTrainingModeKey = TrainingMode.FromGame(
                    gameId = gameId,
                    focusMoveNumber = focusMoveNumber
                ).routeKey
                currentScreen = "training"
                Log.d(TAG, "Open game mistake training: game=$gameId move=$focusMoveNumber from=$fromScreen")
            },
            onLeaveTraining = handleNavigateBack,
            onReturnToGameFromTraining = if (
                trainingReturnScreen != null && gameViewModel.selectedGame.value != null
            ) {
                {
                    val target = trainingReturnScreen ?: "summary"
                    trainingReturnScreen = null
                    currentScreen = target
                }
            } else {
                null
            },
            onTrainingModeConsumed = {
                pendingTrainingModeKey = null
            },
            onOpenGamesWithFilter = { query, color ->
                pendingGamesSearchQuery = query
                pendingGamesColorKey = color?.name
                currentScreen = "games"
                Log.d(TAG, "Open games library filtered by query='$query' color=$color")
            },
            onGamesFilterConsumed = {
                pendingGamesSearchQuery = null
                pendingGamesColorKey = null
            },
            onOpenGameSummaryById = { fromScreen, gameId ->
                val cached = gameViewModel.userGames.firstOrNull { it.id == gameId }
                if (cached != null) {
                    summaryReturnScreen = fromScreen
                    gameViewModel.selectGame(cached)
                    currentScreen = "summary"
                } else {
                    coroutineScope.launch {
                        val loaded = runCatching { container.gameRepository.findById(gameId) }.getOrNull()
                        if (loaded != null) {
                            summaryReturnScreen = fromScreen
                            gameViewModel.selectGame(loaded)
                            currentScreen = "summary"
                        }
                    }
                }
            },
            onUserUpdated = { updated ->
                gameViewModel.updateCurrentUser(updated)
            },
            onLogout = {
                val rememberedEmail = currentUser?.email ?: gameViewModel.lastLoginEmail.value
                boardViewModel.dismissEngineLine()
                gameViewModel.logout()
                loginEmail = rememberedEmail
                loginPassword = ""
                regPassword = ""
                message = ""
                pendingTrainingModeKey = null
                pendingGamesSearchQuery = null
                pendingGamesColorKey = null
                trainingReturnScreen = null
                summaryReturnScreen = "games"
                uploadReturnScreen = "games"
                engineSettingsReturnScreen = "upload"
                currentScreen = "home"
            },
            onMessageDismiss = { message = "" },
            onRegNicknameChange = { regNickname = it },
            onRegEmailChange = { regEmail = it },
            onRegPasswordChange = { regPassword = it },
            onRegRatingChange = { regRating = it },
            onLoginEmailChange = { loginEmail = it },
            onLoginPasswordChange = { loginPassword = it },
            onOpenTester = if (com.example.chessmentor.BuildConfig.DEBUG) ({
                if (!isLoading) {
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            container.testerProfiles.open(onlyIfEmpty = false)?.let { gameViewModel.setUser(it) }
                            regPassword = ""
                            loginPassword = ""
                            message = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Не удалось открыть тестовый профиль"
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }) else null,
            onRegister = { nick, email, pass, rating ->
                coroutineScope.launch {
                    isLoading = true
                    try {
                        when (val result = container.registerUserUseCase.execute(
                            RegisterUserUseCase.Input(nick, email, pass, rating)
                        )) {
                            is RegisterUserUseCase.Result.Success -> {
                                regPassword = ""
                                message = ""
                                gameViewModel.setUser(result.user)
                            }
                            is RegisterUserUseCase.Result.Error -> {
                                message = "Ошибка: ${result.message}"
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Ошибка: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            },
            onLogin = { email, pass ->
                coroutineScope.launch {
                    isLoading = true
                    try {
                        when (val result = container.loginUserUseCase.execute(
                            LoginUserUseCase.Input(email, pass)
                        )) {
                            is LoginUserUseCase.Result.Success -> {
                                loginPassword = ""
                                message = ""
                                gameViewModel.setUser(result.user)
                            }
                            is LoginUserUseCase.Result.Error -> {
                                message = "Ошибка: ${result.message}"
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Ошибка: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChessMentorTopBar(
    currentScreen: String,
    currentUser: User?,
    onNavigateBack: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconPainter = remember {
                    val res = when (currentScreen) {
                        "games" -> R.drawable.ic_mentor_games
                        "upload" -> R.drawable.ic_mentor_upload
                        "game_view" -> R.drawable.ic_mentor_search
                        "summary" -> R.drawable.ic_mentor_emoji_events
                        "training" -> R.drawable.ic_mentor_training
                        "stats" -> R.drawable.ic_mentor_statistics
                        "settings" -> R.drawable.ic_mentor_settings
                        "engine_settings" -> R.drawable.ic_mentor_settings
                        else -> R.drawable.ic_mentor_home
                    }
                    rememberVectorPainter(vectorResource(res))
                }
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = when (currentScreen) {
                        "games" -> "Мои партии"
                        "upload" -> "Загрузить партию"
                        "game_view" -> "Разбор партии"
                        "summary" -> "Сводка анализа"
                        "training" -> "Тренировка"
                        "stats" -> "Статистика"
                        "settings" -> "Настройки"
                        "engine_settings" -> "Настройки движка"
                        else -> "Шахматный Ментор"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        navigationIcon = {
            if (currentScreen != "home" && currentUser != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        painter = rememberVectorPainter(vectorResource(R.drawable.ic_mentor_arrow_back)),
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

private enum class MainTab(val screen: String, val label: String, val iconRes: Int) {
    HOME("home", "Главная", R.drawable.ic_mentor_home),
    GAMES("games", "Партии", R.drawable.ic_mentor_games),
    TASKS("training", "Задачи", R.drawable.ic_mentor_training),
    STATS("stats", "Статистика", R.drawable.ic_mentor_statistics),
    SETTINGS("settings", "Настройки", R.drawable.ic_mentor_settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChessMentorBottomBar(currentScreen: String, dueReviewCount: Int, onNavigate: (String) -> Unit) {
    val section = when (currentScreen) {
        "summary", "upload", "game_view" -> "games"
        "settings", "engine_settings" -> "settings"
        else -> currentScreen
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    // Give longer names proportionally more room, without overriding system font scale.
    val widths = MainTab.entries.map { tab ->
        with(density) { (measurer.measure(tab.label, style, maxLines = 1).size.width.toDp() + 16.dp).coerceAtLeast(64.dp) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = widths.sumOf { it.value.toDouble() }.toFloat()
        val compact = total > maxWidth.value
        NavigationBar {
            MainTab.entries.forEachIndexed { i, tab ->
                val selected = section == tab.screen
                val showLabel = !compact || selected
                val showBadge = tab.screen == "training" && dueReviewCount > 0
                NavigationBarItem(
                    modifier = Modifier.weight(if (compact && !selected) 48f else widths[i].value),
                    icon = {
                        val iconPainter = rememberVectorPainter(vectorResource(tab.iconRes))
                        Box(Modifier.size(24.dp)) {
                            Icon(
                                painter = iconPainter,
                                contentDescription = if (showLabel) null else tab.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showBadge) {
                                Badge(
                                    badgeContent = {
                                        Text(
                                            text = dueReviewCount.coerceAtMost(9).toString(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    },
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = -4.dp)
                                )
                            }
                        }
                    },
                    label = if (showLabel) { { Text(tab.label, style = style, maxLines = 1) } } else null,
                    selected = selected,
                    onClick = { onNavigate(tab.screen) }
                )
            }
        }
    }
}

@Composable
private fun NavigationHost(
    paddingValues: PaddingValues,
    currentScreen: String,
    isRestoringSession: Boolean,
    pendingTrainingModeKey: String?,
    pendingGamesSearchQuery: String?,
    pendingGamesColor: ChessColor?,
    currentUser: User?,
    gameViewModel: GameViewModel,
    boardViewModel: BoardViewModel,
    container: AppContainer,
    message: String,
    isLoading: Boolean,
    regNickname: String,
    regEmail: String,
    regPassword: String,
    regRating: String,
    loginEmail: String,
    loginPassword: String,
    onScreenChange: (String) -> Unit,
    onOpenEngineSettings: (fromScreen: String) -> Unit,
    onCloseEngineSettings: () -> Unit,
    onOpenTrainingWithMode: (TrainingMode) -> Unit,
    onOpenGameMistakeTraining: (fromScreen: String, gameId: Long, focusMoveNumber: Int?) -> Unit,
    onLeaveTraining: () -> Unit,
    onReturnToGameFromTraining: (() -> Unit)?,
    onTrainingModeConsumed: () -> Unit,
    onOpenGamesWithFilter: (query: String, color: ChessColor?) -> Unit,
    onGamesFilterConsumed: () -> Unit,
    onOpenGameSummaryById: (fromScreen: String, gameId: Long) -> Unit,
    onUserUpdated: (User) -> Unit,
    onLogout: () -> Unit,
    onMessageDismiss: () -> Unit,
    onRegNicknameChange: (String) -> Unit,
    onRegEmailChange: (String) -> Unit,
    onRegPasswordChange: (String) -> Unit,
    onRegRatingChange: (String) -> Unit,
    onLoginEmailChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit,
    onOpenTester: (() -> Unit)?,
    onRegister: (String, String, String, Int) -> Unit,
    onLogin: (String, String) -> Unit
) {
    if (isRestoringSession) {
        SessionRestoringView(paddingValues = paddingValues)
        return
    }

    // Keep the summary's lazy-list and filter only for the current review route.
    val reviewGameId = when (currentScreen) {
        "summary", "game_view", "training" -> gameViewModel.selectedGame.value?.id
        else -> null
    }
    val summaryState = key(reviewGameId) { rememberSaveableStateHolder() }

    AnimatedContentSwitcher(key = currentScreen) {
        when (currentScreen) {
            "home" -> {
            if (currentUser == null) {
                AuthScreen(
                    paddingValues = paddingValues,
                    message = message,
                    isLoading = isLoading,
                    onMessageDismiss = onMessageDismiss,
                    onOpenTester = onOpenTester,
                    onRegister = onRegister,
                    onLogin = onLogin,
                    regNickname = regNickname,
                    regEmail = regEmail,
                    regPassword = regPassword,
                    regRating = regRating,
                    loginEmail = loginEmail,
                    loginPassword = loginPassword,
                    onRegNicknameChange = onRegNicknameChange,
                    onRegEmailChange = onRegEmailChange,
                    onRegPasswordChange = onRegPasswordChange,
                    onRegRatingChange = onRegRatingChange,
                    onLoginEmailChange = onLoginEmailChange,
                    onLoginPasswordChange = onLoginPasswordChange
                )
            } else {
                val user = currentUser
                DashboardScreen(
                    paddingValues = paddingValues,
                    user = user,
                    gameViewModel = gameViewModel,
                    container = container,
                    onLogout = onLogout,
                    onNavigateToUpload = { onScreenChange("upload") },
                    onNavigateToDailyPuzzle = { onOpenTrainingWithMode(TrainingMode.Daily) },
                    onNavigateToSmartSession = { onOpenTrainingWithMode(TrainingMode.SmartSession) },
                    onOpenEngineSettings = { onOpenEngineSettings("home") },
                    onOpenGame = { game ->
                        gameViewModel.selectGame(game)
                        onScreenChange("summary")
                    },
                    onNavigateToSettings = { onScreenChange("settings") }
                )
            }
        }

        "games" -> GamesListScreen(
            paddingValues = paddingValues,
            gameViewModel = gameViewModel,
            container = container,
            onGameClick = { game ->
                gameViewModel.selectGame(game)
                onScreenChange("summary")
            },
            onNavigateToUpload = { onScreenChange("upload") },
            onAnalysisComplete = { onScreenChange("summary") },
            onOpenEngineSettings = { onOpenEngineSettings("games") },
            initialSearchQuery = pendingGamesSearchQuery,
            initialColorFilter = pendingGamesColor,
            onInitialFilterConsumed = onGamesFilterConsumed
        )

        "game_view" -> {
            val game = gameViewModel.selectedGame.value
            if (game != null) {
                GameViewScreen(
                    paddingValues = paddingValues,
                    gameViewModel = gameViewModel,
                    user = currentUser,
                    boardViewModel = boardViewModel,
                    chessEngine = container.chessEngine,
                    onOpenSummary = {
                        boardViewModel.dismissEngineLine()
                        onScreenChange("summary")
                    },
                    onPracticeMistake = { gameId, moveNumber ->
                        onOpenGameMistakeTraining("game_view", gameId, moveNumber)
                    },
                    onNavigateBack = handleNavigateBack
                )
            } else {
                LaunchedEffect(Unit) {
                    onScreenChange("games")
                }
            }
        }

        "summary" -> {
            val game = gameViewModel.selectedGame.value

            if (game != null) {
                val mistakes = gameViewModel.selectedGameMistakes
                val analyzedMoves = gameViewModel.selectedGameAnalyzedMoves
                val evaluations = gameViewModel.selectedGameEvaluations

                LaunchedEffect(game.id, game.analyzedAt, mistakes.size, analyzedMoves.size, evaluations.size) {
                    boardViewModel.loadGame(
                        game = game,
                        gameMistakes = mistakes.toList(),
                        gameAnalyzedMoves = analyzedMoves.toList(),
                        gameEvaluations = evaluations.toList(),
                        preserveMoveIndex = boardViewModel.getLoadedGameId() == game.id
                    )
                }

                val keyMoments by remember(game.id, game.playerColor) {
                    derivedStateOf { boardViewModel.getKeyMoments() }
                }

                summaryState.SaveableStateProvider("summary") {
                    SummaryScreen(
                        paddingValues = paddingValues,
                        game = game,
                        keyMoments = keyMoments,
                        boardViewModel = boardViewModel,
                        gameViewModel = gameViewModel,
                        onReviewClick = {
                            onScreenChange("game_view")
                        },
                        onPracticeGameMistakes = { gameId, focusMove ->
                            onOpenGameMistakeTraining("summary", gameId, focusMove)
                        }
                    )
                }
            } else {
                LaunchedEffect(Unit) {
                    Log.w(TAG, "SummaryScreen: No game selected")
                    onScreenChange("games")
                }
            }
        }

        "upload" -> UploadGameScreen(
            paddingValues = paddingValues,
            gameViewModel = gameViewModel,
            engineSettingsRepository = container.engineSettingsRepository,
            onGameUploaded = { },
            onAnalysisComplete = { onScreenChange("summary") },
            onOpenEngineSettings = { onOpenEngineSettings("upload") },
            onNavigateToLibrary = { onScreenChange("games") },
            onOpenExistingGame = { id -> onOpenGameSummaryById("upload", id) },
            chessEngine = container.chessEngine
        )

        "training" -> {
            val user = currentUser
            if (user?.id != null) {
                TrainingScreen(
                    paddingValues = paddingValues,
                    container = container,
                    userId = user.id,
                    preferredTheme = user.preferredTheme,
                    initialSoundEnabled = user.isSoundEnabled,
                    initialModeKey = pendingTrainingModeKey,
                    onModeConsumed = onTrainingModeConsumed,
                    onLeaveTraining = onLeaveTraining,
                    onNavigateToUpload = { onScreenChange("upload") },
                    onReturnToGame = onReturnToGameFromTraining,
                    onUserUpdated = onUserUpdated,
                    onDueReviewCountChange = { dueReviewCount = it }
                )
            } else {
                LaunchedEffect(Unit) {
                    onScreenChange("home")
                }
            }
        }

        "stats" -> {
            currentUser?.let { user ->
                StatisticsScreen(
                    paddingValues = paddingValues,
                    user = user,
                    container = container,
                    onPracticeWeakPatterns = {
                        onOpenTrainingWithMode(TrainingMode.WeakPatterns)
                    },
                    onStartTraining = { mode ->
                        onOpenTrainingWithMode(mode)
                    },
                    onOpenGamesLibrary = { query, color ->
                        onOpenGamesWithFilter(query, color)
                    },
                    onOpenGameById = { gameId ->
                        onOpenGameSummaryById("stats", gameId)
                    },
                    onNavigateToUpload = {
                        onScreenChange("upload")
                    }
                )
            } ?: LaunchedEffect(Unit) {
                onScreenChange("home")
            }
        }

        "settings" -> {
            currentUser?.let { user ->
                SettingsScreen(
                    paddingValues = paddingValues,
                    user = user,
                    container = container,
                    onLogout = onLogout,
                    onUserUpdated = onUserUpdated,
                    onOpenEngineSettings = { onOpenEngineSettings("settings") },
                    onDataRestored = { gameViewModel.loadUserData() }
                )
            } ?: LaunchedEffect(Unit) {
                onScreenChange("home")
            }
        }

        "engine_settings" -> EngineSettingsScreen(
            paddingValues = paddingValues,
            engineSettingsRepository = container.engineSettingsRepository,
            onBack = onCloseEngineSettings,
            chessEngine = container.chessEngine,
            onRetryDownload = { gameViewModel.retryEngineInit(forceRedownload = false) },
            onForceRedownload = { gameViewModel.retryEngineInit(forceRedownload = true) },
            onVerifyNetwork = { gameViewModel.verifyEngineNetwork() },
            onCancelDownload = { gameViewModel.cancelEngineInit() }
        )
    }
}
}

@Composable
private fun SessionRestoringView(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Text(
                text = "Загрузка профиля…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
