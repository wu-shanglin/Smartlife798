package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.FixtureData
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Repeat real AppRoot open/back/scroll input; inspect UI only outside measured intervals. */
object DetailStressChecks {
    private data class Sample(val total: Long, val ui: Long, val layout: Long)

    fun run(test: Instrumentation) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated account guard"
        var activity: MainActivity? = null
        var started = false
        val original = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val collecting = AtomicBoolean()
        val frames = Collections.synchronizedList(mutableListOf<Sample>())
        val frameThread = HandlerThread("detail-stress-frames").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (collecting.get()) frames.add(Sample(metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                    metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)))
        }
        val devices = (0 until 60).map {
            FixtureData.devices[it % FixtureData.devices.size].copy(id = "stress-$it", name = "性能设备 $it")
        }
        val gson = Gson()
        val fixture = original.newBuilder().apply { interceptors().add(0, okhttp3.Interceptor { chain ->
            val request = chain.request()
            val data = when (request.url.encodedPath) {
                "/api/v1/ui/app/master" -> gson.toJson(mapOf("account" to mapOf("id" to "preview-user", "name" to "离线性能用户"), "favos" to devices))
                "/api/v1/ui/app/dev/home/1" -> {
                    reads.incrementAndGet()
                    val device = devices.first { it.id == request.url.queryParameter("did") }
                    gson.toJson(FixtureData.detail(FixtureData.devices.first().id).copy(device = device))
                }
                else -> null
            }
            if (request.method != "GET" || request.url.encodedPath in setOf("/api/v1/dev/start", "/api/v1/dev/end", "/api/v1/trans/prepay", "/api/v1/trans/close")) writes.incrementAndGet()
            if (data == null) chain.proceed(request) else Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("offline").body("""{"code":0,"data":$data}""".toResponseBody()).build()
        }) }.build()
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            started = true
            clientField.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            await { click(test, "设备") }
            await { find(test, "性能设备 1") != null }
            SystemClock.sleep(800)
            test.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper)) }
            val height = test.targetContext.resources.displayMetrics.heightPixels
            val x = test.targetContext.resources.displayMetrics.widthPixels / 2
            repeat(10) { index ->
                stage = "open back and scroll $index"
                // Reset the source outside the measured interval so every round uses the same input.
                swipe(test, x, height / 3, height * 3 / 4)
                await { find(test, "性能设备 1") != null }
                val source = Rect().also { find(test, "性能设备 1")!!.getBoundsInScreen(it) }
                collecting.set(true)
                tap(test, source.centerX(), source.centerY())
                SystemClock.sleep(120)
                test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(20)
                swipe(test, x, height * 3 / 4, height / 3)
                swipe(test, x, height / 3, height * 3 / 4)
                SystemClock.sleep(400)
                collecting.set(false)
            }
            await { find(test, "我的设备") != null && find(test, "当前模式") == null }
            check(reads.get() >= 10 && writes.get() == 0)
            val snapshot = synchronized(frames) { frames.toList() }
            check(snapshot.size >= 30)
            java.io.File(test.targetContext.getExternalFilesDir(null), "detail-stress.csv").writeText(
                "totalNs,uiNs,layoutNs\n" + snapshot.joinToString("\n") { "${it.total},${it.ui},${it.layout}" })
            fun summary(values: List<Long>): String {
                val ms = values.sorted().map { it / 1_000_000.0 }
                return "p50=${ms[(ms.size - 1) / 2]},p95=${ms[((ms.size - 1) * .95).toInt()]},max=${ms.last()}"
            }
            result.putString("stream", "PASS: 10 real open/back/scroll cycles; reads=${reads.get()},frames=${snapshot.size}," +
                "over16ms=${snapshot.count { it.total > 16_666_667 }},over32ms=${snapshot.count { it.total > 33_333_334 }}; " +
                "totalMs(${summary(snapshot.map { it.total })}); uiMs(${summary(snapshot.map { it.ui })}); " +
                "layoutMs(${summary(snapshot.map { it.layout })}); zero commands; emulator sample only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; reads=${reads.get()},frames=${frames.size},writes=${writes.get()}\n")
        } finally {
            collecting.set(false)
            test.runOnMainSync {
                activity?.let { it.window.removeOnFrameMetricsAvailableListener(listener); it.setContent { }; it.viewModelStore.clear(); it.finish() }
                if (started) session.clear()
            }
            if (started) {
                val field = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
                check((field.get(null) as android.content.SharedPreferences).edit().commit())
            }
            frameThread.quitSafely()
            clientField.set(null, original)
        }
        test.finish(code, result)
    }

    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        return nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    }
    private fun click(test: Instrumentation, label: String): Boolean {
        var node = find(test, label) ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun tap(test: Instrumentation, x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            test.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0))
        }
    }
    private fun swipe(test: Instrumentation, x: Int, from: Int, to: Int) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = from + (to - from) * minOf(step, 10) / 10f
            test.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y, 0))
            SystemClock.sleep(16)
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        error("Expected UI state not reached")
    }
}
