package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.DeviceDetailScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Offline fixture transport lives only in the test APK. Every request is intercepted. */
object EndFlowChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var replaced = false
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val originalUid = session.uid
        val endCalls = AtomicInteger()
        val statusReads = AtomicInteger()
        val totalStatusReads = AtomicInteger()
        val reject = AtomicBoolean()
        val holdFull = AtomicBoolean()
        val fullEntered = CountDownLatch(1)
        val releaseFull = CountDownLatch(1)
        val device = """{"id":"end-fixture","name":"结束状态演示饮水机","status":1,"bm":{"dtype":8},"gene":{"status":10}}"""
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null && session.token.isNullOrBlank())
            val fixture = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when {
                    path.endsWith("/dev/end") -> {
                        endCalls.incrementAndGet()
                        if (reject.get()) """{"code":-2,"msg":"请在设备上按结算"}"""
                        else """{"code":0}"""
                    }
                    path.endsWith("/dev/status") -> {
                        totalStatusReads.incrementAndGet()
                        val status = if (!reject.get() && endCalls.get() > 0 && statusReads.incrementAndGet() >= 3) 99 else 10
                        """{"code":0,"data":{"device":{"id":"end-fixture","status":1,"gene":{"status":$status}}}}"""
                    }
                    path.endsWith("/dev/home/1") -> {
                        if (holdFull.getAndSet(false)) {
                            fullEntered.countDown()
                            check(releaseFull.await(20, TimeUnit.SECONDS))
                        }
                        """{"code":0,"data":{"device":$device,"user":{"id":"offline-end-user"},"payItems":[{"type":21}]}}"""
                    }
                    else -> """{"code":0,"data":[]}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            clientField.set(null, fixture)
            replaced = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            test.waitForIdleSync()
            val screen = activity
            lateinit var vm: MainViewModel
            fun render() {
                test.runOnMainSync {
                    Preview.enabled = false
                    session.token = "offline-end-fixture"
                    session.uid = "offline-end-user"
                    vm = ViewModelProvider(screen)[MainViewModel::class.java]
                    screen.setContent {
                        SmartLifeTheme {
                            DeviceDetailScreen(vm, "end-fixture", onBack = {}, onOpenOrders = {})
                        }
                    }
                }
            }
            stage = "render running device"
            render()
            await(test) { enabled(test, "结束使用并结算") }
            stage = "poll starts after asynchronous detail load"
            await(test) { totalStatusReads.get() > 0 }
            // A full read started before the end must never overwrite its confirmed result.
            holdFull.set(true)
            test.runOnMainSync { vm.bumpRefresh() }
            check(fullEntered.await(5, TimeUnit.SECONDS))
            stage = "delayed standby and duplicate tap"
            click(test, "结束使用并结算")
            click(test, "正在结束使用并结算…", required = false)
            await(test) { endCalls.get() == 1 }
            check(!enabled(test, "结束使用并结算"))
            val until = SystemClock.elapsedRealtime() + 12_000
            while (!enabled(test, "立即使用") && SystemClock.elapsedRealtime() < until) {
                check(!enabled(test, "结束使用并结算"))
                Thread.sleep(80)
            }
            check(enabled(test, "立即使用"))
            check(endCalls.get() == 1)
            stage = "late full response cannot restore stop"
            releaseFull.countDown()
            Thread.sleep(2_500)
            check(enabled(test, "立即使用"))
            check(!enabled(test, "结束使用并结算"))

            stage = "server rejection restores explicit retry"
            holdFull.set(false)
            reject.set(true)
            test.runOnMainSync { vm.bumpRefresh() }
            await(test) { enabled(test, "结束使用并结算") }
            click(test, "结束使用并结算")
            await(test) { text(test).contains("请在设备上按「结算」键结束") }
            check(enabled(test, "结束使用并结算"))
            check(endCalls.get() == 2)

            stage = "preview isolation and standby refresh"
            val persisted = Store.active()
            test.runOnMainSync { vm.startPreview() }
            val before = endCalls.get()
            kotlinx.coroutines.runBlocking {
                val sample = Preview.devices.first()
                check(vm.stopDevice(sample) is com.smartlife798.app.core.Res.Ok)
                val detail = vm.loadDevice(sample.id) as com.smartlife798.app.core.Res.Ok
                check(detail.data?.device?.geneStatus == 99)
                check(vm.pollDevice(sample.id, sample) is com.smartlife798.app.core.Res.Ok)
            }
            check(Store.active() == persisted && endCalls.get() == before)
            result.putString("stream", "PASS: delayed standby without stop-button rebound, duplicate tap, late full response, server rejection, preview no-request/no-account-write\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            releaseFull.countDown()
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = false
            session.token = originalToken
            session.uid = originalUid
            if (replaced) clientField.set(null, originalClient)
        }
        test.finish(code, result)
    }

    private fun nodes(test: Instrumentation): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) +
                (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        return walk(test.uiAutomation.rootInActiveWindow)
    }
    private fun text(test: Instrumentation) = nodes(test).joinToString(" ") { it.text?.toString().orEmpty() }
    private fun enabled(test: Instrumentation, label: String): Boolean {
        var node = nodes(test).firstOrNull { it.text?.toString() == label } ?: return false
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.isEnabled && node.isClickable
    }
    private fun click(test: Instrumentation, label: String, required: Boolean = true) {
        var node = nodes(test).firstOrNull { it.text?.toString() == label }
        while (node != null && !node.isClickable) node = node.parent
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (required) check(clicked)
    }
    private fun await(test: Instrumentation, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("UI condition timed out")
    }
}
