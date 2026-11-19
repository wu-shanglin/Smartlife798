package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.ThemeMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Regression check for the collapse: after Back, the screen must end up pixel-identical to the
 * list captured before the card was opened.
 *
 * This is the only reliable way to catch the collapse landing on the wrong content. Counting
 * "page background" pixels cannot do it, because the detail page and the home list share the
 * same surface colours, so a misaligned frame scores the same as a correct one.
 */
object FoldBackChecks {
    private const val Favorite = "3 栋 2 层淋浴机 02"

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "offline guard"
        var activity: Activity? = null
        val thread = HandlerThread("fold-frames").apply { start() }
        val handler = Handler(thread.looper)
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val previousTheme = Store.themeModeName()
        val previousDynamic = Store.dynamicColor()
        val previousStartup = Store.startupPage()
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            test.runOnMainSync {
                Appearance.setMode(ThemeMode.LIGHT)
                Appearance.setDynamicColor(false)
                Store.saveStartupPage(StartupPage.HOME)
            }
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true))
            val window = activity.window
            stage = "reach source"
            await { find(test, "常用设备") != null && find(test, Favorite) != null }
            SystemClock.sleep(1_500)
            val label = checkNotNull(find(test, Favorite))
            val card = cardBounds(label)
            // Capture the untouched list BEFORE opening: after the fold-back the screen must be
            // pixel-identical to this, which is the only robust way to prove "no misalignment".
            val listRef = copyFrame(test, handler, window)
            stage = "open detail"
            tap(test, card.centerX(), card.centerY())
            await { find(test, "返回") != null }
            // Animator scale makes the entry take seconds, so a fixed sleep would press Back
            // while the page is still expanding and test an interruption instead of the
            // "settled full screen -> card" path reported by the user.
            awaitStable(test, handler, window)

            stage = "back"
            val back = Rect().also { checkNotNull(find(test, "返回")).getBoundsInScreen(it) }
            tap(test, back.centerX(), back.centerY())
            val start = SystemClock.uptimeMillis()
            var index = 0
            // Sampling costs a frame each and the collapse is 650ms of animation time; with
            // animator_duration_scale=10 that is 6.5s of wall clock. Sample until the screen
            // actually matches the pre-entry list, with a generous cap.
            val size = test.targetContext.resources.displayMetrics
            val full = Rect(0, 0, size.widthPixels, size.heightPixels)
            var settledAt = -1L
            while (SystemClock.uptimeMillis() - start < 20_000) {
                val shot = copyFrame(test, handler, window)
                val matched = changed(listRef, shot, full) == null
                shot.recycle()
                index++
                if (matched) {
                    settledAt = SystemClock.uptimeMillis() - start
                    break
                }
            }
            // Stay quiet for a moment after the list is back, then confirm it is still there:
            // a collapse that keeps animating afterwards would fail here.
            SystemClock.sleep(1_500)
            val settled = copyFrame(test, handler, window)
            val diff = changed(listRef, settled, full)
            settled.recycle()
            listRef.recycle()
            check(diff == null) {
                "the fold-back left ${diff?.width()}x${diff?.height()} at ${diff?.left}," +
                    "${diff?.top} instead of restoring the untouched list"
            }
            result.putString(
                "stream",
                "PASS fold frames=$index settledAt=${settledAt}ms card=$card residue=none\n",
            )
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL $stage: ${failure.message}\n")
        } finally {
            thread.quitSafely()
            test.runOnMainSync {
                activity?.finish()
                Appearance.setMode(ThemeMode.fromName(previousTheme))
                Appearance.setDynamicColor(previousDynamic)
                Store.saveStartupPage(previousStartup)
            }
        }
        test.finish(code, result)
    }

    /** Waits until two frames sampled 250ms apart are pixel-identical. */
    private fun awaitStable(test: Instrumentation, handler: Handler, window: android.view.Window) {
        var last = copyFrame(test, handler, window)
        try {
            val end = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(250)
                val next = copyFrame(test, handler, window)
                val settled = changed(last, next, Rect(0, 0, next.width, next.height)) == null
                last.recycle()
                last = next
                if (settled) return
            }
            check(false) { "the detail page never settled before Back" }
        } finally {
            last.recycle()
        }
    }

    private fun changed(home: Bitmap, shot: Bitmap, area: Rect): Rect? {
        val change = Rect()
        for (y in area.top until area.bottom step 4) {
            for (x in area.left until area.right step 4) {
                val a = home.getPixel(x, y)
                val b = shot.getPixel(x, y)
                if (abs(Color.red(a) - Color.red(b)) > 20 ||
                    abs(Color.green(a) - Color.green(b)) > 20 ||
                    abs(Color.blue(a) - Color.blue(b)) > 20
                ) {
                    change.union(x, y, x + 1, y + 1)
                }
            }
        }
        return change.takeUnless { it.isEmpty }
    }

    /** The clickable ancestor of [label]: the tile's real bounds, not the label's. */
    private fun cardBounds(label: AccessibilityNodeInfo): Rect {
        var node = label
        while (!node.isClickable) node = checkNotNull(node.parent) { "no clickable card ancestor" }
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun copyFrame(test: Instrumentation, handler: Handler, window: android.view.Window): Bitmap {
        val size = test.targetContext.resources.displayMetrics
        val bitmap = Bitmap.createBitmap(size.widthPixels, size.heightPixels, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var code = -1
        handler.post {
            PixelCopy.request(window, bitmap, { copyResult ->
                code = copyResult
                latch.countDown()
            }, handler)
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "PixelCopy timeout" }
        check(code == PixelCopy.SUCCESS) { "PixelCopy failed $code" }
        return bitmap
    }

    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() == label ||
                    node.contentDescription?.toString() == label)
            ) {
                return node
            }
            return (0 until node.childCount).firstNotNullOfOrNull { search(node.getChild(it)) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return search(test.uiAutomation.rootInActiveWindow)
    }

    private fun tap(test: Instrumentation, x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(40)
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "condition timed out" }
            SystemClock.sleep(40)
        }
    }
}
