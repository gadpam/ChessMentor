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
import com.example.chessmentor.domain.entity.User
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
    // Пользователь хранится в ViewModel, поэтому сессия сохраняется при повороте экрана
    val currentUser by gameViewModel.currentUser
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var isLoading by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }

    // Форма регистрации (пароли не сохраняем в Bundle)
    var regNickname by rememberSaveable { mutableStateOf("") }
    var regEmail by rememberSaveable { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regRating by rememberSaveable { mutableStateOf("1200") }

    // Форма входа (без захардкоженных тестовых учётных данных)
    var loginEmail by rememberSaveable { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val viewModelFactory = remember(container) { ChessMentorViewModelFactory(container) }
    val boardViewModel: BoardViewModel = viewModel(factory = viewModelFactory)

    val handleNavigateBack: () -> Unit = {
        currentScreen = when (currentScreen) {
            "game_view" -> "summary"
            "summary" -> {
                gameViewModel.closeGameView()
                "games"
            }
            "engine_settings" -> "upload"
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
            onUserUpdated = { updated ->
                gameViewModel.updateCurrentUser(updated)
            },
            onLogout = {
                gameViewModel.logout()
                loginPassword = ""
                regPassword = ""
                message = ""
                currentScreen = "home"
            },
            onMessageDismiss = { message = "" },
            onRegNicknameChange = { regNickname = it },
            onRegEmailChange = { regEmail = it },
            onRegPasswordChange = { regPassword = it },
            onRegRatingChange = { regRating = it },
            onLoginEmailChange = { loginEmail = it },
            onLoginPasswordChange = { loginPassword = it },
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
    onUserUpdated: (User) -> Unit,
    onLogout: () -> Unit,
    onMessageDismiss: () -> Unit,
    onRegNicknameChange: (String) -> Unit,
    onRegEmailChange: (String) -> Unit,
    onRegPasswordChange: (String) -> Unit,
    onRegRatingChange: (String) -> Unit,
    onLoginEmailChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit,
    onRegister: (String, String, String, Int) -> Unit,
    onLogin: (String, String) -> Unit
) {
    when (currentScreen) {
        "home" -> HomeScreen(
            paddingValues = paddingValues,
            currentUser = currentUser,
            gameViewModel = gameViewModel,
            onNavigateToSettings = { onScreenChange("settings") },
            onNavigateToTraining = { onScreenChange("training") },
            message = message,
            isLoading = isLoading,
            onMessageDismiss = onMessageDismiss,
            onLogout = onLogout,
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
            }
        )

        "game_view" -> {
            val game = gameViewModel.selectedGame.value
            if (game != null) {
                GameViewScreen(
                    paddingValues = paddingValues,
                    gameViewModel = gameViewModel,
                    user = currentUser,
                    boardViewModel = boardViewModel
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

                LaunchedEffect(game.id, mistakes.size, analyzedMoves.size, evaluations.size) {
                    boardViewModel.loadGame(
                        game = game,
                        gameMistakes = mistakes.toList(),
                        gameAnalyzedMoves = analyzedMoves.toList(),
                        gameEvaluations = evaluations.toList(),
                        preserveMoveIndex = boardViewModel.getLoadedGameId() == game.id
                    )
                }

                val keyMoments = remember(game.id, boardViewModel.analyzedMoves.size) {
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
            onOpenEngineSettings = { onScreenChange("engine_settings") }
        )

        "training" -> {
            val user = currentUser
            if (user?.id != null) {
                TrainingScreen(
                    paddingValues = paddingValues,
                    container = container,
                    userId = user.id,
                    preferredTheme = user.preferredTheme,
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
                    container = container
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
                    onUserUpdated = onUserUpdated
                )
            } ?: LaunchedEffect(Unit) {
                onScreenChange("home")
            }
        }

        "engine_settings" -> EngineSettingsScreen(
            paddingValues = paddingValues,
            engineSettingsRepository = container.engineSettingsRepository,
            onBack = { onScreenChange("upload") }
        )
    }
}
