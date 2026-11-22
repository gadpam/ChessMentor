// presentation/ui/screen/HomeScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.example.chessmentor.R
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.example.chessmentor.presentation.ui.components.CommonUI.BrandEmptyState
import com.example.chessmentor.presentation.ui.components.CommonUI.BrandButton
import com.example.chessmentor.presentation.ui.components.CommonUI.MessageCard
import com.example.chessmentor.presentation.ui.components.CommonUI.MessageType
import com.example.chessmentor.presentation.ui.components.EngineStatusBanner
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.github.bhlangonijr.chesslib.Board

@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    currentUser: User?,
    gameViewModel: GameViewModel,
    container: AppContainer? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToTraining: () -> Unit,
    onNavigateToUpload: () -> Unit,
    onNavigateToDailyPuzzle: () -> Unit = onNavigateToTraining,
    onNavigateToSmartSession: () -> Unit = onNavigateToTraining,
    onOpenEngineSettings: (() -> Unit)? = null,
    onOpenGame: (Game) -> Unit = {},
    message: String,
    isLoading: Boolean,
    onMessageDismiss: () -> Unit,
    onLogout: () -> Unit,
    onOpenTester: (() -> Unit)?,
    onRegister: (String, String, String, Int) -> Unit,
    onLogin: (String, String) -> Unit,
    regNickname: String,
    regEmail: String,
    regPassword: String,
    regRating: String,
    loginEmail: String,
    loginPassword: String,
    onRegNicknameChange: (String) -> Unit,
    onRegEmailChange: (String) -> Unit,
    onRegPasswordChange: (String) -> Unit,
    onRegRatingChange: (String) -> Unit,
    onLoginEmailChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit
) {
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
        DashboardScreen(
            paddingValues = paddingValues,
            user = currentUser,
            gameViewModel = gameViewModel,
            container = container,
            onLogout = onLogout,
            onNavigateToUpload = onNavigateToUpload,
            onNavigateToDailyPuzzle = onNavigateToDailyPuzzle,
            onNavigateToSmartSession = onNavigateToSmartSession,
            onOpenEngineSettings = onOpenEngineSettings,
            onOpenGame = onOpenGame,
            onNavigateToSettings = onNavigateToSettings
        )
    }
}

@Composable
fun AuthScreen(
    paddingValues: PaddingValues,
    message: String,
    isLoading: Boolean,
    onMessageDismiss: () -> Unit,
    onOpenTester: (() -> Unit)?,
    onRegister: (String, String, String, Int) -> Unit,
    onLogin: (String, String) -> Unit,
    regNickname: String,
    regEmail: String,
    regPassword: String,
    regRating: String,
    loginEmail: String,
    loginPassword: String,
    onRegNicknameChange: (String) -> Unit,
    onRegEmailChange: (String) -> Unit,
    onRegPasswordChange: (String) -> Unit,
    onRegRatingChange: (String) -> Unit,
    onLoginEmailChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf("login") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Сообщение
        if (message.isNotEmpty()) {
            item {
                MessageCard(
                    type = if (message.startsWith("Ошибка")) MessageType.Error else MessageType.Info,
                    title = if (message.startsWith("Ошибка")) "Ошибка" else "Информация",
                    message = message,
                    onDismiss = onMessageDismiss
                )
            }
        }

        if (onOpenTester != null) {
            item {
                OutlinedButton(onClick = onOpenTester, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("Войти как тестировщик")
                }
            }
        }

        // Табы
        item {
            AuthTabs(
                currentTab = currentTab,
                isLoading = isLoading,
                onTabChange = { currentTab = it; onMessageDismiss() }
            )
        }

        // Форма
        item {
            AuthForm(
                currentTab = currentTab,
                isLoading = isLoading,
                loginEmail = loginEmail,
                loginPassword = loginPassword,
                regNickname = regNickname,
                regEmail = regEmail,
                regPassword = regPassword,
                regRating = regRating,
                onLoginEmailChange = onLoginEmailChange,
                onLoginPasswordChange = onLoginPasswordChange,
                onRegNicknameChange = onRegNicknameChange,
                onRegEmailChange = onRegEmailChange,
                onRegPasswordChange = onRegPasswordChange,
                onRegRatingChange = onRegRatingChange,
                onLogin = onLogin,
                onRegister = onRegister
            )
        }
    }
}

