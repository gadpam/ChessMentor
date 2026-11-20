// presentation/ui/components/navigation/ChessMentorNavigation.kt
package com.example.chessmentor.presentation.ui.components.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.chessmentor.presentation.ui.components.screen.EngineSettingsScreen
import com.example.chessmentor.presentation.ui.components.screen.GameViewScreen
import com.example.chessmentor.presentation.ui.components.screen.GamesListScreen
import com.example.chessmentor.presentation.ui.components.screen.HomeScreen
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
    var summaryReturnScreen by rememberSaveable { mutableStateOf("games") }
    var engineSettingsReturnScreen by rememberSaveable { mutableStateOf("upload") }
    var isLoading by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }

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
                val target = summaryReturnScreen.takeIf { it in setOf("games", "stats", "home") } ?: "games"
                summaryReturnScreen = "games"
                target
            }
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
            ChessMentorTopBar(
                currentScreen = currentScreen,
                currentUser = currentUser,
                onNavigateBack = handleNavigateBack
            )
        },
        bottomBar = {
            if (currentUser != null && currentScreen !in setOf("game_view", "engine_settings")) {
                ChessMentorBottomBar(
                    currentScreen = currentScreen,
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
                                message = "❌ ${result.message}"
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "❌ Ошибка: ${e.message}"
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
                                message = "❌ ${result.message}"
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "❌ Ошибка: ${e.message}"
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
                val icon = when (currentScreen) {
                    "games" -> Icons.Default.List
                    "upload" -> Icons.Default.Upload
                    "game_view" -> Icons.Default.Search
                    "summary" -> Icons.Default.Insights
                    "training" -> Icons.Default.FitnessCenter
                    "stats" -> Icons.Default.Analytics
                    "settings" -> Icons.Default.Settings
                    "engine_settings" -> Icons.Default.Tune
                    else -> Icons.Default.SportsEsports
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад"
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

@Composable
private fun ChessMentorBottomBar(
    currentScreen: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Home, contentDescription = "Главная") },
            label = { Text("Главная") },
            selected = currentScreen == "home",
            onClick = { onNavigate("home") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.List, contentDescription = "Партии") },
            label = { Text("Партии") },
            selected = currentScreen in setOf("games", "summary"),
            onClick = { onNavigate("games") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.FitnessCenter, contentDescription = "Тренировка") },
            label = { Text("Тренировка") },
            selected = currentScreen == "training",
            onClick = { onNavigate("training") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Upload, contentDescription = "Загрузить") },
            label = { Text("Загрузить") },
            selected = currentScreen == "upload",
            onClick = { onNavigate("upload") }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Analytics, contentDescription = "Статистика") },
            label = { Text("Статистика") },
            selected = currentScreen == "stats",
            onClick = { onNavigate("stats") }
        )
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

    when (currentScreen) {
        "home" -> HomeScreen(
            paddingValues = paddingValues,
            currentUser = currentUser,
            gameViewModel = gameViewModel,
            container = container,
            onNavigateToSettings = { onScreenChange("settings") },
            onNavigateToTraining = { onScreenChange("training") },
            onNavigateToDailyPuzzle = { onOpenTrainingWithMode(TrainingMode.Daily) },
            onNavigateToSmartSession = { onOpenTrainingWithMode(TrainingMode.SmartSession) },
            onOpenEngineSettings = { onOpenEngineSettings("home") },
            onOpenGame = { game ->
                gameViewModel.selectGame(game)
                onScreenChange("summary")
            },
            message = message,
            isLoading = isLoading,
            onMessageDismiss = onMessageDismiss,
            onLogout = onLogout,
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
                    }
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

                val keyMoments = remember(game.id, game.analyzedAt, boardViewModel.analyzedMoves.size) {
                    boardViewModel.getKeyMoments()
                }

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
            onOpenExistingGame = { existing ->
                gameViewModel.selectGame(existing)
                onScreenChange("summary")
            },
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
                    onNavigateToUpload = { onScreenChange("upload") },
                    onReturnToGame = onReturnToGameFromTraining,
                    onUserUpdated = onUserUpdated
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
