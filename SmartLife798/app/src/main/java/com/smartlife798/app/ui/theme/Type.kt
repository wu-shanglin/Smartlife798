package com.smartlife798.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val default = Typography()

val AppTypography = Typography(
    displaySmall = default.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = default.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    bodyLarge = default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = default.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = default.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)
