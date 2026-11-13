package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Store
import java.util.concurrent.atomic.AtomicInteger

/** Real touch hit testing during a 3x shared transition, in the isolated offline APK. */
object NavigationInterruptionChecks {
    fun run(test: Instrumentation) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: Activity? = null
        var stage = "guard"
        val reads = AtomicInteger()
        val originalTheme = Store.themeModeName()
        val originalDynamic = Store.dynamicColor()
        val original = Api.client
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            test.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val scale = shell("settings get global animator_duration_scale")
        try {
            check(Store.token.isNullOrBlank())
            field.set(null, original.newBuilder().apply { interceptors().add(0, okhttp3.Interceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/dev/home/1")) reads.incrementAndGet()
                chain.proceed(chain.request())
            }) }.build())
            test.runOnMainSync {
                com.smartlife798.app.core.Appearance.setMode(com.smartlife798.app.core.ThemeMode.LIGHT)
                com.smartlife798.app.core.Appearance.setDynamicColor(false)
            }
            shell("settings put global animator_duration_scale 3")
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true))
            await { click(test, "设备") || find(test, "我的设备") != null }
            await { find(test, "3 栋 2 层淋浴机 02") != null }
            Thread.sleep(1000)
            val source = Rect().also { find(test, "3 栋 2 层淋浴机 02")!!.getBoundsInScreen(it) }
            stage = "open first detail"
            tap(test, source.centerX(), source.centerY())
            await { reads.get() >= 1 }
            Thread.sleep(2000)
            check(find(test, "返回") != null)
            repeat(5) { index ->
                stage = "reverse and reopen during return $index"
                val before = reads.get()
                stage += " before=$before source=$source"
                test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(120)
                tap(test, source.centerX(), source.centerY())
                val start = SystemClock.elapsedRealtime()
                await(1000) { reads.get() > before }
                check(SystemClock.elapsedRealtime() - start < 1000)
                // A retained outgoing page can finish another read. Before the
                // next Back, also require the new detail's active action. Keep
                // cross-process accessibility queries outside the request timer.
                await { find(test, "返回") != null }
                Thread.sleep(120)
            }
            stage = "system back during entry"
            test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { find(test, "我的设备") != null }
            Thread.sleep(2000)
            check(find(test, "当前模式") == null)
            stage = "wallet draws intermediate return frames over its source"
            check(click(test, "我的"))
            await { find(test, "钱包") != null }
            Thread.sleep(1200)
            val profilePixels = screenPixels(test)
            check(click(test, "钱包"))
            await { find(test, "钱包余额") != null }
            Thread.sleep(2000)
            val walletPixels = screenPixels(test)
            check(!walletPixels.contentEquals(profilePixels))
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            val changingPixels = walletPixels.indices.filter { walletPixels[it] != profilePixels[it] }
            val returnFrames = List(5) {
                Thread.sleep(100)
                screenPixels(test)
            }
            val profileDifferences = returnFrames.map { frame -> changingPixels.count { frame[it] != profilePixels[it] } }
            val walletDifferences = returnFrames.map { frame -> changingPixels.count { frame[it] != walletPixels[it] } }
            stage += " profile=$profileDifferences wallet=$walletDifferences"
            check(returnFrames.indices.count { profileDifferences[it] > 20 && walletDifferences[it] > 20 } >= 2)
            check(profileDifferences.first() > profileDifferences.last() + 20)
            await { find(test, "钱包余额") == null && find(test, "钱包") != null }
            await { click(test, "钱包") }
            await { find(test, "钱包余额") != null }
            check(click(test, "返回"))
            await { find(test, "钱包余额") == null }
            check(click(test, "设备"))
            await { find(test, "我的设备") != null }
            Thread.sleep(1000)
            stage = "capture light and dark page hierarchy"
            capture(test, "devices.png")
            check(click(test, "订单"))
            await { find(test, "订单与记录") != null }
            Thread.sleep(1000)
            capture(test, "orders.png")
            await { click(test, "待付款 1") }
            Thread.sleep(500)
            capture(test, "preview_bills.png")
            check(click(test, "首页"))
            await { find(test, "扫码使用") != null }
            Thread.sleep(1000)
            capture(test, "home.png")
            test.runOnMainSync { com.smartlife798.app.core.Appearance.setMode(com.smartlife798.app.core.ThemeMode.DARK) }
            Thread.sleep(700)
            capture(test, "dark.png")
            check(click(test, "订单"))
            await { find(test, "订单与记录") != null }
            Thread.sleep(700)
            capture(test, "orders-dark.png")
            result.putString("stream", "PASS: five system-Back/real-touch detail reopen interruptions at 3x, Back during entry, and intermediate wallet return frames with reopen and toolbar Back. Offline only.\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(test.targetContext.cacheDir, "interrupt-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val sourceNow = Rect().also { find(test, "3 栋 2 层淋浴机 02")?.getBoundsInScreen(it) }
            result.putString("stream", "FAIL sourceNow=$sourceNow back=${find(test, "返回") != null} at $stage: ${error.javaClass.simpleName} at ${error.stackTrace.firstOrNull()}, reads=${reads.get()}\n")
        } finally {
            test.runOnMainSync {
                activity?.finish()
                com.smartlife798.app.core.Appearance.setMode(com.smartlife798.app.core.ThemeMode.fromName(originalTheme))
                com.smartlife798.app.core.Appearance.setDynamicColor(originalDynamic)
            }
            shell(if (scale == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $scale")
            field.set(null, original)
        }
        test.finish(code, result)
    }

    private fun capture(test: Instrumentation, name: String) {
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(test.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        } ?: error("screenshot unavailable")
    }

    private fun screenPixels(test: Instrumentation): IntArray {
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        return try {
            // Exclude the status and navigation bars and sample the same grid in each frame.
            IntArray(36 * 44) { index ->
                bitmap.getPixel(bitmap.width * (2 + index % 36) / 40, bitmap.height * (3 + index / 36) / 50)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return nodes(test.uiAutomation.rootInActiveWindow).firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }
    }

    private fun click(test: Instrumentation, label: String): Boolean {
        var node = find(test, label) ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tap(test: Instrumentation, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(test.uiAutomation.injectInputEvent(event, true))
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(50)
            event.recycle()
        }
    }

    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(30)
        }
        error("condition timed out")
    }
}
