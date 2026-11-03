package com.smartlife798.app.ui

import androidx.compose.ui.graphics.Color
import com.smartlife798.app.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
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
    // Owner-selected brand hero uses pale blue/white; other theme text roles
    // retain their contrast checks below. Do not darken this hero to satisfy them.
    @Test fun `scan hero is lighter than the previous blue and uses opaque white in both modes`() {
        listOf(LightScheme, DarkScheme).forEach { scheme ->
            val hero = scheme.scanCardColors()
            assertEquals(Color(0xFF65C3F3), hero.container)
            assertEquals(Color.White, hero.content)
            assertTrue(hero.container.luminance() > Color(0xFF4DB5F2).luminance())
        }
    }
    @Test fun `brand text and status pairs meet normal text contrast`() {
        val pairs = listOf(WaterBlue40 to Color.White, WaterBlue80 to WaterBlue10,
            WaterBlue90 to WaterBlue10, WaterBlue30 to WaterBlue90,
            Mist to Ink, Color.White to InkSoft,
            SurfaceDarkAlt to DarkOnSurface, SurfaceDarkAlt to DarkOnSurfaceVariant)
        pairs.forEach { (background, text) ->
            val a = luminance(background); val b = luminance(text)
            assertTrue("contrast $background / $text", (maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
}
