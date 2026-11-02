package com.smartlife798.app.ui

import androidx.compose.ui.graphics.Color
import com.smartlife798.app.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PaletteContrastTest {
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
    }
    @Test fun `brand text and status pairs meet normal text contrast`() {
        val pairs = listOf(WaterBlue40 to Color.White, WaterBlue80 to WaterBlue10,
            WaterBlue90 to WaterBlue10, WaterBlue30 to WaterBlue90,
            Aqua40 to Color.White, Aqua80 to DarkOnAqua, AquaContainer to AquaOnContainer,
            DarkAquaContainer to DarkOnAquaContainer, Slate40 to Color.White,
            Slate80 to DarkOnSlate, SlateContainer to SlateOnContainer,
            DarkSlateContainer to DarkOnSlateContainer, Mist to Ink, Color.White to InkSoft,
            SurfaceDarkAlt to DarkOnSurface, SurfaceDarkAlt to DarkOnSurfaceVariant)
        pairs.forEach { (background, text) ->
            val a = luminance(background); val b = luminance(text)
            assertTrue("contrast $background / $text", (maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= 4.5)
        }
    }
}