@Composable
private fun AuthTabs(
    currentTab: String,
    isLoading: Boolean,
    onTabChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = { onTabChange("login") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentTab == "login") MaterialTheme.colorScheme.surface
                else Color.Transparent,
                contentColor = if (currentTab == "login") MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = if (currentTab == "login") ButtonDefaults.buttonElevation(2.dp)
            else ButtonDefaults.buttonElevation(0.dp),
            enabled = !isLoading
        ) { Text("Вход") }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { onTabChange("register") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentTab == "register") MaterialTheme.colorScheme.surface
                else Color.Transparent,
                contentColor = if (currentTab == "register") MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = if (currentTab == "register") ButtonDefaults.buttonElevation(2.dp)
            else ButtonDefaults.buttonElevation(0.dp),
            enabled = !isLoading
        ) { Text("Регистрация") }
    }
}

@Composable
private fun AuthForm(
    currentTab: String,
    isLoading: Boolean,
    loginEmail: String,
    loginPassword: String,
    regNickname: String,
    regEmail: String,
    regPassword: String,
    regRating: String,
    onLoginEmailChange: (String) -> Unit,
    onLoginPasswordChange: (String) -> Unit,
    onRegNicknameChange: (String) -> Unit,
    onRegEmailChange: (String) -> Unit,
    onRegPasswordChange: (String) -> Unit,
    onRegRatingChange: (String) -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String, Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (currentTab == "login") {
                LoginForm(
                    isLoading = isLoading,
                    email = loginEmail,
                    password = loginPassword,
                    onEmailChange = onLoginEmailChange,
                    onPasswordChange = onLoginPasswordChange,
                    onLogin = onLogin
                )
            } else {
                RegisterForm(
                    isLoading = isLoading,
                    nickname = regNickname,
                    email = regEmail,
                    password = regPassword,
                    rating = regRating,
                    onNicknameChange = onRegNicknameChange,
                    onEmailChange = onRegEmailChange,
                    onPasswordChange = onRegPasswordChange,
                    onRatingChange = onRegRatingChange,
                    onRegister = onRegister
                )
            }
        }
    }
}

@Composable
private fun LoginForm(
    isLoading: Boolean,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: (String, String) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = !isLoading && email.isNotBlank() && password.isNotBlank()

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        leadingIcon = { Icon(Icons.Default.Email, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль") },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль"
                )
            }
        },
        singleLine = true,
        visualTransformation = if (showPassword) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (canSubmit) onLogin(email.trim(), password)
            }
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    Button(
        onClick = { onLogin(email.trim(), password) },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = canSubmit
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp
            )
        } else {
            Text("Войти", fontSize = 16.sp)
        }
    }
}

