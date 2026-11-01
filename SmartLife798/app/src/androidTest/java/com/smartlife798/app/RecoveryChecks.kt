package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.ui.OrdersScreen
import com.smartlife798.app.ui.WalletScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

/** Actual production screens and terminal responses, with a failed second page and failed product read. */
object RecoveryChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var activity: MainActivity? = null
        var stage = "isolated account guard"
        var code = Activity.RESULT_CANCELED
        val original = Api.client
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val pages = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val second = AtomicInteger()
        val products = AtomicInteger()
        val writes = AtomicInteger()
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val fixture = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }
                .addInterceptor { chain ->
                    val req = chain.request()
                    val data = when (req.url.encodedPath) {
                        "/api/v1/bill/lst-owner" -> {
                            val page = req.url.queryParameter("page")!!.toInt()
                            pages.add(page)
                            if (page == 1 && second.incrementAndGet() == 1) null
                            else if (page == 0) (1..20).joinToString(",", "[", "]") {
                                """{"id":"paid-$it","status":3,"prds":[{"name":"已完成记录$it"}]}"""
                            } else """[{"id":"older-unpaid","status":1,"payment":0.6,"prds":[{"name":"较早待付款记录"}]}]"""
                        }
                        "/api/v1/acc/wallet/owner" -> """{"aw":{"total":36.5,"ep":{"id":"offline-ep"}}}"""
                        "/api/v1/prd/lst" -> if (products.incrementAndGet() == 1) null
                            else """[{"id":"offline-product","name":"恢复后的充值商品","curPrice":2.0}]"""
                        else -> { writes.incrementAndGet(); null }
                    }
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body((if (data == null) """{"code":-1,"msg":"离线读取失败"}""" else """{"code":0,"data":$data}""").toResponseBody()).build()
                }.build()
            lateinit var vm: com.smartlife798.app.vm.MainViewModel
            test.runOnMainSync {
                field.set(null, fixture)
                session.token = "offline-recovery-token"
                session.uid = "offline-recovery-user"
                vm = ViewModelProvider(activity!!)[com.smartlife798.app.vm.MainViewModel::class.java]
                activity!!.setContent { SmartLifeTheme { OrdersScreen(vm) { _, _ -> } } }
            }
            stage = "empty local filter still permits paging"
            await(test) { click(test, "待付款 0") }
            await(test) { click(test, "加载更多") }
            await(test) { second.get() == 1 }
            stage = "failed page retries the same page"
            SystemClock.sleep(300)
            await(test) { click(test, "加载更多") }
            await(test) { text(test).contains("较早待付款记录") }
            check(pages.toList() == listOf(0, 1, 1))
            stage = "wallet product retry reads products again"
            test.runOnMainSync { activity!!.setContent { SmartLifeTheme { WalletScreen(vm) {} } } }
            await(test) { text(test).contains("离线读取失败") }
            check(products.get() == 1)
            await(test) { click(test, "重试") }
            await(test) { text(test).contains("恢复后的充值商品") }
            check(products.get() == 2 && writes.get() == 0)
            result.putString("stream", "PASS: empty filter retains paging; failed page retries [0,1,1]; wallet product retry recovers; zero side effects\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; pages=$pages,products=${products.get()},writes=${writes.get()}\n")
        } finally {
            test.runOnMainSync { activity?.let { it.setContent {}; it.viewModelStore.clear(); it.finish() }; session.clear() }
            field.set(null, original)
        }
        test.finish(code, result)
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun text(test: Instrumentation) = nodes(test.uiAutomation.rootInActiveWindow).joinToString(" ") { it.text?.toString().orEmpty() }
    private fun click(test: Instrumentation, label: String): Boolean {
        var node = nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
        while (node != null && !node.isClickable) node = node.parent
        return node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun await(test: Instrumentation, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < until) {
            if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
            if (predicate()) return
            SystemClock.sleep(80)
        }
        error("Expected UI state not reached")
    }
}
