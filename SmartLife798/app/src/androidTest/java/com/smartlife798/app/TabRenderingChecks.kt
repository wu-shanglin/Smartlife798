package com.smartlife798.app

import android.app.Activity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.ui.LoginScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
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
        var started = false
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
            check(Store.token.isNullOrBlank() && Store.profileJson == null &&
                Store.active() == null && Store.pendingStart() == null)
            started = true
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
            test.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(draw) }
            listener = null
            stage = "capture reference theme pairs"
            fun capture(name: String) {
                test.waitForIdleSync()
                val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
                try {
                    java.io.File(test.targetContext.getExternalFilesDir(null), name).outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
            capture("home.png")
            test.runOnMainSync { Appearance.setMode(ThemeMode.DARK) }
            Thread.sleep(500)
            capture("dark.png")
            test.runOnMainSync { Appearance.setMode(ThemeMode.LIGHT) }
            Thread.sleep(500)
            tap("设备")
            await { find(test, "我的设备") != null }
            Thread.sleep(500)
            capture("devices.png")
            tap("订单")
            await { find(test, "订单与记录") != null }
            Thread.sleep(500)
            capture("orders.png")
            var pending = find(test, "待付款 1") ?: error("pending tab absent")
            while (!pending.isClickable) pending = pending.parent ?: error("pending action absent")
            check(pending.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(500)
            capture("preview_bills.png")
            fun click(label: String) {
                var action = find(test, label) ?: error("missing fixture action")
                while (!action.isClickable) action = action.parent ?: error("missing click parent")
                check(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            stage = "capture settings and wallet"
            tap("我的")
            await { find(test, "修改昵称") != null }
            Thread.sleep(500)
            capture("me.png")
            click("钱包")
            await { find(test, "充值金额") != null }
            Thread.sleep(700)
            capture("wallet.png")
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await { find(test, "修改昵称") != null }
            stage = "capture device detail"
            tap("设备")
            await { find(test, "我的设备") != null }
            click("3 栋 2 层淋浴机 02")
            await { find(test, "当前模式") != null }
            Thread.sleep(700)
            capture("device.png")
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await { find(test, "我的设备") != null }
            tap("我的")
            await { find(test, "修改昵称") != null }
            stage = "capture appearance in both modes"
            val appearanceAnchor = if (android.os.Build.VERSION.SDK_INT >= 31) "动态取色开关" else "跟随系统"
            repeat(12) {
                if (find(test, appearanceAnchor) == null) {
                    val scroll = nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }
                    check(scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
                    Thread.sleep(250)
                }
            }
            check(find(test, appearanceAnchor) != null)
            if (android.os.Build.VERSION.SDK_INT < 31) check(find(test, "动态取色开关") == null)
            capture("appearance_light.png")
            test.runOnMainSync { Appearance.setMode(ThemeMode.DARK) }
            Thread.sleep(600)
            capture("appearance_dark.png")
            stage = "capture login form without submitting"
            test.runOnMainSync {
                val screen = activity as MainActivity
                val vm = ViewModelProvider(screen)[MainViewModel::class.java]
                screen.setContent { SmartLifeTheme(darkTheme = false, dynamicColor = false) {
                    LoginScreen(vm = vm)
                } }
            }
            Thread.sleep(700)
            capture("login.png")
            result.putString("stream", "PASS: 16 real main-tab switches; rendered scan-button samples=${samples.get()}, missing=${missing.get()}, copyErrors=${errors.get()}; light/dark palette, main pages, device detail, wallet and settings captures; offline only, not an FPS measurement\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; root=${test.uiAutomation.rootInActiveWindow?.packageName}, finishing=${activity?.isFinishing}; samples=${samples.get()}, missing=${missing.get()}, copyErrors=${errors.get()}\n")
        } finally {
            test.runOnMainSync {
                listener?.let { activity?.window?.decorView?.viewTreeObserver?.removeOnDrawListener(it) }
                activity?.finish()
                if (started) {
                    (activity as? MainActivity)?.viewModelStore?.clear()
                    com.smartlife798.app.core.session.clear()
                }
                Appearance.setMode(ThemeMode.fromName(originalMode)); Appearance.setDynamicColor(originalDynamic)
            }
            worker.quitSafely()
            if (started) {
                val prefs = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
                    .get(null) as android.content.SharedPreferences
                check(prefs.edit().commit())
            }
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
