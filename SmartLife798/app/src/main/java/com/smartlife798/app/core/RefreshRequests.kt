package com.smartlife798.app.core

import kotlinx.coroutines.flow.StateFlow

/** Load immediately, merging ticks received during that load instead of cancelling and restarting it. */
suspend fun collectRefreshRequests(ticks: StateFlow<Int>, load: suspend () -> Unit) {
    var consumed: Int? = null
    ticks.collect { tick ->
        if (tick != consumed) {
            load()
            consumed = ticks.value
        }
    }
}
