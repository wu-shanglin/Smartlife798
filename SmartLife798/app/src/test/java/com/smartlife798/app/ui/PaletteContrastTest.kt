package com.smartlife798.app.ui

import androidx.compose.ui.graphics.Color
import com.smartlife798.app.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.compositeOver
import org.junit.Test
import kotlin.math.pow

class PaletteContrastTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
    }
    @Test fun `reference secondary and tertiary roles stay muted instead of being collapsed into blue`() {
        assertEquals(Color(0xFFE1E1FD), LightScheme.secondaryContainer)
        assertEquals(Color(0xFFE0E1F3), LightScheme.tertiaryContainer)
        assertEquals(Color(0xFF535466), LightScheme.onSecondaryContainer)
        assertTrue(LightScheme.primaryContainer != LightScheme.secondaryContainer)
        assertTrue(DarkScheme.primaryContainer != DarkScheme.secondaryContainer)
    }
    @Test fun `scan uses reference light blue white and dark periwinkle deep blue pairs`() {
        assertEquals(Color(0xFF0055D6), LightScheme.scanCardColors().container)
        assertEquals(Color.White, LightScheme.scanCardColors().content)
        assertEquals(Color(0xFFB3C5FF), DarkScheme.scanCardColors().container)
        assertEquals(Color(0xFF002C6D), DarkScheme.scanCardColors().content)
        listOf(LightScheme, DarkScheme).forEach { scheme ->
            val hero = scheme.scanCardColors()
            val decorated = hero.content.copy(alpha = 0.14f).compositeOver(hero.container)
            val a = luminance(decorated); val b = luminance(hero.content)
            assertTrue((maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
    @Test fun `light reference image surface and selection colours remain paired`() {
        assertEquals(Color(0xFFF9F8FE), LightScheme.background)
        assertEquals(Color(0xFFE5E7F4), LightScheme.surfaceContainerHigh)
        assertEquals(Color(0xFFDAE1FE), LightScheme.primaryContainer)
        assertEquals(Color(0xFF0040A4), LightScheme.onPrimaryContainer)
    }
    @Test fun `all page and card text share the reference neutral roles`() {
        assertEquals(LightScheme.onBackground, LightScheme.onSurface)
        assertEquals(Color(0xFF1B1A20), LightScheme.onSurface)
        assertEquals(Color(0xFF48474D), LightScheme.onSurfaceVariant)
    }
    @Test fun `all standard actions use the specified blue fill and matching white content`() {
        assertEquals(Color(0xFF0055D6), LightScheme.primary)
        assertEquals(LightScheme.primary, LightScheme.scanCardColors().container)
        assertEquals(Color.White, LightScheme.onPrimary)
    }
    @Test fun `notice colours match the reference dark bar and pale foreground`() {
        assertEquals(Color(0xFF10131C), LightScheme.inverseSurface)
        assertEquals(Color(0xFFE5E5EE), LightScheme.inverseOnSurface)
    }
    @Test fun `brand text and status pairs meet normal text contrast`() {
        val pairs = listOf(LightScheme, DarkScheme).flatMap { scheme -> listOf(
            scheme.primary to scheme.onPrimary,
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondary to scheme.onSecondary,
            scheme.tertiary to scheme.onTertiary,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
            scheme.background to scheme.onBackground,
            scheme.surfaceContainerHigh to scheme.onSurface,
            scheme.surfaceVariant to scheme.onSurfaceVariant,
            scheme.surfaceContainerHigh to scheme.onSurfaceVariant,
            scheme.inverseSurface to scheme.inverseOnSurface,
            scheme.surfaceContainerHigh to scheme.primary,
        ) }
        pairs.forEach { (background, text) ->
            val a = luminance(background); val b = luminance(text)
            assertTrue("contrast $background / $text", (maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
}
