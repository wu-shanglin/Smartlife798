package com.smartlife798.app.ui.components

/** An edge press takes its entire growth from one neighbor; reserve one rounding pixel. */
internal fun safePressExpansion(slotWidth: Int, minimumWidth: Int): Float {
    if (slotWidth <= 0 || minimumWidth < 0) return 0f
    return ((slotWidth - minimumWidth - 1).toFloat() / slotWidth).coerceIn(0f, 0.25f)
}
