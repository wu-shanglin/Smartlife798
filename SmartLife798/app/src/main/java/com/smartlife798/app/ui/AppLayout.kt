package com.smartlife798.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/** Shared page grid and action sizing; text may grow beyond the minimum at large font scales. */
object AppLayout {
    val Page = 16.dp
    val Card = 16.dp
    val Gap = 12.dp
    val ActionHeight = 56.dp
    val PagePadding = PaddingValues(start = Page, top = Page, end = Page, bottom = 28.dp)
}
