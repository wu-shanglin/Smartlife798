package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartlife798.app.ui.tapDuringFling
import com.smartlife798.app.ui.theme.SmartLifeTheme
import java.util.concurrent.atomic.AtomicInteger

/** Actual pointer consumption during LazyRow fling; never accesses an account. */
object FlingTapChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
        val clicks = AtomicInteger()
        var scrolling by mutableStateOf(false)
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            test.runOnMainSync {
                activity.setContent {
                    SmartLifeTheme {
                        val row = rememberLazyListState()
                        LaunchedEffect(row.isScrollInProgress) { scrolling = row.isScrollInProgress }
                        LazyRow(state = row, modifier = Modifier.fillMaxSize()) {
                            items(30) { index ->
                                val action = { clicks.incrementAndGet(); Unit }
                                Box(Modifier.width(176.dp).fillParentMaxHeight()
                                    .tapDuringFling({ row.isScrollInProgress }, action).clickable(onClick = action)) {
                                    Text("卡片 $index")
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(1500)
            val width = test.targetContext.resources.displayMetrics.widthPixels.toFloat()
            val y = test.targetContext.resources.displayMetrics.heightPixels * 0.4f
            fun event(action: Int, x: Float, start: Long) {
                val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(e, false)); e.recycle()
            }
            var start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, width * 0.9f, start)
            repeat(6) { Thread.sleep(12); event(MotionEvent.ACTION_MOVE, width * (0.9f - (it + 1) * 0.11f), start) }
            event(MotionEvent.ACTION_UP, width * 0.24f, start)
            Thread.sleep(60)
            check(scrolling) { "fixture did not fling" }
            check(clicks.get() == 0)
            start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, width * 0.5f, start)
            Thread.sleep(40)
            event(MotionEvent.ACTION_UP, width * 0.5f, start)
            Thread.sleep(300)
            check(clicks.get() == 1) { "fling tap count=${clicks.get()}" }
            Thread.sleep(500)
            start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, width * 0.5f, start)
            Thread.sleep(40)
            event(MotionEvent.ACTION_UP, width * 0.5f, start)
            Thread.sleep(300)
            check(clicks.get() == 2) { "idle tap count=${clicks.get()}" }
            result.putString("stream", "PASS: real horizontal drag produces zero clicks; tap during fling opens once; idle tap opens once; offline only\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.javaClass.simpleName}: ${failure.message}\n")
        } finally { test.runOnMainSync { activity?.finish() } }
        test.finish(code, result)
    }
}
