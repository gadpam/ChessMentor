package com.example.chessmentor.presentation.ui.components.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality

/** Foreground accents on neutral surfaces (or 10–16% tinted cards). onAccent is for solid fills. */
@Immutable
internal data class ChessPalette(
    val positive: Color, val negative: Color, val caution: Color, val neutral: Color,
    val insight: Color, val brilliant: Color, val theory: Color, val onAccent: Color,
    val accentBackground: Color
) {
    fun accent(quality: MoveQuality) = when (quality) {
        MoveQuality.BRILLIANT -> brilliant
        MoveQuality.GREAT_MOVE -> insight
        MoveQuality.BEST_MOVE, MoveQuality.EXCELLENT, MoveQuality.GOOD -> positive
        MoveQuality.BOOK -> theory
        MoveQuality.INACCURACY, MoveQuality.MISTAKE -> caution
        MoveQuality.BLUNDER, MoveQuality.MISSED_WIN -> negative
    }

    fun accent(type: MistakeType) = when (type) {
        MistakeType.BLUNDER -> negative
        MistakeType.MISTAKE, MistakeType.INACCURACY -> caution
    }
}

internal val LightChessPalette = ChessPalette(
    Color(0xFF205D41), Color(0xFF92332E), Color(0xFF75480B), Color(0xFF47545A),
    Color(0xFF275678), Color(0xFF005A60), Color(0xFF634479), Color.White,
    Color(0xFFF1F5EE)
)
internal val DarkChessPalette = ChessPalette(
    Color(0xFFA6DBB4), Color(0xFFFFB4AB), Color(0xFFF2C184), Color(0xFFC1CAD0),
    Color(0xFFACD3EE), Color(0xFF8ED8D8), Color(0xFFDEC2EE), Color(0xFF172019),
    Color(0xFF14210A)
)
internal val LocalChessPalette = staticCompositionLocalOf { LightChessPalette }
