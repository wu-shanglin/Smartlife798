package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.ThemeMode
import kotlin.math.abs

/** Exercises the home carousel and measures the displayed frames after a settled entry. */
object FavoriteReturnChecks {
    private const val Favorite = "3 栋 2 层淋浴机 02"

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: Activity? = null
        var stage = "offline guard"
        val samples = mutableListOf<String>()
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
            stage = "find home favorite"
            await { find(test, "常用设备") != null && find(test, Favorite) != null }
            SystemClock.sleep(700)
            val card = cardBounds(test)
            val home = frame(test)
            val area = Rect(12, (home.height * 0.07f).toInt(), home.width - 12, (home.height * 0.87f).toInt())
            try {
                fun open() {
                    tap(test, card)
                    await { find(test, "当前模式") != null && find(test, "返回") != null }
                    SystemClock.sleep(850)
                    check(find(test, "当前模式") != null)
                }
                stage = "settled hardware Back"
                open()
                val down = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    check(test.uiAutomation.injectInputEvent(
                        KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0), false,
                    ))
                }
                val exit = sampleExit(test, home, area)
                samples += "key=$exit"
                assertApproachesCard(exit, card, area)
                await { find(test, "常用设备") != null && find(test, "返回") == null }
                check(cardBounds(test) == card)

                stage = "settled toolbar Back"
                open()
                val back = bounds(checkNotNull(find(test, "返回")))
                tap(test, back, waitForUp = false)
                val toolbarExit = sampleExit(test, home, area)
                samples += "toolbar=$toolbarExit"
                assertApproachesCard(toolbarExit, card, area)
                await { find(test, "常用设备") != null && find(test, "返回") == null }
                check(cardBounds(test) == card)

                if (Build.VERSION.SDK_INT >= 34 && Settings.Secure.getInt(
                        test.targetContext.contentResolver, "navigation_mode", 0,
                    ) == 2) {
                    stage = "predictive preview and cancellation"
                    open()
                    val edgeY = home.height / 2
                    var start = SystemClock.uptimeMillis()
                    touch(test, MotionEvent.ACTION_DOWN, 1, edgeY, start)
                    val preview = (1..12).map { step ->
                        touch(test, MotionEvent.ACTION_MOVE, 1 + home.width * step / 32, edgeY, start)
                        changedBounds(test, home, area)
                    }.filterNotNull()
                    samples += "preview=$preview"
                    for (step in 11 downTo 0) {
                        touch(test, MotionEvent.ACTION_MOVE, 1 + home.width * step / 32, edgeY, start)
                        SystemClock.sleep(16)
                    }
                    touch(test, MotionEvent.ACTION_UP, 1, edgeY, start)
                    assertApproachesCard(preview, card, area)
                    await { find(test, "当前模式") != null && find(test, "返回") != null }
                    SystemClock.sleep(700)
                    check(changedBounds(test, home, area)?.width() ?: 0 > area.width() * 0.85)

                    stage = "predictive confirmation returns to the same card"
                    start = SystemClock.uptimeMillis()
                    touch(test, MotionEvent.ACTION_DOWN, 1, edgeY, start)
                    for (step in 1..12) {
                        touch(test, MotionEvent.ACTION_MOVE, 1 + home.width * step / 32, edgeY, start)
                        SystemClock.sleep(18)
                    }
                    touch(test, MotionEvent.ACTION_UP, 1 + home.width * 12 / 32, edgeY, start, wait = false)
                    val commit = sampleExit(test, home, area)
                    samples += "commit=$commit"
                    check(commit.any { Rect.intersects(it, card) && it.width() in 16..(area.width() * 0.85f).toInt() })
                    await { find(test, "常用设备") != null && find(test, "返回") == null }
                    check(cardBounds(test) == card)
                }
            } finally {
                home.recycle()
            }
            result.putString("stream", "PASS favorite source=$card; $samples\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL $stage: ${failure.message} at ${failure.stackTrace.firstOrNull()}; $samples\n")
        } finally {
            test.runOnMainSync {
                activity?.finish()
                Appearance.setMode(ThemeMode.fromName(previousTheme))
                Appearance.setDynamicColor(previousDynamic)
                Store.saveStartupPage(previousStartup)
            }
        }
        test.finish(code, result)
    }

    private fun assertApproachesCard(frames: List<Rect>, card: Rect, area: Rect) {
        val widthGap = area.width() - card.width()
        val heightGap = area.height() - card.height()
        val middle = frames.indexOfFirst {
            it.width() in (card.width() * 3 / 4)..(area.width() - widthGap / 8) &&
                it.height() in (card.height() + heightGap / 16)..(area.height() - heightGap / 12) &&
                it.top < card.top - heightGap / 16 &&
                Rect.intersects(it, card)
        }
        check(middle >= 0 && frames.drop(middle + 1).any { next ->
            val previous = frames[middle]
            Rect.intersects(next, card) && next.height() < previous.height() && next.top > previous.top &&
                abs(next.centerY() - card.centerY()) < abs(previous.centerY() - card.centerY())
        }) {
            "rendered exit did not shrink toward $card"
        }
    }

    private fun sampleExit(test: Instrumentation, home: Bitmap, area: Rect): List<Rect> {
        val end = SystemClock.uptimeMillis() + 780
        val frames = mutableListOf<Rect>()
        while (SystemClock.uptimeMillis() < end) {
            changedBounds(test, home, area)?.let { if (frames.lastOrNull() != it) frames += it }
        }
        return frames
    }

    private fun changedBounds(test: Instrumentation, home: Bitmap, area: Rect): Rect? {
        val screenshot = frame(test)
        try {
            val change = Rect()
            for (y in area.top until area.bottom step 9) {
                for (x in area.left until area.right step 9) {
                    val a = home.getPixel(x, y)
                    val b = screenshot.getPixel(x, y)
                    if (abs(Color.red(a) - Color.red(b)) > 26 ||
                        abs(Color.green(a) - Color.green(b)) > 26 ||
                        abs(Color.blue(a) - Color.blue(b)) > 26) {
                        change.union(x, y, x + 1, y + 1)
                    }
                }
            }
            return change.takeUnless { it.isEmpty }
        } finally {
            screenshot.recycle()
        }
    }

    private fun frame(test: Instrumentation): Bitmap =
        checkNotNull(test.uiAutomation.takeScreenshot()) { "no display frame" }

    private fun cardBounds(test: Instrumentation): Rect {
        var node = checkNotNull(find(test, Favorite))
        while (!node.isClickable) node = checkNotNull(node.parent)
        return bounds(node)
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { search(node.getChild(it)) }
        }
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return search(test.uiAutomation.rootInActiveWindow)
    }

    private fun tap(test: Instrumentation, bounds: Rect, waitForUp: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        touch(test, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), down)
        SystemClock.sleep(40)
        touch(test, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), down, waitForUp)
    }

    private fun touch(test: Instrumentation, action: Int, x: Int, y: Int, down: Long, wait: Boolean = true) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { check(test.uiAutomation.injectInputEvent(event, wait)) } finally { event.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "condition timed out" }
            SystemClock.sleep(35)
        }
    }
}
