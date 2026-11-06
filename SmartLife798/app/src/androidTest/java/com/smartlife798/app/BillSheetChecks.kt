package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.BillDetail
import com.smartlife798.app.core.BillStatus
import com.smartlife798.app.core.FixtureData
import com.smartlife798.app.core.Product
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.OrdersScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** Real order navigation with fully intercepted reads; never submits a payment or device command. */
object BillSheetChecks {
    fun run(test: Instrumentation) {
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val originalPolicy = Store.updatePolicyJson
        val originalProtocol = Store.originalAppVersion
        val writes = AtomicInteger()
        val details = AtomicInteger()
        val channels = AtomicInteger()
        val measurements = mutableListOf<String>()
        val predictiveBack = android.os.Build.VERSION.SDK_INT >= 34 &&
            android.provider.Settings.Secure.getInt(test.targetContext.contentResolver, "navigation_mode", 0) == 2
        var overflowSelections = 0
        val dark = mutableStateOf(false)
        val short = Bill(id = "offline-short", msg = "短账单夹具", status = BillStatus.PAID, payment = 1.0)
        val long = FixtureData.bills().first().copy(
            id = "offline-long", msg = "长账单夹具", status = BillStatus.UNPAID,
            note = "长备注用于验证换行与滚动。".repeat(16),
            prds = (1..18).map { Product(name = "商品明细 $it：离线测试长商品名称", count = 2, curPrice = 0.5) },
        )
        val rows = listOf(short, long)
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            val gson = Gson()
            clientField.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val data = when {
                    path.endsWith("/bill/lst-owner") -> gson.toJson(rows)
                    path.endsWith("/bill/view-full") -> {
                        val read = details.incrementAndGet()
                        Thread.sleep(if (read >= 3) 1800 else 250)
                        val id = chain.request().url.queryParameter("id")
                        // Also cover view-full omitting bill: production must retain the clicked row.
                        gson.toJson(if (id == short.id) BillDetail() else BillDetail(bill = long))
                    }
                    path.endsWith("/bill/pay/channels") -> {
                        channels.incrementAndGet()
                        Thread.sleep(450)
                        """{"channels":[{"type":92,"name":"账户余额"},{"type":21,"name":"支付宝"}]}"""
                    }
                    chain.request().method != "GET" || path.startsWith("/api/v1/trans/") || path.contains("/dev/start") || path.contains("/dev/end") -> {
                        writes.incrementAndGet()
                        "null"
                    }
                    else -> "[]"
                }
                val body = if (path.endsWith("/bill/pay/channels") && channels.get() == 1) {
                    """{"code":-1,"msg":"离线渠道错误"}"""
                } else """{"code":0,"data":$data}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("offline fixture").body(body.toResponseBody()).build()
            }.build())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            val screen = activity
            test.runOnMainSync {

                session.token = "offline-bill-fixture"
                val vm = ViewModelProvider(screen)[MainViewModel::class.java]
                screen.setContent { SmartLifeTheme(darkTheme = dark.value) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.statusBarsPadding()) {
                        OrdersScreen(vm) { _, _ -> }
                    }
                } }
            }
            stage = "button group filters and overflow"
            await { find(test, "短账单夹具") != null && find(test, "长账单夹具") != null }
            if (choose(test, "待付款 1")) overflowSelections += 1
            await { find(test, "短账单夹具") == null && find(test, "长账单夹具") != null }
            if (choose(test, "已完成 1")) overflowSelections += 1
            await { find(test, "短账单夹具") != null && find(test, "长账单夹具") == null }
            if (choose(test, "全部 2")) overflowSelections += 1
            for (label in listOf("短账单夹具", "长账单夹具")) {
                stage = "open $label"
                await { find(test, label) != null }
                click(test, label)
                // The summary now opens immediately and covers the source row.
                // A second tap must target the visible sheet, not the hidden list.
                await { find(test, "账单详情") != null }
                stage = "settled height $label"
                val before = stableTitle(test, "账单详情", measurements)
                if (predictiveBack && label == "短账单夹具") {
                    stage = "predictive back previews downward and cancels"
                    val cancel = startBackGesture(test)
                    val preview = bounds(find(test, "账单详情") ?: error("preview missing"))
                    check(preview.top > before.top + 16 && preview.width() == before.width()) {
                        "expected vertical preview: before=$before preview=$preview"
                    }
                    screenshot(test, "bill-back-preview.png")
                    cancel(false)
                    await { find(test, "账单详情")?.let { bounds(it) } == before }

                    stage = "committed back continues below the preview before removing detail"
                    val commit = startBackGesture(test)
                    val releaseTop = bounds(find(test, "账单详情") ?: error("preview missing")).top
                    commit(true)
                    val exitTop = awaitVisibleExit(test, releaseTop)
                    await { find(test, "账单详情") == null && find(test, label) != null }
                    measurements.add("predictive cancel restored bounds; top=${before.top} -> $releaseTop -> $exitTop at commit")

                    stage = "reopen after committed back"
                    click(test, label)
                    await { find(test, "账单详情") != null }
                    stableTitle(test, "账单详情", measurements)
                }
                if (label == "长账单夹具") {
                    check(find(test, "去支付") != null)
                    fun actionBounds(label: String): android.graphics.Rect {
                        var node = find(test, label) ?: error("action missing: $label")
                        while (!node.isClickable) node = node.parent ?: error("action has no click target")
                        return bounds(node)
                    }
                    val cancel = actionBounds("暂不支付")
                    val pay = actionBounds("去支付")
                    check(cancel.left < pay.left && cancel.top == pay.top && cancel.bottom == pay.bottom)
                    check(kotlin.math.abs(cancel.width() - pay.width()) <= 1)
                    screenshot(test, "bill-detail.png")
                    stage = "long content scroll keeps header and payment reachable"
                    repeat(3) { scroll(test); Thread.sleep(180) }
                    val after = bounds(find(test, "账单详情") ?: error("header scrolled away"))
                    check(before == after)
                    check(find(test, "去支付") != null)
                    stage = "detail hides before payment sheet opens"
                    click(test, "去支付")
                    await { find(test, "支付账单") != null && find(test, "账单详情") == null }
                    stage = "channel error can be explicitly retried"
                    await { find(test, "加载失败") != null }
                    click(test, "重试")
                    await { find(test, "账户余额") != null }
                    stableTitle(test, "支付账单", measurements)
                    screenshot(test, "bill-payment.png")
                    test.runOnMainSync { dark.value = true }
                    Thread.sleep(600)
                    screenshot(test, "bill-payment-dark.png")
                    test.runOnMainSync { dark.value = false }
                    Thread.sleep(400)
                    check(channels.get() == 2 && writes.get() == 0)
                }
                stage = "Back closes sheet for $label"
                val backStart = find(test, "账单详情")?.let { bounds(it).top }
                check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                if (backStart != null) awaitVisibleExit(test, backStart)
                await { find(test, "账单详情") == null && find(test, "支付账单") == null && find(test, "短账单夹具") != null }
            }
            stage = "reopen and logout isolation"
            click(test, "短账单夹具")
            await { find(test, "账单详情") != null }
            click(test, "关闭账单详情")
            await { find(test, "账单详情") == null && find(test, "短账单夹具") != null }
            Thread.sleep(2200)
            check(find(test, "账单详情") == null) { "late detail response reopened dismissed sheet" }
            stage = "drag handle dismisses detail"
            click(test, "短账单夹具")
            await { find(test, "账单详情") != null }
            val dragTitle = stableTitle(test, "账单详情", measurements)
            val display = test.targetContext.resources.displayMetrics
            val startY = (dragTitle.top - 32 * display.density).toInt()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                "input swipe ${display.widthPixels / 2} $startY ${display.widthPixels / 2} ${display.heightPixels - 40} 500"
            )).use { it.readBytes() }
            await { find(test, "账单详情") == null }
            check(details.get() == (if (predictiveBack) 5 else 4) && writes.get() == 0)
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            check(Store.updatePolicyJson == originalPolicy && Store.originalAppVersion == originalProtocol)
            result.putString("stream", "PASS: immediate summary, late response cannot reopen, short/missing bill fallback, long content, stable detail/payment bounds, fixed header/pay action after scroll, sequential sheet handoff, channel error/retry, Back/reopen, handle drag dismiss; reads=${details.get()}, payments/commands=${writes.get()}, overflowSelections=$overflowSelections; ${measurements.joinToString()}; fully intercepted offline fixtures\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            screenshot(test, "bill-sheet-failure.png")
            result.putString("visible", nodes(root(test)).filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" | "))
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}:${error.message}; source=${error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }}; finishing=${activity?.isFinishing}, root=${test.uiAutomation.rootInActiveWindow?.packageName}, reads=${details.get()}, channels=${channels.get()}, writes=${writes.get()}; ${measurements.joinToString()}; visible=${nodes(root(test)).filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" | ")}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
            clientField.set(null, originalClient)
            session.token = originalToken

        }
        test.finish(code, result)
    }

    private fun startBackGesture(test: Instrumentation): (Boolean) -> Unit {
        val display = test.targetContext.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        val distance = display.widthPixels * 0.5f
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, display.heightPixels * 0.6f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(test.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, 1f)
        for (step in 1..20) {
            touch(MotionEvent.ACTION_MOVE, 1f + distance * step / 20)
            Thread.sleep(20)
        }
        Thread.sleep(120)
        return { commit ->
            if (!commit) {
                for (step in 19 downTo 0) {
                    touch(MotionEvent.ACTION_MOVE, 1f + distance * step / 20)
                    Thread.sleep(20)
                }
            }
            touch(MotionEvent.ACTION_UP, if (commit) 1f + distance else 1f)
        }
    }

    private fun awaitVisibleExit(test: Instrumentation, fromTop: Int): Int {
        val deadline = SystemClock.uptimeMillis() + 750
        val displayBottom = test.targetContext.resources.displayMetrics.heightPixels
        while (SystemClock.uptimeMillis() < deadline) {
            val title = find(test, "账单详情") ?: error("detail disappeared before a visible exit frame")
            val rect = bounds(title)
            if (rect.top >= fromTop + 16 && rect.height() > 0 && rect.bottom < displayBottom) return rect.top
            Thread.sleep(16)
        }
        error("no visible downward exit after top=$fromTop")
    }

    private fun stableTitle(test: Instrumentation, title: String, measurements: MutableList<String>): Rect {
        Thread.sleep(1200)
        val samples = (1..24).map {
            val rect = bounds(find(test, title) ?: error("missing title"))
            Thread.sleep(80)
            rect
        }
        val spread = samples.maxOf { it.top } - samples.minOf { it.top }
        measurements.add("$title topSpread=$spread px")
        check(spread <= 1)
        val window = root(test) ?: error("missing sheet window")
        val pane = nodes(window).firstOrNull { it.paneTitle != null } ?: error("missing sheet pane")
        check(kotlin.math.abs(bounds(pane).bottom - bounds(window).bottom) <= 1) { "pane=${bounds(pane)},window=${bounds(window)}" }
        return samples.last()
    }

    private fun screenshot(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("missing screenshot")
        File(test.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow ?: test.uiAutomation.windows
            .filter { it.isActive || it.isFocused }
            .firstNotNullOfOrNull { it.root?.takeIf { root -> root.packageName == test.targetContext.packageName } }
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun find(test: Instrumentation, text: String): AccessibilityNodeInfo? =
        nodes(root(test)).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun click(test: Instrumentation, text: String) {
        clickNode(find(test, text))
    }

    private fun choose(test: Instrumentation, text: String): Boolean {
        val overflow = find(test, text) == null
        if (overflow) {
            clickNode(nodes(root(test)).lastOrNull { it.contentDescription?.toString() == "更多选项" })
            await { find(test, text) != null }
        }
        click(test, text)
        return overflow
    }

    private fun clickNode(candidate: AccessibilityNodeInfo?) {
        var node = candidate
        while (node != null) {
            if (node.isClickable) { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            node = node.parent
        }
        error("missing click target")
    }

    private fun scroll(test: Instrumentation) {
        val node = nodes(root(test)).firstOrNull { it.isScrollable } ?: error("missing scroll area")
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline)
            Thread.sleep(80)
        }
    }
}
