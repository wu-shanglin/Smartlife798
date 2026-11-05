package com.smartlife798.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.smartlife798.app.core.collectRefreshRequests
import com.smartlife798.app.vm.MainViewModel

/** Retained source pages keep pixels and state; refresh starts once the entry is resumed. */
@Composable
internal fun PageRefreshEffect(vm: MainViewModel, refresh: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            collectRefreshRequests(vm.refreshTick) { currentRefresh() }
        }
    }
}

/** A retained page must release dialogs when navigation covers it, including incoming NFC. */
@Composable
internal fun PageInactiveEffect(onInactive: () -> Unit) {
    val active by cardSceneActiveState()
    val currentInactive by rememberUpdatedState(onInactive)
    LaunchedEffect(active) { if (!active) currentInactive() }
}
