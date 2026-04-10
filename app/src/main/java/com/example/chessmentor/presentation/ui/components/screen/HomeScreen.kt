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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.presentation.ui.components.ChessBoard
import com.example.chessmentor.presentation.viewmodel.GameViewModel
import com.github.bhlangonijr.chesslib.Board

@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    currentUser: User?,
    gameViewModel: GameViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToTraining: () -> Unit,
    message: String,
    isLoading: Boolean,
    onMessageDismiss: () -> Unit,
    onLogout: () -> Unit,
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
            onLogout = onLogout,
            onNavigateToTraining = onNavigateToTraining,
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
    var currentTab by remember { mutableStateOf("login") }

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

        // Табы
        item {
            AuthTabs(
                currentTab = currentTab,
                isLoading = isLoading,
                onTabChange = { currentTab = it }
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
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль") },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    Button(
        onClick = { onLogin(email, password) },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
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
    Text(
        "Создать аккаунт",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    OutlinedTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        label = { Text("Никнейм") },
        leadingIcon = { Icon(Icons.Default.Person, null) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        leadingIcon = { Icon(Icons.Default.Email, null) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль") },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    OutlinedTextField(
        value = rating,
        onValueChange = onRatingChange,
        label = { Text("Рейтинг") },
        leadingIcon = { Icon(Icons.Default.Star, null) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    Button(
        onClick = { onRegister(nickname, email, password, rating.toIntOrNull() ?: 1200) },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = !isLoading && nickname.isNotBlank()
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
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
    onLogout: () -> Unit,
    onNavigateToTraining: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    LaunchedEffect(user.id) {
        gameViewModel.loadUserData()
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
            DailyPuzzleWidget(onClick = onNavigateToTraining)
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
            items(recentGames) { game ->
                MiniGameCard(game)
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
                        text = user.nickname.first().uppercase(),
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
fun DailyPuzzleWidget(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(40.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Задача дня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "Сложная",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
            val board = remember {
                Board().apply { loadFromFen(fen) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .background(
                        Color.LightGray.copy(alpha = 0.2f),
                        RoundedCornerShape(8.dp)
                    )
            ) {
                ChessBoard(
                    board = board,
                    modifier = Modifier.fillMaxSize(),
                    onSquareClick = null
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = onClick) {
                        Text("Решить сейчас")
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
fun MiniGameCard(game: Game) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (game.playerColor == ChessColor.WHITE) Color.White else Color.Black
                        )
                        .border(1.dp, Color.Gray)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Партия #${game.id}", fontWeight = FontWeight.Bold)
                    Text(
                        game.timeControl ?: "Без контроля",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            if (game.isAnalyzed()) {
                Text(
                    "${game.accuracy?.toInt() ?: 0}%",
                    fontWeight = FontWeight.Bold,
                    color = if ((game.accuracy ?: 0.0) > 80) Color(0xFF4CAF50) else Color(0xFFFFA726)
                )
            } else {
                Text("В очереди", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
