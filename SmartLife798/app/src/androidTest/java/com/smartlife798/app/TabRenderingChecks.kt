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
import android.view.MotionEvent
import android.view.InputDevice
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.ThemeMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Samples the always-present scan button in actual rendered tab-switch frames. */
object TabRenderingChecks {
    fun run(test: Instrumentation) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: Activity? = null
        val worker = HandlerThread("tab-pixels").apply { start() }
        val copying = AtomicBoolean()
        val samples = AtomicInteger()
        val missing = AtomicInteger()
        val errors = AtomicInteger()
        var listener: ViewTreeObserver.OnDrawListener? = null
        var stage = "guard"
        val originalMode = Store.themeModeName()
        val originalDynamic = Store.dynamicColor()
        try {
            check(Store.token.isNullOrBlank())
            test.runOnMainSync { Appearance.setMode(ThemeMode.LIGHT); Appearance.setDynamicColor(false) }
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true))
            await { find(test, "扫码") != null && find(test, "设备") != null }
            Thread.sleep(500)
            val rect = Rect().also { find(test, "扫码")!!.getBoundsInScreen(it) }
            val x = rect.centerX() + (rect.width() * 0.35f).toInt()
            val y = rect.centerY()
            val area = Rect(x, y, x + 2, y + 2)
            val taps = listOf("设备", "订单", "我的", "首页").associateWith { label ->
                Rect().also { find(test, label)!!.getBoundsInScreen(it) }
            }
            fun tap(label: String) {
                val bounds = taps.getValue(label)
                val start = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                        bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(test.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                    if (action == MotionEvent.ACTION_DOWN) Thread.sleep(30)
                }
            }
            val window = activity.window
            val view = window.decorView
            val draw = ViewTreeObserver.OnDrawListener {
                if (copying.compareAndSet(false, true)) {
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window, area, bitmap, { status ->
                        if (status == PixelCopy.SUCCESS) {
                            samples.incrementAndGet()
                            val pixel = bitmap.getPixel(0, 0)
                            if (Color.blue(pixel) < Color.red(pixel) * 1.3 ||
                                Color.blue(pixel) < Color.green(pixel) * 1.15) missing.incrementAndGet()
                        } else errors.incrementAndGet()
                        bitmap.recycle()
                        copying.set(false)
                    }, Handler(worker.looper))
                }
            }
            listener = draw
            test.runOnMainSync { view.viewTreeObserver.addOnDrawListener(draw) }
            repeat(4) {
                for ((tab, title) in listOf("设备" to "我的设备", "订单" to "订单与记录", "我的" to "钱包", "首页" to "扫码即刻使用")) {
                    stage = "tap $tab then await $title"
                    tap(tab)
                    await { find(test, title) != null }
                    Thread.sleep(100)
                }
            }
            Thread.sleep(300)
            check(samples.get() >= 16) { "insufficient rendered samples" }
            check(missing.get() == 0) { "scan button absent in ${missing.get()} copied frames" }
            check(errors.get() == 0) { "pixel copy failures" }
            result.putString("stream", "PASS: 16 real main-tab switches; rendered scan-button samples=${samples.get()}, missing=${missing.get()}, copyErrors=${errors.get()}; offline only, not an FPS measurement\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}: ${failure.message}; root=${test.uiAutomation.rootInActiveWindow?.packageName}, finishing=${activity?.isFinishing}; samples=${samples.get()}, missing=${missing.get()}, copyErrors=${errors.get()}\n")
        } finally {
            test.runOnMainSync {
                listener?.let { activity?.window?.decorView?.viewTreeObserver?.removeOnDrawListener(it) }
                activity?.finish()
                Appearance.setMode(ThemeMode.fromName(originalMode)); Appearance.setDynamicColor(originalDynamic)
            }
            worker.quitSafely()
        }
        test.finish(code, result)
    }
    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isVisibleToUser &&
            (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline); Thread.sleep(30) }
    }
}