@Composable
private fun RegisterForm(
    isLoading: Boolean,
    nickname: String,
    email: String,
    password: String,
    rating: String,
    onNicknameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRatingChange: (String) -> Unit,
    onRegister: (String, String, String, Int) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    val trimmedNick = nickname.trim()
    val trimmedEmail = email.trim()
    val isEmailValid = trimmedEmail.contains("@") &&
        trimmedEmail.substringAfter("@").contains(".") &&
        !trimmedEmail.contains(" ")
    val isFormValid = trimmedNick.length in 3..50 && isEmailValid && password.length >= 6

    OutlinedTextField(
        value = nickname,
        onValueChange = { if (it.length <= 50) onNicknameChange(it) },
        label = { Text("Имя в ChessMentor") },
        leadingIcon = { Icon(Icons.Default.Person, null) },
        singleLine = true,
        isError = nickname.isNotEmpty() && trimmedNick.length < 3,
        supportingText = if (nickname.isNotEmpty() && trimmedNick.length < 3) {
            { Text("Минимум 3 символа") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        leadingIcon = { Icon(Icons.Default.Email, null) },
        singleLine = true,
        isError = trimmedEmail.isNotEmpty() && !isEmailValid,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль (не менее 6 символов)") },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль"
                )
            }
        },
        singleLine = true,
        isError = password.isNotEmpty() && password.length < 6,
        visualTransformation = if (showPassword) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    var adjustRating by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { adjustRating = !adjustRating }, enabled = !isLoading) {
        Text(if (adjustRating) "Скрыть настройку сложности" else "Настроить стартовую сложность задач")
    }
    if (adjustRating) {
        OutlinedTextField(
            value = rating,
            onValueChange = { value ->
                if (value.length <= 4 && value.all { it.isDigit() }) {
                    onRatingChange(value)
                }
            },
            label = { Text("Стартовая оценка для подбора задач (0–3500)") },
            leadingIcon = { Icon(Icons.Default.Star, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isLoading && isFormValid) {
                        val parsedRating = (rating.toIntOrNull() ?: 1200).coerceIn(0, 3500)
                        onRegister(trimmedNick, trimmedEmail, password, parsedRating)
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )

        // Быстрый выбор стартового уровня
        val presets = remember { listOf("800", "1200", "1500", "1800") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = rating == preset,
                    onClick = { onRatingChange(preset) },
                    label = { Text(preset, fontSize = 12.sp) },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
        }

    }

    Button(
        onClick = {
            val parsedRating = (rating.toIntOrNull() ?: 1200).coerceIn(0, 3500)
            onRegister(trimmedNick, trimmedEmail, password, parsedRating)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && isFormValid
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp
            )
        } else {
            Text("Зарегистрироваться", fontSize = 16.sp)
        }
    }
}

// ==================== DASHBOARD ====================

@Composable
fun DashboardScreen(
    paddingValues: PaddingValues,
    user: User,
    gameViewModel: GameViewModel,
    container: AppContainer? = null,
    @Suppress("UNUSED_PARAMETER")
    onLogout: () -> Unit,
    onNavigateToUpload: () -> Unit,
    onNavigateToDailyPuzzle: () -> Unit,
    onNavigateToSmartSession: () -> Unit = onNavigateToDailyPuzzle,
    onOpenEngineSettings: (() -> Unit)? = null,
    onOpenGame: (Game) -> Unit = {},
    onNavigateToSettings: () -> Unit
) {
    val epochDay = remember { CuratedExerciseBank.currentEpochDay() }
    val dailyPuzzle = remember(epochDay) { CuratedExerciseBank.getDailyPuzzle(epochDay) }
    var isDailySolved by remember(user.id, epochDay) { mutableStateOf(false) }

    val engineStatus by if (container != null) {
        container.chessEngine.status.collectAsState()
    } else {
        remember { mutableStateOf<EngineStatus>(EngineStatus.Idle) }
    }

    LaunchedEffect(user.id, epochDay) {
        gameViewModel.loadUserData()
        val uid = user.id
        if (uid != null && container != null) {
            isDailySolved = runCatching {
                val existing = container.exerciseRepository.findGlobalByFen(dailyPuzzle.fen)
                val exId = existing?.id
                exId != null && container.exerciseRepository.hasUserSolvedExercise(uid, exId)
            }.getOrDefault(false)
        }
    }

    val recentGames = gameViewModel.userGames.take(2)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            top = 2.dp + paddingValues.calculateTopPadding(),
            bottom = 16.dp + paddingValues.calculateBottomPadding(),
            start = 16.dp,
            end = 16.dp
        )
    ) {
        item {
            UserHeader(
                user = user,
                onSettingsClick = onNavigateToSettings
            )
        }

        item {
            DailyPuzzleWidget(
                puzzle = dailyPuzzle,
                isSolved = isDailySolved,
                preferredTheme = user.preferredTheme,
                onClick = onNavigateToDailyPuzzle,
                onStartSmartSession = onNavigateToSmartSession
            )
        }

        // Живой баннер загрузки/проверки или ошибки NNUE (автоматически скрывается в состоянии Ready)
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

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Недавние партии", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                AddGameButton(onNavigateToUpload)
            }
        }

        if (recentGames.isEmpty()) {
            item {
                BrandEmptyState(
                    painter = painterResource(R.drawable.ic_empty_games),
                    title = "Партій ещё нет",
                    subtitle = "Загрузите партию или начните новую игру, чтобы начать отслеживать свой прогресс.",
                    onAction = onNavigateToUpload,
                    actionLabel = "Загрузить партию"
                )
            }
        } else {
            items(
                items = recentGames,
                key = { g -> g.id ?: g.hashCode() }
            ) { game ->
                MiniGameCard(
                    game = game,
                    onOpen = onOpenGame
                )
            }
        }

        item {
            TipCard()
        }
    }
}

