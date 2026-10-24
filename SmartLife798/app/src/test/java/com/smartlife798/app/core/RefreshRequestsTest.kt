package com.smartlife798.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRequestsTest {
    @Test fun firstLoadStartsImmediatelyAndOverlappingTicksDoNotRestartIt() = runBlocking {
        val ticks = MutableStateFlow(7)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var completed = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            collectRefreshRequests(ticks) {
                calls++
                release.await()
                completed++
            }
        }
        try {
            assertEquals(1, calls)
            ticks.value++
            yield()
            ticks.value++
            yield()
            assertEquals(1, calls)
            assertEquals(0, completed)
            release.complete(Unit)
            yield()
            assertEquals(1, completed)
            assertEquals(1, calls)
            // A subsequent foreground/navigation event must still refresh.
            ticks.value++
            yield()
            assertEquals(2, calls)
            assertEquals(2, completed)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun leavingCancelsPendingLoadAndStopsCollecting() = runBlocking {
        val ticks = MutableStateFlow(0)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var released = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            collectRefreshRequests(ticks) {
                calls++
                try {
                    release.await()
                } finally {
                    released = true
                }
            }
        }
        job.cancelAndJoin()
        assertTrue(released)
        ticks.value++
        release.complete(Unit)
        yield()
        assertEquals(1, calls)
    }
}
