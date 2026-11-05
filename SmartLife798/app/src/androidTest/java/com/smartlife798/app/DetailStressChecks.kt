package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Trace
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Repeat real AppRoot open/back/scroll input; inspect UI only outside measured intervals. */
object DetailStressChecks {
    // Every other route, including legacy GET commands, is an unexpected operation.
    private val allowedReads = setOf(
        "/api/v1/ui/app/master", "/api/v1/ui/app/dev/home/1", "/api/v1/ui/app/dev/status",
        "/api/v1/acc/wallet/owner", "/api/v1/bill/lst-owner", "/api/v1/ord/lst-owner",
        "/api/v1/acc/score/mission-lst", "/api/v1/sys/app/upt",
    )

    private data class Sample(
        val time: Long,
        val total: Long,
        val ui: Long,
        val layout: Long,
        val input: Long,
        val sync: Long,
        val command: Long,
        val swap: Long,
        val gpu: Long,
        val deadline: Long,
        val unknown: Long,
        val frameId: Long,
        val droppedReports: Int,
    )

    private data class Phase(val time: Long, val round: Int, val name: String)

    fun run(test: Instrumentation, inputOnly: Boolean = false, reopenOnly: Boolean = false) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated account guard"
        var activity: MainActivity? = null
        var started = false
        var metricsAttached = false
        var predictiveChecked = false
        val accessibilityFlags = test.uiAutomation.serviceInfo.flags
        val original = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val reads = AtomicInteger()
        val readDevices = Collections.synchronizedList(mutableListOf<String>())
        var sourceBounds: Rect? = null
        val writes = AtomicInteger()
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val firstReadFinished = CountDownLatch(1)
        val firstCall = AtomicReference<okhttp3.Call>()
        val collecting = AtomicBoolean()
        val frames = Collections.synchronizedList(mutableListOf<Sample>())
        val frameThread = HandlerThread("detail-stress-frames").apply { start() }
        val phases = mutableListOf<Phase>()
        fun phase(round: Int, name: String, input: () -> Unit) {
            phases.add(Phase(System.nanoTime(), round, name))
            Trace.beginSection("detail-stress:$round:$name")
            try { input() } finally { Trace.endSection() }
        }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
            if (collecting.get()) frames.add(Sample(
                metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                    metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION), metrics.getMetric(FrameMetrics.SYNC_DURATION),
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (android.os.Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1,
                if (android.os.Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1,
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                if (android.os.Build.VERSION.SDK_INT >= 36) metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID) else -1,
                droppedReports,
            ))
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
                    val number = reads.incrementAndGet()
                    readDevices.add("$number:${request.url.queryParameter("did")}")
                    if (reopenOnly && number == 1) {
                        firstCall.set(chain.call())
                        firstReadEntered.countDown()
                        try { check(releaseFirstRead.await(20, TimeUnit.SECONDS)) }
                        finally { firstReadFinished.countDown() }
                    }
                    val device = devices.first { it.id == request.url.queryParameter("did") }
                    val shown = if (reopenOnly) device.copy(name = if (number == 1) "迟到旧详情" else "当前新详情") else device
                    gson.toJson(FixtureData.detail(FixtureData.devices.first().id).copy(device = shown))
                }
                else -> null
            }
            if (request.method != "GET" || request.url.encodedPath !in allowedReads) writes.incrementAndGet()
            if (data == null) chain.proceed(request) else Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("offline").body("""{"code":0,"data":$data}""".toResponseBody()).build()
        }) }.build()
        try {
            stage = "isolated account guard tokenPresent=${!Store.token.isNullOrBlank()}, activePresent=${Store.active() != null}, pendingPresent=${Store.pendingStart() != null}"
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            started = true
            if (inputOnly) {
                val service = test.uiAutomation.serviceInfo
                service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo = service
            }
            clientField.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            await { click(test, "设备") }
            await { find(test, "性能设备 1") != null }
            SystemClock.sleep(800)
            val height = test.targetContext.resources.displayMetrics.heightPixels
            val x = test.targetContext.resources.displayMetrics.widthPixels / 2
            if (reopenOnly) {
                stage = "open first slow detail"
                val source = Rect().also { find(test, "性能设备 1")!!.getBoundsInScreen(it) }
                sourceBounds = source
                tap(test, source.centerX(), source.centerY())
                check(firstReadEntered.await(5, TimeUnit.SECONDS))
                SystemClock.sleep(120)
                stage = "Back and reopen the same source during exit"
                back(test)
                SystemClock.sleep(20)
                tap(test, source.centerX(), source.centerY())
                await { reads.get() >= 2 }
                await { find(test, "当前新详情") != null }
                await { find(test, "当前模式") != null }
                check(synchronized(readDevices) { readDevices.all { it.endsWith(":stress-1") } })
                stage = "inactive read cancels before its late response"
                await { firstCall.get()?.isCanceled() == true }
                stage = "late cancelled response cannot replace reopened detail"
                releaseFirstRead.countDown()
                check(firstReadFinished.await(5, TimeUnit.SECONDS))
                SystemClock.sleep(200)
                check(find(test, "迟到旧详情") == null && find(test, "当前新详情") != null)
                repeat(4) { index ->
                    stage = "repeat same-source immediate reopen $index; before=${reads.get()}"
                    val before = reads.get()
                    back(test)
                    SystemClock.sleep(20)
                    tap(test, source.centerX(), source.centerY())
                    await { reads.get() > before }
                    check(synchronized(readDevices) { readDevices.all { it.endsWith(":stress-1") } })
                    SystemClock.sleep(120)
                }
                await { find(test, "当前新详情") != null && find(test, "当前模式") != null }
                fun settledAction(): Boolean {
                    var node = find(test, "立即使用") ?: return false
                    while (!node.isClickable) node = node.parent ?: return false
                    return node.isEnabled
                }
                // Entry completion can dispose the old source in NavHost. Cover this
                // separately from bursts where the source is naturally still retained.
                repeat(4) { index ->
                    stage = "settled detail immediate reopen $index"
                    await { settledAction() }
                    val before = reads.get()
                    back(test)
                    SystemClock.sleep(20)
                    tap(test, source.centerX(), source.centerY())
                    await { reads.get() > before }
                }
                await { settledAction() }
                if (android.os.Build.VERSION.SDK_INT >= 34 && android.provider.Settings.Secure.getInt(
                        test.targetContext.contentResolver, "navigation_mode", 0) == 2) {
                    stage = "recreate detail before predictive source preview"
                    val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        val previous = checkNotNull(activity)
                        test.runOnMainSync { previous.recreate() }
                        activity = test.waitForMonitorWithTimeout(monitor, 10_000) as MainActivity
                    } finally { test.removeMonitor(monitor) }
                    await { settledAction() }
                    val y = height / 2
                    fun edgeBack(cancel: Boolean) {
                        val down = SystemClock.uptimeMillis()
                        touch(test, down, MotionEvent.ACTION_DOWN, 1, y.toFloat())
                        val end = if (cancel) x / 2 else x * 5 / 3
                        for (step in 1..12) {
                            touch(test, down, MotionEvent.ACTION_MOVE, 1 + end * step / 12, y.toFloat())
                            SystemClock.sleep(20)
                        }
                        if (cancel) {
                            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                                java.io.File(test.targetContext.getExternalFilesDir(null), "device-predictive-preview.png").outputStream().use {
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                bitmap.recycle()
                            }
                            for (step in 12 downTo 0) {
                                touch(test, down, MotionEvent.ACTION_MOVE, 1 + end * step / 12, y.toFloat())
                                SystemClock.sleep(20)
                            }
                        }
                        touch(test, down, MotionEvent.ACTION_UP, if (cancel) 1 else end, y.toFloat())
                    }
                    stage = "predictive cancellation retains the current detail"
                    edgeBack(cancel = true)
                    await { find(test, "当前模式") != null && settledAction() }
                    stage = "predictive commit returns to the recreated source"
                    edgeBack(cancel = false)
                    await { find(test, "我的设备") != null && find(test, "当前模式") == null }
                    tap(test, source.centerX(), source.centerY())
                    await { settledAction() }
                    predictiveChecked = true
                }
                stage = "first source drag after repeated interrupted entries"
                back(test)
                SystemClock.sleep(20)
                swipe(test, x, height * 3 / 4, height / 3)
                SystemClock.sleep(900)
                val after = find(test, "性能设备 1")?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
                check(find(test, "返回") == null && find(test, "性能设备 4") != null)
                check(after == null || after.top < source.top - height / 8)
                check(reads.get() >= 10 && writes.get() == 0)
                result.putString("stream", "PASS: five burst and four settled same-source reopen interruptions using real Key BACK and touch; " +
                    "the blocked old read is cancelled and its late response stays invisible; final first source drag moves; " +
                    "reads=${reads.get()}, predictiveCancelCommit=$predictiveChecked, zero commands; not a frame benchmark\n")
            } else if (inputOnly) {
                stage = "source-only drag positive control"
                val originalBounds = Rect().also { find(test, "性能设备 1")!!.getBoundsInScreen(it) }
                swipe(test, x, height * 3 / 4, height / 3)
                SystemClock.sleep(500)
                val movedBounds = find(test, "性能设备 1")?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
                stage += "; before=$originalBounds after=$movedBounds"
                check(movedBounds == null || movedBounds.top < originalBounds.top - height / 8)
                stage = "retained source releases its search focus when detail covers it"
                swipe(test, x, height / 3, height * 3 / 4)
                await { find(test, "搜索设备名称、编号或类型") != null }
                fun editor(label: String): AccessibilityNodeInfo {
                    var node = checkNotNull(find(test, label))
                    while (!node.isEditable) node = checkNotNull(node.parent)
                    return node
                }
                fun touchLabel(label: String) {
                    var previous: Rect? = null
                    var rect = Rect()
                    // These setup controls may still be moving in a shared morph.
                    // Keep this outside the separately timed first-drag assertions.
                    await {
                        val node = find(test, label) ?: return@await false
                        rect = Rect().also(node::getBoundsInScreen)
                        val stable = rect.height() >= 20 && rect == previous
                        previous = rect
                        stable
                    }
                    tap(test, rect.centerX(), rect.top + minOf(5, rect.height() / 2))
                }
                val search = editor("搜索设备名称、编号或类型")
                check(search.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "性能")
                }))
                await { editor("性能").isFocused }
                touchLabel("性能设备 1")
                await { find(test, "当前模式") != null }
                touchLabel("返回")
                await { find(test, "我的设备") != null && find(test, "性能") != null }
                stage = "source focus after Back focused=${editor("性能").isFocused}"
                check(!editor("性能").isFocused)
                stage = "focused source bottom scan and manual-input round trip"
                check(editor("性能").performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                fun imeVisible(): Boolean = test.uiAutomation.windows.any {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                val imeDeadline = SystemClock.uptimeMillis() + 1_500
                while (!imeVisible() && SystemClock.uptimeMillis() < imeDeadline) SystemClock.sleep(50)
                if (imeVisible()) {
                    stage = "dismiss the IME covering the bottom scan button"
                    back(test)
                    await { !imeVisible() }
                }
                stage = "focused source bottom scan opens"
                touchLabel("扫码")
                await { find(test, "手动输入") != null }
                stage = "scan opens manual input"
                touchLabel("手动输入")
                await { find(test, "输入设备编号") != null }
                stage = "manual input retains its own focus"
                check(editor("设备编号 / 二维码内容").performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                await { editor("设备编号 / 二维码内容").isFocused }
                touchLabel("返回扫一扫")
                await { find(test, "手动输入") != null }
                stage = "scan closes back to the retained source"
                touchLabel("关闭")
                await { find(test, "我的设备") != null && find(test, "性能") != null }
                check(!editor("性能").isFocused)
                stage = "scan immediately reopens and closes"
                touchLabel("扫码")
                await { find(test, "手动输入") != null }
                touchLabel("关闭")
                await { find(test, "我的设备") != null && find(test, "性能") != null }
                check(editor("性能").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }))
                repeat(5) { index ->
                    stage = "first source drag after quick Back $index"
                    swipe(test, x, height / 3, height * 3 / 4)
                    SystemClock.sleep(400)
                    await { find(test, "性能设备 1") != null }
                    val before = Rect().also { find(test, "性能设备 1")!!.getBoundsInScreen(it) }
                    tap(test, before.centerX(), before.centerY())
                    SystemClock.sleep(120)
                    back(test)
                    SystemClock.sleep(20)
                    // One gesture only: a second swipe would conceal a swallowed first DOWN.
                    swipe(test, x, height * 3 / 4, height / 3)
                    SystemClock.sleep(900)
                    check(find(test, "返回") == null && find(test, "当前模式") == null)
                    val after = find(test, "性能设备 1")?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
                    stage += "; before=$before after=$after"
                    check(find(test, "性能设备 4") != null) { "Source list is not visible after Back" }
                    check(after == null || after.top < before.top - height / 8) {
                        "First drag did not move source: before=$before after=$after"
                    }
                }
                check(reads.get() >= 5 && writes.get() == 0)
                result.putString("stream", "PASS: five real quick open/Back cycles; the first drag 20ms after Back moves the source list; " +
                    "reads=${reads.get()}, zero commands; source search focus releases; synchronous Key BACK, asynchronous touchscreen gestures; not a frame benchmark\n")
            } else {
                test.runOnMainSync {
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
                    metricsAttached = true
                }
                repeat(10) { index ->
                    stage = "open back and scroll $index"
                    // Reset the source outside the measured interval so every round uses the same input.
                    swipe(test, x, height / 3, height * 3 / 4)
                    await { find(test, "性能设备 1") != null }
                    val source = Rect().also { find(test, "性能设备 1")!!.getBoundsInScreen(it) }
                    collecting.set(true)
                    phase(index, "entry") {
                        tap(test, source.centerX(), source.centerY())
                        SystemClock.sleep(120)
                    }
                    phase(index, "back") {
                        back(test)
                        SystemClock.sleep(20)
                    }
                    phase(index, "scroll-up") { swipe(test, x, height * 3 / 4, height / 3) }
                    phase(index, "scroll-down") { swipe(test, x, height / 3, height * 3 / 4) }
                    phase(index, "settle") { SystemClock.sleep(400) }
                    collecting.set(false)
                    phases.add(Phase(System.nanoTime(), index, "outside"))
                }
                await { find(test, "我的设备") != null && find(test, "当前模式") == null }
                check(reads.get() >= 10 && writes.get() == 0)
                val snapshot = synchronized(frames) { frames.toList() }
                check(snapshot.size >= 30)
                java.io.File(test.targetContext.getExternalFilesDir(null), "detail-stress.csv").writeText(
                    "totalNs,uiNs,layoutNs,vsyncNs,round,phase,inputNs,syncNs,commandNs,swapNs,gpuNs,deadlineNs,unknownNs,frameId,droppedReports\n" +
                        snapshot.joinToString("\n") { sample ->
                            val phase = phases.lastOrNull { it.time <= sample.time }
                            "${sample.total},${sample.ui},${sample.layout},${sample.time},${phase?.round ?: -1},${phase?.name ?: "outside"}," +
                                "${sample.input},${sample.sync},${sample.command},${sample.swap},${sample.gpu},${sample.deadline}," +
                                "${sample.unknown},${sample.frameId},${sample.droppedReports}"
                        })
                fun summary(values: List<Long>): String {
                    val ms = values.sorted().map { it / 1_000_000.0 }
                    return "p50=${ms[(ms.size - 1) / 2]},p95=${ms[((ms.size - 1) * .95).toInt()]},max=${ms.last()}"
                }
                result.putString("stream", "PASS: 10 real open/back/scroll cycles; reads=${reads.get()},frames=${snapshot.size}," +
                    "over16ms=${snapshot.count { it.total > 16_666_667 }},over32ms=${snapshot.count { it.total > 33_333_334 }}; " +
                    "totalMs(${summary(snapshot.map { it.total })}); uiMs(${summary(snapshot.map { it.ui })}); " +
                    "layoutMs(${summary(snapshot.map { it.layout })}); " +
                    "deadlineMisses=${snapshot.count { it.deadline > 0 && it.total > it.deadline }}," +
                    "droppedMetricReports=${snapshot.sumOf { it.droppedReports }}; synchronous Key BACK; zero commands; emulator sample only\n")
            }
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            if ((reopenOnly || inputOnly) && started && activity != null) {
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(test.targetContext.getExternalFilesDir(null), if (inputOnly) "detail-input-failure.png" else "detail-reopen-failure.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val labels = listOf("我的设备", "当前模式", "当前新详情", "性能设备 1", "性能设备 0", "首页")
                stage += labels.joinToString(prefix = "; UI=", separator = " | ") { label ->
                    val node = find(test, label)
                    val rect = node?.let { Rect().also(node::getBoundsInScreen) }
                    "$label:$rect"
                }
            }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; source=$sourceBounds, requests=$readDevices, reads=${reads.get()},frames=${frames.size},writes=${writes.get()}\n")
        } finally {
            releaseFirstRead.countDown()
            collecting.set(false)
            test.runOnMainSync {
                activity?.let {
                    if (metricsAttached) it.window.removeOnFrameMetricsAvailableListener(listener)
                    it.setContent { }
                    it.viewModelStore.clear()
                    it.finish()
                }
                if (started) session.clear()
            }
            if (started) {
                val field = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
                check((field.get(null) as android.content.SharedPreferences).edit().commit())
            }
            frameThread.quitSafely()
            if (inputOnly && started) {
                val service = test.uiAutomation.serviceInfo
                service.flags = accessibilityFlags
                test.uiAutomation.serviceInfo = service
            }
            clientField.set(null, original)
        }
        test.finish(code, result)
    }

    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else {
                if (android.os.Build.VERSION.SDK_INT < 33 && !node.refresh()) emptyList()
                else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
            }
        return nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    }
    private fun click(test: Instrumentation, label: String): Boolean {
        var node = find(test, label) ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun tap(test: Instrumentation, x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        touch(test, down, MotionEvent.ACTION_DOWN, x, y.toFloat())
        SystemClock.sleep(50)
        touch(test, down, MotionEvent.ACTION_UP, x, y.toFloat())
    }

    private fun touch(test: Instrumentation, down: Long, action: Int, x: Int, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { check(test.uiAutomation.injectInputEvent(event, false)) }
        finally { event.recycle() }
    }

    private fun back(test: Instrumentation) {
        // GLOBAL_ACTION_BACK returns before Back reaches the app; a following DOWN
        // could correctly hit the detail page before navigation has even changed.
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            check(test.uiAutomation.injectInputEvent(android.view.KeyEvent(down, SystemClock.uptimeMillis(),
                action, android.view.KeyEvent.KEYCODE_BACK, 0), true))
        }
    }

    private fun swipe(test: Instrumentation, x: Int, from: Int, to: Int) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..10) {
            touch(test, down, if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                x, from + (to - from) * step / 10f)
            SystemClock.sleep(16)
        }
        // Finish a drag without a fling: the assertion must measure this first gesture.
        SystemClock.sleep(80)
        touch(test, down, MotionEvent.ACTION_UP, x, to.toFloat())
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
