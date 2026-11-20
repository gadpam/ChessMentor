// presentation/ui/screen/HomeScreen.kt
package com.example.chessmentor.presentation.ui.components.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.engine.EngineStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.TrainingLabels
import com.example.chessmentor.presentation.ui.components.BoardThemes
import com.example.chessmentor.presentation.ui.components.ChessBoard
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
        AuthScreenContent(
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
        DashboardContent(
            paddingValues = paddingValues,
            user = currentUser,
            gameViewModel = gameViewModel,
            container = container,
            onLogout = onLogout,
            onNavigateToDailyPuzzle = onNavigateToDailyPuzzle,
            onNavigateToSmartSession = onNavigateToSmartSession,
            onOpenEngineSettings = onOpenEngineSettings,
            onOpenGame = onOpenGame,
            onNavigateToSettings = onNavigateToSettings
        )
    }
}

@Composable
private fun AuthScreenContent(
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
        // Приветствие
        item {
            WelcomeCard()
        }

        // Сообщение
        if (message.isNotEmpty()) {
            item {
                MessageCard(
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
private fun WelcomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Добро пожаловать!", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Войдите, чтобы начать")
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: String,
    onDismiss: () -> Unit
) {
    val isError = message.startsWith("❌") || message.startsWith("Ошибка")
    val cleanMessage = message.replace("✅ ", "").replace("❌ ", "")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                null,
                tint = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                cleanMessage,
                modifier = Modifier.weight(1f),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null)
            }
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

    Text(
        "С возвращением!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

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

    Text(
        "Создать аккаунт",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    OutlinedTextField(
        value = nickname,
        onValueChange = { if (it.length <= 50) onNicknameChange(it) },
        label = { Text("Никнейм (или ник на Lichess / Chess.com)") },
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
        Text(if (adjustRating) "Скрыть выбор рейтинга" else "Стартовый рейтинг: ${rating.ifBlank { "1200" }} · изменить")
    }
    if (adjustRating) {
        OutlinedTextField(
            value = rating,
            onValueChange = { value ->
                if (value.length <= 4 && value.all { it.isDigit() }) {
                    onRatingChange(value)
                }
            },
            label = { Text("Примерный рейтинг (0–3500)") },
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
fun DashboardContent(
    paddingValues: PaddingValues,
    user: User,
    gameViewModel: GameViewModel,
    container: AppContainer? = null,
    @Suppress("UNUSED_PARAMETER")
    onLogout: () -> Unit,
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
            DailyPuzzleWidget(
                puzzle = dailyPuzzle,
                isSolved = isDailySolved,
                preferredTheme = user.preferredTheme,
                onClick = onNavigateToDailyPuzzle,
                onStartSmartSession = onNavigateToSmartSession
            )
        }

        item {
            Text(
                "Недавние партии",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (recentGames.isEmpty()) {
            item {
                EmptyGamesCard()
            }
        } else {
            items(
                items = recentGames,
                key = { g -> g.id ?: g.hashCode() }
            ) { game ->
                MiniGameCard(
                    game = game,
                    onClick = { onOpenGame(game) }
                )
            }
        }

        item {
            TipCard()
        }
    }
}

@Composable
private fun UserHeader(
    user: User,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user.nickname.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    user.nickname,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TrendingUp, null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${user.rating}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, null)
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Задача дня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                Surface(
                    color = if (isSolved) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isSolved) "✓ Решено" else difficultyLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSolved) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChessBoard(
                    board = board,
                    theme = boardTheme,
                    flipped = !puzzle.isWhiteToMove,
                    animateMove = false,
                    showCoordinates = false,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    onSquareClick = null
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isSolved) puzzle.title else sidePrompt,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isSolved) {
                            puzzle.explanation ?: puzzle.subtitle
                        } else {
                            "Оцените позицию и найдите сильнейшее продолжение без подсказки мотива"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isSolved) {
                            "$sidePrompt • ${TrainingLabels.themeDisplayName(puzzle.theme)}"
                        } else {
                            val elo = com.example.chessmentor.domain.training.TrainingRatingCalculator
                                .difficultyToElo(puzzle.difficultyRating)
                            "Сложность: $difficultyLabel • ~$elo Эло"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(if (isSolved) "Повторить" else "Решить сейчас")
                    }
                    if (onStartSmartSession != null) {
                        OutlinedButton(
                            onClick = onStartSmartSession,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Сессия 10 задач", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGamesCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.History, null, tint = Color.Gray)
            Text("История пуста", color = Color.Gray)
        }
    }
}

@Composable
fun MiniGameCard(
    game: Game,
    onClick: () -> Unit = {}
) {
    val info = remember(game.id, game.pgnData, game.playerColor, game.timeControl) {
        PgnParser.extractGameCardInfo(game)
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black,
                            shape = RoundedCornerShape(3.dp)
                        )
                        .border(1.dp, Color.Gray, RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaLine = buildList {
                        if (info.outcome != PgnParser.PlayerOutcome.UNKNOWN) {
                            add(info.outcome.displayName)
                        }
                        add(info.timeControlLabel)
                        info.eco?.let { add(it) }
                        if (info.dateLabel.isNotBlank()) add(info.dateLabel)
                    }.joinToString(" · ")
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            if (game.isAnalyzed()) {
                val acc = game.accuracy ?: 0.0
                Text(
                    text = String.format(java.util.Locale.US, "%.0f%%", acc),
                    fontWeight = FontWeight.Bold,
                    color = when {
                        acc >= 80.0 -> Color(0xFF4CAF50)
                        acc >= 65.0 -> Color(0xFFFFA726)
                        else -> Color(0xFFE53935)
                    }
                )
            } else {
                Text(
                    text = "В очереди",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TipCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Lightbulb, null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Совет: Контролируйте центр доски пешками в начале партии.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
