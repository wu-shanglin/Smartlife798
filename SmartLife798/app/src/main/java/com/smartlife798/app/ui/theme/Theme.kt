package com.smartlife798.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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

private val LightScheme = lightColorScheme(
    primary = WaterBlue40,
    onPrimary = Color.White,
    primaryContainer = WaterBlue90,
    onPrimaryContainer = WaterBlue10,
    secondary = Aqua40,
    onSecondary = Color.White,
    secondaryContainer = AquaContainer,
    onSecondaryContainer = AquaOnContainer,
    tertiary = Sand40,
    onTertiary = Color.White,
    tertiaryContainer = SandContainer,
    onTertiaryContainer = SandOnContainer,
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
    surfaceDim = Color(0xFFD7DAE0),
    surfaceBright = Mist,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4FA),
    surfaceContainer = Color(0xFFEAEEF5),
    surfaceContainerHighest = Color(0xFFDFE3EB),
)

private val DarkScheme = darkColorScheme(
    primary = WaterBlue80,
    onPrimary = WaterBlue10,
    primaryContainer = WaterBlue30,
    onPrimaryContainer = WaterBlue90,
    secondary = Aqua80,
    onSecondary = DarkOnAqua,
    secondaryContainer = DarkAquaContainer,
    onSecondaryContainer = DarkOnAquaContainer,
    tertiary = Sand80,
    onTertiary = DarkOnSand,
    tertiaryContainer = DarkSandContainer,
    onTertiaryContainer = DarkOnSandContainer,
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
    surfaceBright = Color(0xFF353B43),
    surfaceContainerLowest = Color(0xFF0B0F14),
    surfaceContainerLow = Color(0xFF151B22),
    surfaceContainer = Color(0xFF171D24),
    surfaceContainerHighest = DarkSurfaceVariant,
)

/**
 * Material 3 Expressive inspired shape scale (softer, larger radii).
 * Components stay on the stable material3 API; the expressive character comes
 * from shapes, motion and state styling rather than alpha-only APIs.
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
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
}
