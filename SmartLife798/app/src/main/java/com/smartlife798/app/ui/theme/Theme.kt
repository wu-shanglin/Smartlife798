@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal val LightScheme = lightColorScheme(
    primary = WaterBlue40,
    onPrimary = Color.White,
    primaryContainer = WaterBlue90,
    onPrimaryContainer = SelectionInk,
    secondary = MutedAccent,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = MutedAccent,
    tertiary = MutedAccent,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = MutedAccent,
    background = Mist,
    onBackground = Ink,
    surface = Mist,
    onSurface = Ink,
    // Cards sit on `background`. With a dynamic scheme `surface == background`,
    // so card containers use the tonal token to keep their edge visible.
    surfaceContainerHigh = LightCard,
    surfaceVariant = MistVariant,
    onSurfaceVariant = InkSoft,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = WaterBlue40,
    inverseSurface = Color(0xFF10131C),
    inverseOnSurface = Color(0xFFE5E5EE),
    inversePrimary = WaterBlue80,
    surfaceDim = Color(0xFFDADDE8),
    surfaceBright = Mist,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F1FA),
    surfaceContainer = Color(0xFFEDECF5),
    surfaceContainerHighest = Color(0xFFDEDEEA),
)

internal val DarkScheme = darkColorScheme(
    primary = WaterBlue80,
    onPrimary = WaterBlue10,
    primaryContainer = WaterBlue30,
    onPrimaryContainer = WaterBlue90,
    secondary = DarkSecondary,
    onSecondary = Color(0xFF25283D),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF273044),
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = SurfaceDark,
    onBackground = DarkOnSurface,
    surface = SurfaceDarkAlt,
    onSurface = DarkOnSurface,
    surfaceContainerHigh = SurfaceDarkAlt,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = WaterBlue80,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = WaterBlue40,
    surfaceDim = SurfaceDark,
    surfaceBright = Color(0xFF3A3E4C),
    surfaceContainerLowest = Color(0xFF0A0D14),
    surfaceContainerLow = Color(0xFF171B25),
    surfaceContainer = Color(0xFF1D222E),
    surfaceContainerHighest = DarkSurfaceVariant,
)

/**
 * Brand shape scale shared by the official Material 3 Expressive components.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Expressive motion: quick, springy and slightly overshooting. */
val ExpressiveSpatial = spring<Float>(
    dampingRatio = 0.62f,
    stiffness = Spring.StiffnessMediumLow,
)

val ExpressiveEffects = spring<Float>(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMedium,
)

internal data class ScanCardColors(val container: Color, val content: Color)

// Use the matched Material foreground in each mode: vivid blue/white in
// light mode, periwinkle/deep blue in dark mode, and the selected dynamic pair.
internal fun ColorScheme.scanCardColors(): ScanCardColors = ScanCardColors(primary, onPrimary)

@Composable
fun SmartLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // enableEdgeToEdge() already makes the system bars transparent; only the
            // icon polarity has to follow our scheme. Writing the deprecated colour
            // properties here was a no-op that ran on every recomposition.
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
