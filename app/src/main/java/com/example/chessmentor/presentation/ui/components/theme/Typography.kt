package com.example.chessmentor.presentation.ui.components.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System families include Cyrillic and respect the device's font scale without downloads.
private val base = Typography()
internal val MentorTypography = base.copy(
    // Заголовки — с засечками для солидности (как у Chess.com)
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp),
    // Основной текст
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
    // Метки и кнопки
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
    // Моноширина для цифр (оценки, часы, ACPL, рейтинг)
    displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 40.sp),
    displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 32.sp),
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp)
)
