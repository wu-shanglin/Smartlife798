package com.smartlife798.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** LazyRow consumes a down during fling. Observe that one tap before consumption. */
fun Modifier.tapDuringFling(isScrolling: () -> Boolean, onTap: () -> Unit): Modifier =
    composed {
        val scrolling by rememberUpdatedState(isScrolling)
        val open by rememberUpdatedState(onTap)
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val wasScrolling = scrolling()
                var tap = wasScrolling
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.size != 1) tap = false
                    if (change != null) {
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                        if (!change.pressed && tap) {
                            change.consume()
                            open()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    }
