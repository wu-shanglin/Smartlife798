package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import com.smartlife798.app.ui.theme.SmartLifeTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/** Actual drag input against the owning refresh wrapper; entirely offline. */
object RefreshChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        val calls = AtomicInteger()
        val finished = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        var dragCompositions = 0
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            var scroll = LazyListState()
            val compositions = AtomicInteger()
            fun mount() = test.runOnMainSync {
                activity!!.setContent {
                    SmartLifeTheme(darkTheme = false) {
                        ExpressiveRefreshBox(onRefresh = {
                            val call = calls.incrementAndGet()
                            try {
                                if (call == 1) release.await()
                                if (call == 2) awaitCancellation()
                            } finally {
                                finished.incrementAndGet()
                            }
                        }, modifier = Modifier.fillMaxSize()) {
                            SideEffect { compositions.incrementAndGet() }
                            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                                items(80) { Text("离线刷新 $it", Modifier.padding(24.dp)) }
                            }
                        }
                    }
                }
            }
            mount()
            SystemClock.sleep(600)
            test.waitForIdleSync()
            stage = "continuous drag before UP"
            val before = compositions.get()
            drag(test, downward = false, beforeUp = {
                await { scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0 }
                dragCompositions = compositions.get() - before
                check(calls.get() == 0 && dragCompositions == 0)
            })
            test.runOnMainSync { activity!!.setContent { } }
            test.waitForIdleSync()
            scroll = LazyListState()
            mount()
            SystemClock.sleep(600)
            stage = "first refresh starts"
            drag(test, downward = true)
            await { calls.get() == 1 }
            stage = "busy refresh rejects duplicate"
            drag(test, downward = true)
            check(calls.get() == 1)
            release.complete(Unit)
            await { finished.get() == 1 }
            SystemClock.sleep(700)
            stage = "refresh can run again"
            drag(test, downward = true)
            await { calls.get() == 2 }
            stage = "leaving page cancels suspended refresh"
            test.runOnMainSync { activity!!.setContent { } }
            await { finished.get() == 2 }
            mount()
            SystemClock.sleep(600)
            stage = "reopen can refresh"
            drag(test, downward = true)
            await { calls.get() == 3 && finished.get() == 3 }
            result.putString("stream", "PASS: scroll updates before UP without content recomposition; busy pull coalesces; completion permits retry; page disposal cancels suspended action; reopening refreshes; no transport\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; calls=${calls.get()},finished=${finished.get()},dragCompositions=$dragCompositions,source=${frame?.fileName}:${frame?.lineNumber},state=${activity?.lifecycle?.currentState}\n")
        } finally {
            release.complete(Unit)
            test.runOnMainSync { activity?.let { it.setContent { }; it.finish() } }
            Preview.enabled = false
        }
        test.finish(code, result)
    }

    private fun drag(test: Instrumentation, downward: Boolean, beforeUp: () -> Unit = {}) {
        val display = test.targetContext.resources.displayMetrics
        val x = display.widthPixels * 0.5f
        val start = display.heightPixels * if (downward) 0.25f else 0.75f
        val end = display.heightPixels * if (downward) 0.80f else 0.30f
        val began = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { test.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        try {
            for (step in 1..20) {
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 20)
            }
            beforeUp()
        } finally {
            send(MotionEvent.ACTION_UP, end)
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        check(condition())
    }
}
