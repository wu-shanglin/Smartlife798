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
 * Samples presented frames while a shared card expands into its full-screen page.
 *
 * The container must stay opaque: the scan hero card on Home is a saturated brand blue the
 * detail page never paints, so that blue showing through the growing box means the card and
 * the page were cross-faded and the detail then pops in at full screen.
 *
 * The collapse is covered by [FoldBackChecks], which compares the restored screen against
 * the list captured before the card was opened; counting page-background pixels there is
 * blind because the list and the detail page share the same surface colours.
 */
object DetailMorphChecks {
    private const val Favorite = "3 栋 2 层淋浴机 02"
    /** Blue bleed allowed, as a percentage of the sampled container interior. */
    private const val MaxBleedPercent = 1

    fun run(test: Instrumentation, wallet: Boolean = false) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "offline guard"
        var activity: Activity? = null
        var trace = ""
        val thread = HandlerThread("morph-frames").apply { start() }
        val handler = Handler(thread.looper)
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val previousTheme = Store.themeModeName()
        val previousDynamic = Store.dynamicColor()
        val previousStartup = Store.startupPage()
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            check(android.provider.Settings.Global.getFloat(
                test.targetContext.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) >= 6f) { "set animator_duration_scale >= 6 to resolve the morph" }
            test.runOnMainSync {
                Appearance.setMode(ThemeMode.LIGHT)
                Appearance.setDynamicColor(false)
                Store.saveStartupPage(StartupPage.HOME)
            }
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true))
            val window = activity.window
            val name = if (wallet) "钱包" else Favorite
            stage = "reach source"
            if (wallet) {
                await { find(test, "我的") != null }
                val me = Rect().also { checkNotNull(find(test, "我的")).getBoundsInScreen(it) }
                tap(test, me.centerX(), me.centerY())
                await { find(test, "积分") != null && find(test, "钱包") != null }
            } else {
                await { find(test, "常用设备") != null && find(test, Favorite) != null }
            }
            SystemClock.sleep(1_200)
            val label = checkNotNull(find(test, name))
            val source = Rect().also { label.getBoundsInScreen(it) }
            val card = cardBounds(label)
            stage = "tap ${if (wallet) "wallet" else "device"} source $source"
            val home = copyFrame(test, handler, window)
            val size = test.targetContext.resources.displayMetrics
            val screen = Rect(0, 0, size.widthPixels, size.heightPixels)
            tap(test, source.centerX(), source.centerY())
            val start = SystemClock.uptimeMillis()
            val boxes = mutableListOf<Rect>()
            val frames = mutableListOf<Bitmap>()
            while (SystemClock.uptimeMillis() - start < 4_500) {
                val shot = copyFrame(test, handler, window)
                val box = changed(home, shot, screen)
                if (box == null) {
                    shot.recycle()
                    continue
                }
                boxes += Rect(box)
                frames += shot
            }
            check(boxes.size >= 4) { "too few frames: ${boxes.size}" }
            val last = boxes.last()
            check(last.width() >= size.widthPixels * 88 / 100) {
                "container never reached the page width: ${last.width()}x${last.height()}@${last.top}"
            }
            // Growth towards the viewport must be monotonic; a mid-flight shrink or a
            // jump back to the source means the box was replaced by a second animation.
            for (i in 1 until boxes.size) {
                val previous = boxes[i - 1]
                val current = boxes[i]
                check(current.width() >= previous.width() - 8 && current.height() >= previous.height() - 8) {
                    "container shrank mid-flight: ${previous.width()}x${previous.height()} -> " +
                        "${current.width()}x${current.height()} at frame $i"
                }
            }
            var worstBleed = 0
            var worstFrame = -1
            // Only frames after the incoming page has finished fading in can tell whether
            // the container is opaque. While it fades (220ms) the box legitimately shows the
            // outgoing page around the growing card: that is the container transform, not a
            // see-through dip. Before the fade completes the box is also still card-sized,
            // and the wallet's own balance is painted in the same blue.
            val settledFrom = boxes.size * 2 / 3
            for (i in settledFrom until frames.size - 1) {
                val box = boxes[i]
                if (box.width() < size.widthPixels * 60 / 100) continue
                val bleed = bleed(frames[i], box)
                trace += "${i}:${box.width()}x${box.height()}@${box.top}b$bleed "
                if (bleed > worstBleed) {
                    worstBleed = bleed
                    worstFrame = i
                }
            }
            frames.forEach { it.recycle() }
            if (!wallet) {
                check(worstBleed <= MaxBleedPercent) {
                    "the scan card's brand blue showed through the growing container at frame " +
                        "$worstFrame ($worstBleed% of sampled pixels); the card and the page are " +
                        "cross-faded instead of one opaque container transform"
                }
            }
            result.putString(
                "stream",
                "PASS ${if (wallet) "wallet" else "device"} morph frames=${boxes.size} " +
                    "reached=${last.width()}x${last.height()}@${last.top} " +
                    "blueBleed=${worstBleed}%${if (wallet) " (reference only)" else ""}; $trace\n",
            )
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL $stage: ${failure.message}; $trace\n")
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

    /**
     * Percentage of sampled container pixels that carry the scan card's saturated brand
     * blue (or a translucent blend of it). Neutral surfaces, dark text and the detail's
     * muted accent never match, so a non-zero value means the source page is visible.
     */
    private fun bleed(shot: Bitmap, box: Rect): Int {
        var hits = 0
        var total = 0
        for (y in box.top + 12 until box.bottom - 12 step 6) {
            for (x in box.left + 12 until box.right - 12 step 6) {
                total++
                val color = shot.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                // Brand blue #0055D6 and its lighter blends stay strongly blue-dominant
                // with little red; the detail page has no such colour.
                if (b > 150 && b - r > 90 && b - g > 60) hits++
            }
        }
        return if (total == 0) 0 else hits * 100 / total
    }

    /** The clickable ancestor of [label]: the card's real bounds, not the label's. */
    private fun cardBounds(label: AccessibilityNodeInfo): Rect {
        var node = label
        while (!node.isClickable) node = checkNotNull(node.parent) { "no clickable card ancestor" }
        return Rect().also { node.getBoundsInScreen(it) }
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
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
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
