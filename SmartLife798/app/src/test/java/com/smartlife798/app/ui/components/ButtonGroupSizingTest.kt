package com.smartlife798.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ButtonGroupSizingTest {
    @Test fun tightAndInvalidWidthsKeepShapeFeedbackWithoutSqueezingText() {
        assertEquals(0f, safePressExpansion(100, 100), 0f)
        assertEquals(0f, safePressExpansion(80, 100), 0f)
        assertEquals(0f, safePressExpansion(0, 0), 0f)
    }

    @Test fun spaciousGroupsRetainFullExpressiveExpansion() {
        assertEquals(0.25f, safePressExpansion(120, 80), 0f)
    }

    @Test fun edgePressAlwaysLeavesEnoughPixelsForItsNeighborsLabel() {
        for (width in 48..600) for (minimum in 48..width) {
            val growth = (width * safePressExpansion(width, minimum)).roundToInt()
            assertTrue(width - growth >= minimum)
        }
    }
}
