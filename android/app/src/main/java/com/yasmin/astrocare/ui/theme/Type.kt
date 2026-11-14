package com.yasmin.astrocare.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun careText(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

// Readable body text, restrained headings. Android font scaling is preserved.
val Typography = Typography(
    displayLarge = careText(36, 44, FontWeight.SemiBold),
    displayMedium = careText(32, 40, FontWeight.SemiBold),
    displaySmall = careText(28, 36, FontWeight.SemiBold),
    headlineLarge = careText(28, 36, FontWeight.SemiBold),
    headlineMedium = careText(24, 32, FontWeight.SemiBold),
    headlineSmall = careText(22, 30, FontWeight.SemiBold),
    titleLarge = careText(20, 28, FontWeight.SemiBold),
    titleMedium = careText(18, 26, FontWeight.Medium),
    titleSmall = careText(16, 24, FontWeight.SemiBold),
    bodyLarge = careText(16, 26),
    bodyMedium = careText(16, 24),
    bodySmall = careText(14, 20),
    labelLarge = careText(16, 24, FontWeight.Medium),
    labelMedium = careText(14, 20, FontWeight.Medium),
    labelSmall = careText(13, 18, FontWeight.Medium),
)
