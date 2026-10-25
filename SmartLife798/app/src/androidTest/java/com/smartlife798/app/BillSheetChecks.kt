package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.BillDetail
import com.smartlife798.app.core.BillStatus
import com.smartlife798.app.core.Preview
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
        var overflowSelections = 0
        val short = Bill(id = "offline-short", msg = "短账单夹具", status = BillStatus.PAID, payment = 1.0)
        val long = Preview.bills().first().copy(
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
                        details.incrementAndGet()
                        Thread.sleep(250)
                        val id = chain.request().url.queryParameter("id")
                        // Also cover view-full omitting bill: production must retain the clicked row.
                        gson.toJson(if (id == short.id) BillDetail() else BillDetail(bill = long))
                    }
                    path.endsWith("/bill/pay/channels") -> {
                        channels.incrementAndGet()
                        Thread.sleep(450)
                        """{"channels":[{"type":92,"name":"账户余额"},{"type":21,"name":"支付宝"}]}"""
                    }
                    path.contains("prepay") || path.endsWith("/confirm") || path.contains("/dev/start") || path.contains("/dev/end") -> {
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
                .putExtra("preview", true)) as MainActivity
            val screen = activity
            test.runOnMainSync {
                Preview.enabled = false
                session.token = "offline-bill-fixture"
                val vm = ViewModelProvider(screen)[MainViewModel::class.java]
                screen.setContent { SmartLifeTheme { OrdersScreen(vm) { _, _ -> } } }
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
                click(test, label)
                await { find(test, "账单详情") != null }
                stage = "settled height $label"
                val before = stableTitle(test, "账单详情", measurements)
                if (label == "长账单夹具") {
                    check(find(test, "去支付") != null)
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
                    check(channels.get() == 2 && writes.get() == 0)
                }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await { find(test, "账单详情") == null && find(test, "支付账单") == null && find(test, "短账单夹具") != null }
            }
            stage = "reopen and preview isolation"
            click(test, "短账单夹具")
            await { find(test, "账单详情") != null }
            stableTitle(test, "账单详情", measurements)
            click(test, "关闭账单详情")
            await { find(test, "账单详情") == null && find(test, "短账单夹具") != null }
            check(details.get() == 3 && writes.get() == 0)
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            check(Store.updatePolicyJson == originalPolicy && Store.originalAppVersion == originalProtocol)
            result.putString("stream", "PASS: rapid taps coalesce, short/missing bill fallback, long content, stable detail/payment bounds, fixed header/pay action after scroll, sequential sheet handoff, channel error/retry, Back/reopen; reads=${details.get()}, payments/commands=${writes.get()}, overflowSelections=$overflowSelections; ${measurements.joinToString()}; fully intercepted offline fixtures\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; reads=${details.get()}, channels=${channels.get()}, writes=${writes.get()}; ${measurements.joinToString()}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
            clientField.set(null, originalClient)
            session.token = originalToken
            Preview.enabled = false
        }
        test.finish(code, result)
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
        check(kotlin.math.abs(bounds(pane).bottom - bounds(window).bottom) <= 1)
        return samples.last()
    }

    private fun screenshot(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("missing screenshot")
        File(test.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
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
