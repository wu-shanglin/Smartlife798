package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.smartlife798.app.ui.components.AppBottomSheet
import com.smartlife798.app.ui.theme.SmartLifeTheme
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Compare actual sheet offset animation lifetimes, without screenshots in the measured interval. */
@OptIn(ExperimentalMaterial3Api::class)
object SheetMotionChecks {
    fun run(test: Instrumentation, balanced: Boolean = true) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        var result = ""
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        test.runOnMainSync {
            activity.setContent {
                SmartLifeTheme {
                    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    AppBottomSheet(onDismissRequest = {}, sheetState = sheet, balancedMotion = balanced) {
                        Text("离线面板动画测量")
                    }
                    LaunchedEffect(sheet) {
                        try {
                            // Ignore the initial anchor placement, measure from the first moving frame.
                            var started = 0L
                            var previous = Float.NaN
                            var frames = 0
                            val intervals = mutableListOf<Long>()
                            var lastFrame = 0L
                            while (true) {
                                val frame = withFrameNanos { it }
                                val offset = try { sheet.requireOffset() } catch (_: IllegalStateException) { continue }
                                if (previous.isFinite() && offset != previous && started == 0L) started = frame
                                if (started != 0L) {
                                    frames++
                                    if (lastFrame != 0L) intervals += frame - lastFrame
                                    lastFrame = frame
                                }
                                previous = offset
                                if (started != 0L && sheet.isVisible && !sheet.isAnimationRunning) break
                            }
                            val showMs = (lastFrame - started) / 1_000_000
                            repeat(12) { withFrameNanos { it } }
                            val hideStart = SystemClock.elapsedRealtimeNanos()
                            val hideIntervals = mutableListOf<Long>()
                            val hideOffsets = mutableListOf<Float>()
                            var hideLastFrame = withFrameNanos { it }
                            val hide = launch { sheet.hide() }
                            while (hide.isActive) {
                                val frame = withFrameNanos { it }
                                hideOffsets += sheet.requireOffset()
                                hideIntervals += frame - hideLastFrame
                                hideLastFrame = frame
                            }
                            hide.join()
                            val hideMs = (SystemClock.elapsedRealtimeNanos() - hideStart) / 1_000_000
                            check(!sheet.isVisible)
                            check(!balanced || (hideMs >= showMs * 0.65 && hideMs <= showMs * 1.5)) {
                                "asymmetric sheet motion: show=$showMs ms hide=$hideMs ms"
                            }
                            val reverse = hideOffsets.zipWithNext().count { (a, b) -> b < a - 0.5f }
                            val hidden = sheet.requireOffset()
                            check(!balanced || reverse == 0) { "exit reversed direction in $reverse frames" }
                            check(!balanced || hideOffsets.none { it > hidden + 0.5f }) { "exit overshot Hidden" }
                            result = "reverseFrames=$reverse balanced=$balanced show=${showMs}ms hide=${hideMs}ms showFrames=$frames; " +
                                "largest show frame interval=${(intervals.maxOrNull() ?: 0) / 1_000_000}ms; " +
                                "hide frames=${hideIntervals.size}, largest hide interval=${(hideIntervals.maxOrNull() ?: 0) / 1_000_000}ms; " +
                                "offset lifetime measurement, not a physical-device frame benchmark"
                        } catch (error: Throwable) {
                            failure = error
                        } finally {
                            done.countDown()
                        }
                    }
                }
            }
        }
        val completed = done.await(20, TimeUnit.SECONDS)
        test.runOnMainSync { activity.finish() }
        val output = Bundle()
        output.putString("stream", if (completed && failure == null) "PASS: $result\n"
            else "FAIL: completed=$completed error=$failure\n")
        test.finish(if (completed && failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
