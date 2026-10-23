package com.smartlife798.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.smartlife798.app.ui.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Only the duration text recomposes each second, and only while the page is resumed. */
@Composable
fun UsageDuration(startedAt: Long, style: TextStyle, color: Color, fontWeight: FontWeight? = null) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, startedAt) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    Text(Fmt.duration(startedAt, now), style = style, color = color, fontWeight = fontWeight)
}
