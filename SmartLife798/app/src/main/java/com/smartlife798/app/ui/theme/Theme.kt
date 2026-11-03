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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal val LightScheme = lightColorScheme(
    primary = WaterBlue40,
    onPrimary = Color.White,
    primaryContainer = WaterBlue90,
    onPrimaryContainer = WaterBlue10,
    secondary = WaterBlue40,
    onSecondary = Color.White,
    secondaryContainer = WaterBlue90,
    onSecondaryContainer = WaterBlue10,
    tertiary = WaterBlue40,
    onTertiary = Color.White,
    tertiaryContainer = WaterBlue90,
    onTertiaryContainer = WaterBlue10,
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    // Cards sit on `background`. With a dynamic scheme `surface == background`,
    // so card containers use the tonal token to keep their edge visible.
    surfaceContainerHigh = Color.White,
    surfaceVariant = MistVariant,
    onSurfaceVariant = InkSoft,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = WaterBlue40,
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = WaterBlue80,
    surfaceDim = Color(0xFFD6E7EF),
    surfaceBright = Mist,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7FBFE),
    surfaceContainer = Color(0xFFE7F3FA),
    surfaceContainerHighest = Color(0xFFD5EAF5),
)

internal val DarkScheme = darkColorScheme(
    primary = WaterBlue80,
    onPrimary = WaterBlue10,
    primaryContainer = WaterBlue30,
    onPrimaryContainer = WaterBlue90,
    secondary = WaterBlue80,
    onSecondary = WaterBlue10,
    secondaryContainer = WaterBlue30,
    onSecondaryContainer = WaterBlue90,
    tertiary = WaterBlue80,
    onTertiary = WaterBlue10,
    tertiaryContainer = WaterBlue30,
    onTertiaryContainer = WaterBlue90,
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
    surfaceBright = Color(0xFF354D5B),
    surfaceContainerLowest = Color(0xFF0B0F14),
    surfaceContainerLow = Color(0xFF122630),
    surfaceContainer = Color(0xFF1B3341),
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

/** Tonal controls use the same accent as primary actions, including dynamic palettes. */
internal fun ColorScheme.unifiedControlScheme(): ColorScheme = copy(
    secondary = primary,
    onSecondary = onPrimary,
    secondaryContainer = primaryContainer,
    onSecondaryContainer = onPrimaryContainer,
    tertiary = primary,
    onTertiary = onPrimary,
    tertiaryContainer = primaryContainer,
    onTertiaryContainer = onPrimaryContainer,
)

internal data class ScanCardColors(val container: Color, val content: Color)

// The brand hero stays luminous in both modes; dynamic colour uses its own
// light container/content pair rather than darkening an arbitrary primary.
internal fun ColorScheme.scanCardColors(): ScanCardColors =
    if (primary == LightScheme.primary || primary == DarkScheme.primary) {
        ScanCardColors(SkyBlue, Color.White)
    } else {
        ScanCardColors(primaryContainer, onPrimaryContainer)
    }

@Composable
fun SmartLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val scheme = remember(baseScheme) { baseScheme.unifiedControlScheme() }
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
