package com.example.chessmentor.presentation.ui.components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality

/** Foreground accents on neutral surfaces (or 10–16% tinted cards). onAccent is for solid fills. */
@Immutable
internal data class ChessPalette(
    val positive: Color, val negative: Color, val caution: Color, val inaccuracy: Color,
    val neutral: Color, val insight: Color, val brilliant: Color, val theory: Color,
    val onAccent: Color, val accentBackground: Color
) {
    fun accent(quality: MoveQuality) = when (quality) {
        MoveQuality.BRILLIANT -> brilliant
        MoveQuality.GREAT_MOVE -> insight
        MoveQuality.BEST_MOVE, MoveQuality.EXCELLENT, MoveQuality.GOOD -> positive
        MoveQuality.BOOK -> theory
        MoveQuality.INACCURACY -> inaccuracy
        MoveQuality.MISTAKE -> caution
        MoveQuality.BLUNDER, MoveQuality.MISSED_WIN -> negative
    }

    fun accent(type: MistakeType) = when (type) {
        MistakeType.BLUNDER -> negative
        MistakeType.MISTAKE -> caution
        MistakeType.INACCURACY -> inaccuracy
    }
}

internal val LightChessPalette = ChessPalette(
    positive = Color(0xFF205D41),
    negative = Color(0xFF92332E),
    caution = Color(0xFF913E00),
    inaccuracy = Color(0xFF635900),
    neutral = Color(0xFF47545A),
    insight = Color(0xFF275678),
    brilliant = Color(0xFF005A60),
    theory = Color(0xFF634479),
    onAccent = Color.White,
    accentBackground = Color(0xFFF1F5EE)
)
internal val DarkChessPalette = ChessPalette(
    positive = Color(0xFFA6DBB4),
    negative = Color(0xFFFFB4AB),
    caution = Color(0xFFFFB266),
    inaccuracy = Color(0xFFEFE173),
    neutral = Color(0xFFC1CAD0),
    insight = Color(0xFFACD3EE),
    brilliant = Color(0xFF8ED8D8),
    theory = Color(0xFFDEC2EE),
    onAccent = Color(0xFF172019),
    accentBackground = Color(0xFF14210A)
)
internal val LocalChessPalette = staticCompositionLocalOf { LightChessPalette }
