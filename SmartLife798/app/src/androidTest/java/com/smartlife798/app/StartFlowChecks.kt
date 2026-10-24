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
import com.smartlife798.app.ui.HomeScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Account-free, fully intercepted start/recovery checks; never reaches the vendor. */
object StartFlowChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var fixtureStarted = false
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val calls = AtomicInteger()
        val state = AtomicInteger(10)
        // 0 matching, 1 network failure, 2 foreign, 3 incomplete, 4 blocked read.
        val responseMode = AtomicInteger(4)
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val device = Device("start-fixture", "首页运行卡片测试饮水机", status = 1,
            bm = DeviceModel(dtype = DeviceType.WATER_PURIFIER), gene = Gene(status = 99))
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null &&
                Store.pendingStart() == null && session.token.isNullOrBlank())
            fixtureStarted = true
            val fixture = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when {
                    path.endsWith("/dev/start") -> {
                        calls.incrementAndGet()
                        """{"code":0}"""
                    }
                    path.endsWith("/dev/status") -> {
                        if (responseMode.get() == 5) {
                            staleEntered.countDown()
                            check(releaseStale.await(20, TimeUnit.SECONDS))
                        }
                        if (responseMode.get() == 4) {
                            readEntered.countDown()
                            check(releaseRead.await(20, TimeUnit.SECONDS))
                        }
                        when (responseMode.get()) {
                            1 -> """{"code":-1,"msg":"offline fixture"}"""
                            2 -> """{"code":0,"data":{"device":{"id":"another-device","status":1,"gene":{"status":99}}}}"""
                            3 -> """{"code":0,"data":{"device":{"id":"start-fixture","status":1}}}"""
                            else -> """{"code":0,"data":{"device":{"id":"start-fixture","status":1,"gene":{"status":${state.get()}}}}}"""
                        }
                    }
                    path.endsWith("/dev/home/1") -> """{"code":0,"data":{"device":{"id":"start-fixture","name":"首页运行卡片测试饮水机","status":1,"bm":{"dtype":8},"gene":{"status":${state.get()}}}}}"""
                    path.endsWith("/master") -> """{"code":0,"data":{"favos":[]}}"""
                    else -> """{"code":0,"data":[]}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            clientField.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            val screen = activity
            lateinit var vm: MainViewModel
            fun home() = test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme {
                        HomeScreen(vm, onScan = {}, onOpenDevice = { _, _ -> },
                            onOpenDevices = {}, onOpenOrders = {})
                    }
                }
            }
            test.runOnMainSync {
                Preview.enabled = false
                session.token = "offline-start-fixture"
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }
            stage = "pending home card and cross-entry duplicate guard"
            home()
            val caller = callers.launch { vm.startDevice(device, 21, "") }
            check(readEntered.await(12, TimeUnit.SECONDS))
            await { text(test).contains("启动待确认") }
            check(Store.active() == null && Store.pendingStart()?.deviceId == device.id)
            screenshot(test, "home-pending.png")
            runBlocking {
                check(vm.startDevice(device, 21, "") is Res.Fail)
                check(vm.stopDevice(device) is Res.Fail)
            }
            check(calls.get() == 1)
            stage = "cancelled page caller still confirms running home card"
            caller.cancel()
            responseMode.set(0)
            releaseRead.countDown()
            await { vm.active.value?.deviceId == device.id }
            await { text(test).contains("运行中") }
            check(Store.pendingStart() == null && Store.active()?.deviceId == device.id)
            screenshot(test, "home-running.png")
            stage = "cached detail and stale standby input cannot erase or repeat a start"
            state.set(99)
            runBlocking {
                vm.loadDevice(device.id)
                check(vm.active.value != null)
                check(vm.startDevice(device, 21, "") is Res.Fail)
            }
            check(calls.get() == 1)
            state.set(10)
            stage = "foreign and incomplete reads preserve active state"
            runBlocking {
                responseMode.set(2)
                vm.syncActiveStatus()
                check(vm.active.value != null)
                responseMode.set(3)
                vm.syncActiveStatus()
                check(vm.active.value != null)
            }
            stage = "detail polling clears ended session and home card"
            responseMode.set(0)
            state.set(99)
            runBlocking { vm.pollDevice(device.id, device.copy(gene = Gene(status = 10))) }
            await { vm.active.value == null && !text(test).contains("查看设备") }
            stage = "failed confirmation retains pending and prevents resend"
            responseMode.set(1)
            runBlocking {
                check(vm.startDevice(device, 21, "") is Res.Fail)
                check(vm.active.value == null && Store.pendingStart()?.deviceId == device.id)
                check(vm.startDevice(device, 21, "") is Res.Fail)
            }
            check(calls.get() == 2)
            stage = "recreated viewmodel restores pending and promotes late running state"
            test.runOnMainSync {
                screen.viewModelStore.clear()
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }
            check(vm.pendingStart.value?.deviceId == device.id)
            responseMode.set(0)
            state.set(10)
            runBlocking { vm.syncActiveStatus() }
            home()
            await { text(test).contains("运行中") }
            stage = "ended record and unrelated running device"
            test.runOnMainSync { vm.setAppResumed(false) }
            state.set(99)
            runBlocking { vm.syncActiveStatus() }
            // A matching running device without a local start is never adopted.
            state.set(10)
            runBlocking { vm.loadDevice(device.id) }
            check(vm.active.value == null)
            stage = "logout rejects a late status and releases command guard"
            runBlocking { check(vm.startDevice(device, 21, "") is Res.Ok) }
            responseMode.set(5)
            val stale = callers.async { vm.pollDevice(device.id, device.copy(gene = Gene(status = 10))) }
            check(staleEntered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync { vm.logout() }
            await { session.token == null && vm.active.value == null }
            releaseStale.countDown()
            runBlocking {
                try {
                    stale.await()
                    error("Late account response was accepted")
                } catch (_: CancellationException) { }
                check(vm.startDevice(device, 21, "") is Res.Fail)
            }
            check(Store.active() == null && Store.pendingStart() == null)
            stage = "preview does not write or send"
            val activeBefore = Store.activeUsageJson
            val pendingBefore = Store.pendingStart()
            test.runOnMainSync { vm.startPreview() }
            runBlocking {
                check(vm.startDevice(device, 21, "") is Res.Ok)
                check(vm.stopDevice(device) is Res.Ok)
            }
            check(Store.activeUsageJson == activeBefore && Store.pendingStart() == pendingBefore)
            check(calls.get() == 3)
            result.putString("stream", "PASS: pending/running home cards, cross-entry exclusion, page cancellation, foreign/incomplete reads, detail end reconciliation, unknown start recovery after recreation, no foreign adoption, logout drops late response, preview isolation\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            callers.cancel()
            releaseRead.countDown()
            releaseStale.countDown()
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = false
            if (fixtureStarted) Store.clearAccount()
            session.token = originalToken
            clientField.set(null, originalClient)
        }
        test.finish(code, result)
    }

    private fun screenshot(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot()
        java.io.File(test.targetContext.cacheDir, name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun text(test: Instrumentation): String {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): String = if (node == null) "" else
            node.text?.toString().orEmpty() + " " + (0 until node.childCount).joinToString(" ") { walk(node.getChild(it)) }
        return walk(test.uiAutomation.rootInActiveWindow)
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Condition timed out")
    }
}
