package com.example.chessmentor.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.chessmentor.presentation.ui.components.theme.ChessMentorTheme
import com.example.chessmentor.presentation.ui.components.theme.LocalChessPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PaletteContrastTest {
    @get:Rule val ui = createComposeRule()

    @Test fun bothSelectedThemesKeepTextReadableOnCardsAndSolidBadges() {
        val dark = mutableStateOf(false)
        var pairs = emptyList<Pair<Color, Color>>()
        ui.setContent {
            ChessMentorTheme(darkTheme = dark.value) {
                val m = MaterialTheme.colorScheme
                val p = LocalChessPalette.current
                pairs = buildList {
                    add(m.onSurface to m.surface)
                    add(m.onSurfaceVariant to m.surfaceVariant)
                    add(m.onPrimary to m.primary)
                    add(m.onErrorContainer to m.errorContainer)
                    for (accent in listOf(p.positive, p.negative, p.caution, p.neutral, p.insight, p.brilliant, p.theory)) {
                        add(p.onAccent to accent)
                        for (surface in listOf(m.surface, m.surfaceContainer, m.background)) {
                            add(accent to surface)
                            add(accent to accent.copy(alpha = .16f).compositeOver(surface))
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            ui.runOnIdle { dark.value = mode }
            ui.runOnIdle {
                assertTrue(pairs.isNotEmpty())
                for ((ink, fill) in pairs) {
                    val a = ink.luminance(); val b = fill.luminance()
                    val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                    assertTrue("dark=$mode, $ink on $fill: $ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