@Composable
internal fun UserHeader(
    user: User,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp).clearAndSetSemantics { }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = user.nickname.firstOrNull()?.uppercase() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }

        Text(
            user.nickname,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        IconButton(onClick = onSettingsClick, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "Открыть настройки")
        }
    }
}

@Composable
fun DailyPuzzleWidget(
    puzzle: com.example.chessmentor.domain.training.CuratedPuzzle = remember {
        CuratedExerciseBank.getDailyPuzzle()
    },
    isSolved: Boolean = false,
    preferredTheme: String? = null,
    onClick: () -> Unit,
    onStartSmartSession: (() -> Unit)? = null
) {
    val board = remember(puzzle.fen) {
        Board().apply { loadFromFen(puzzle.fen) }
    }
    val boardTheme = remember(preferredTheme) {
        BoardThemes.getByName(preferredTheme)
    }
    val difficultyLabel = remember(puzzle.difficultyRating) {
        TrainingLabels.difficultyLabel(puzzle.difficultyRating)
    }
    val sidePrompt = if (puzzle.isWhiteToMove) "Ход белых" else "Ход чёрных"
    val dailyAction = if (isSolved) "Повторить задачу" else "Решить задачу дня"


    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChessBoard(
                        board = board, theme = boardTheme, flipped = !puzzle.isWhiteToMove,
                        animateMove = false, showCoordinates = false, onSquareClick = null,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 172.dp).aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClickLabel = dailyAction, onClick = onClick)
                            .semantics { contentDescription = "Позиция задачи дня. $sidePrompt" }
                    )
                    Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ЕЖЕДНЕВНАЯ ПРАКТИКА", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Задача дня", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (isSolved) puzzle.title else sidePrompt, style = MaterialTheme.typography.bodyMedium)
                        Text(if (isSolved) "Решено · $difficultyLabel" else difficultyLabel,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                    Text(dailyAction, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (onStartSmartSession != null) {
            OutlinedCard(onClick = onStartSmartSession, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.FitnessCenter, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Начать тренировку", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("10 задач · от разминки до расчёта", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}

@Composable
fun MiniGameCard(game: Game, onOpen: (Game) -> Unit) {
    GameCard(game = game, showDate = true, showNumber = true, onClick = { onOpen(game) })
}

@Composable
private fun TipCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = LocalChessPalette.current.accentBackground
        ),
        border = BorderStroke(1.dp, LocalChessPalette.current.caution.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = "Совет тренера",
                tint = LocalChessPalette.current.caution,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Совет тренера: Контролируйте центр доски пешками в начале партии — это даёт пространство для фигур и ограничивает противника.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
