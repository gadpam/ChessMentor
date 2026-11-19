package com.example.chessmentor.presentation.ui.components.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.chessmentor.domain.session.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = Color(0xFF14210A),
    primaryContainer = Color(0xFF34562B),
    onPrimaryContainer = Color(0xFFE3F5D3),

    secondary = Color(0xFFB5CBB0),
    onSecondary = Color(0xFF21341E),
    secondaryContainer = Color(0xFF393632),
    onSecondaryContainer = Color(0xFFE9E6E1),

    tertiary = Color(0xFF9AC9A0),
    onTertiary = Color(0xFF15371D),
    tertiaryContainer = Color(0xFF27442D),
    onTertiaryContainer = Color(0xFFD9F5DD),

    background = BackgroundDark,
    onBackground = TextPrimaryDark,

    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF3C3935),
    onSurfaceVariant = TextSecondaryDark,
    outline = Color(0xFF6B6660),

    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF561D17),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5D9233),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F2D5),
    onPrimaryContainer = Color(0xFF1D380E),

    secondary = Color(0xFF4F6347),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5ECE1),
    onSecondaryContainer = Color(0xFF1B2B17),

    tertiary = Color(0xFF336A44),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F3DC),
    onTertiaryContainer = Color(0xFF0E2B17),

    background = BackgroundLight,
    onBackground = TextPrimaryLight,

    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFE6E6E3),
    onSurfaceVariant = Color(0xFF575753),
    outline = Color(0xFF8C8C87),

    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE2DE),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun ChessMentorTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
