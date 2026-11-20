package com.example.chessmentor.presentation.ui.components.util

import java.util.Locale

internal const val MISSING_ACCURACY = "Точность не рассчитана"

/** The same game-level percentage on home, in the library and in review. */
internal fun formatGameAccuracy(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0") + "%"
