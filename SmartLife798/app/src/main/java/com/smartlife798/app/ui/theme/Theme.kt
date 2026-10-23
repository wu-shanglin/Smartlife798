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
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
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
)

private val DarkScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
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
