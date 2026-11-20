package com.example.chessmentor.presentation.ui.components.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System families include Cyrillic and respect the device's font scale without downloads.
private val base = Typography()
internal val MentorTypography = base.copy(
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)
