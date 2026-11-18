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
 * Measures two things the morph must not regress:
 *
 * 1. Tap latency: how long after the touch the first frame with a visible change appears.
 *    This is "response", not animation duration.
 * 2. Interruption: a Back while the entry is still running must fold back to the same card
 *    without leaving the container offset from it, and the list must end up identical to
 *    the pre-tap frame.
 */
object EntryLatencyChecks {
    private const val Favorite = "3 栋 2 层淋浴机 02"
    private const val Iterations = 4

    fun run(test: Instrumentation, interrupt: Boolean = false) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "offline guard"
        var activity: Activity? = null
        val log = StringBuilder()
        val thread = HandlerThread("latency-frames").apply { start() }
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
            val size = test.targetContext.resources.displayMetrics
            val screen = Rect(0, 0, size.widthPixels, size.heightPixels)
            stage = "reach source"
            await { find(test, "常用设备") != null && find(test, Favorite) != null }
            SystemClock.sleep(1_500)
            val label = checkNotNull(find(test, Favorite))
            val card = Rect().also { label.getBoundsInScreen(it) }
            val tapX = card.centerX()
            val tapY = card.centerY()

            val home = copyFrame(test, handler, window)
            val latencies = mutableListOf<Long>()
            for (i in 0 until Iterations) {
                stage = "iteration $i latency"
                val started = SystemClock.uptimeMillis()
                tap(test, tapX, tapY)
                var firstChange = -1L
                var growth = -1L
                while (SystemClock.uptimeMillis() - started < 1_500) {
                    val shot = copyFrame(test, handler, window)
                    val changed = changed(home, shot, screen)
                    shot.recycle()
                    if (changed == null) continue
                    val at = SystemClock.uptimeMillis() - started
                    if (firstChange < 0) firstChange = at
                    // The container is "growing" once it is clearly larger than the card.
                    if (growth < 0 && changed.width() > card.width() + 24) {
                        growth = at
                        break
                    }
                }
                latencies += growth.coerceAtLeast(0)
                await { find(test, "返回") != null }
                SystemClock.sleep(700)
                stage = "iteration $i back"
                val back = Rect().also { checkNotNull(find(test, "返回")).getBoundsInScreen(it) }
                tap(test, back.centerX(), back.centerY())
                await { find(test, Favorite) != null && find(test, "返回") == null }
                SystemClock.sleep(900)
            }
            log.append("growth=").append(latencies).append(' ')

            stage = "interruption"
            val offset = if (interrupt) interruption(test, handler, window, home, screen, card, tapX, tapY, log)
                else 0
            home.recycle()
            result.putString(
                "stream",
                "PASS ${if (interrupt) "interrupt" else "latency"} " +
                    "growth=${latencies.filter { it >= 0 }} offset=$offset; $log\n",
            )
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL $stage: ${failure.message}; $log\n")
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

    /**
     * Taps the card and presses Back while the entry is still running. Returns the largest
     * distance (px) between the collapsing container and the card it must fold into, so a
     * misaligned interruption shows up as a number instead of an opinion.
     */
    private fun interruption(
        test: Instrumentation,
        handler: Handler,
        window: android.view.Window,
        home: Bitmap,
        screen: Rect,
        card: Rect,
        tapX: Int,
        tapY: Int,
        log: StringBuilder,
    ): Int {
        tap(test, tapX, tapY)
        SystemClock.sleep(160)
        val back = Rect().also { checkNotNull(find(test, "返回")).getBoundsInScreen(it) }
        tap(test, back.centerX(), back.centerY())
        // Only the shrink phase is measured. The expansion is still running when Back is
        // pressed, and counting those full-screen frames reported a meaningless offset.
        var widest = 0
        var narrowest = Int.MAX_VALUE
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            val shot = copyFrame(test, handler, window)
            try {
                val box = changed(home, shot, screen) ?: continue
                // A frame that still spans a real container, not the residual tail.
                if (box.width() < card.width() / 2 || box.height() < card.height() / 2) continue
                widest = maxOf(widest, box.width())
                narrowest = minOf(narrowest, box.width())
                log.append("${box.width()}x${box.height()}@${box.left},${box.top} ")
            } finally {
                shot.recycle()
            }
        }
        check(widest > 0) { "no frame with a real container during the fold-back" }
        check(narrowest < widest) { "the fold-back never shrank: $log" }
        // The last frames belong to the flying label, which is its own shared node and lands
        // on the card's text bounds rather than the card's own bounds. Comparing those two
        // reported a bogus offset, so only the end state is asserted here.
        val settled = copyFrame(test, handler, window)
        val residue = changed(home, settled, screen)
        settled.recycle()
        check(residue == null) {
            "the fold-back left ${residue?.width()}x${residue?.height()} of the detail page on " +
                "screen instead of returning to the untouched list"
        }
        log.append("residue=none ")
        return widest - narrowest
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

    private fun changed(home: Bitmap, shot: Bitmap, area: Rect): Rect? {
        val change = Rect()
        for (y in area.top until area.bottom step 6) {
            for (x in area.left until area.right step 6) {
                val a = home.getPixel(x, y)
                val b = shot.getPixel(x, y)
                if (abs(Color.red(a) - Color.red(b)) > 24 ||
                    abs(Color.green(a) - Color.green(b)) > 24 ||
                    abs(Color.blue(a) - Color.blue(b)) > 24
                ) {
                    change.union(x, y, x + 1, y + 1)
                }
            }
        }
        return change.takeUnless { it.isEmpty }
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
