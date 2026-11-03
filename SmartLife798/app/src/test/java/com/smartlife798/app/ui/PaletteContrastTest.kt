package com.smartlife798.app.ui

import androidx.compose.ui.graphics.Color
import com.smartlife798.app.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import org.junit.Test
import kotlin.math.pow

class PaletteContrastTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
    }
    @Test fun `tonal controls follow the primary accent for brand and custom palettes`() {
        val custom = lightColorScheme(primary = Color(0xFF795000), secondary = Color.Green)
        listOf(LightScheme, DarkScheme, custom).forEach { original ->
            val scheme = original.unifiedControlScheme()
            assertEquals(scheme.primary, scheme.secondary)
            assertEquals(scheme.onPrimary, scheme.onSecondary)
            assertEquals(scheme.primaryContainer, scheme.secondaryContainer)
            assertEquals(scheme.onPrimaryContainer, scheme.onSecondaryContainer)
            assertEquals(scheme.primary, scheme.tertiary)
            assertEquals(scheme.primaryContainer, scheme.tertiaryContainer)
        }
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
    @Test fun `page and card text keep separate reference tones`() {
        assertEquals(Color(0xFF1B1A20), LightScheme.onBackground)
        assertEquals(Color(0xFF181A29), LightScheme.onSurface)
        assertEquals(Color(0xFF444654), LightScheme.onSurfaceVariant)
        val subtitle = LightScheme.pageSupportingText().compositeOver(LightScheme.background)
        assertTrue(kotlin.math.abs(subtitle.red - 0x48 / 255f) <= 2 / 255f)
        assertTrue(kotlin.math.abs(subtitle.green - 0x47 / 255f) <= 2 / 255f)
        assertTrue(kotlin.math.abs(subtitle.blue - 0x4D / 255f) <= 2 / 255f)
    }
    @Test fun `light action text stays distinct from the specified scan fill`() {
        assertEquals(Color(0xFF0055D6), LightScheme.scanCardColors().container)
        assertEquals(Color(0xFF0956CB), LightScheme.actionTextColor())
        assertEquals(DarkScheme.primary, DarkScheme.actionTextColor())
        val custom = lightColorScheme(primary = Color(0xFF795000))
        assertEquals(custom.primary, custom.actionTextColor())
    }
    @Test fun `light notices stay light and readable for brand and dynamic palettes`() {
        listOf(LightScheme, lightColorScheme()).forEach { original ->
            val scheme = original.unifiedControlScheme()
            assertTrue(scheme.inverseSurface.luminance() > 0.5f)
            val a = luminance(scheme.inverseSurface); val b = luminance(scheme.inverseOnSurface)
            assertTrue((maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
    @Test fun `brand text and status pairs meet normal text contrast`() {
        val pairs = listOf(WaterBlue40 to Color.White, WaterBlue80 to WaterBlue10,
            WaterBlue90 to WaterBlue10, WaterBlue30 to WaterBlue90,
            Mist to Ink, Color.White to InkSoft, LightCard to CardInk, LightCard to InkSoft,
            LightCard to ActionInk, Color.White to ActionInk,
            LightScheme.primaryContainer to LightScheme.onSurfaceVariant,
            DarkScheme.primaryContainer to DarkScheme.onSurfaceVariant,
            LightScheme.background to LightScheme.pageSupportingText().compositeOver(LightScheme.background),
            SurfaceDarkAlt to DarkOnSurface, SurfaceDarkAlt to DarkOnSurfaceVariant)
        pairs.forEach { (background, text) ->
            val a = luminance(background); val b = luminance(text)
            assertTrue("contrast $background / $text", (maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
}
