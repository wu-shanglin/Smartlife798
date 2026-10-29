package com.smartlife798.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Exercise the real Activity and Compose graph with terminal offline transports, no NFC antenna. */
object NfcNavigationChecks {
    fun run(test: Instrumentation, automatic: Boolean = false, rejectStart: Boolean = false) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "empty account guard"
        var started = false
        val application = test.targetContext.applicationContext as Application
        val screen = AtomicReference<MainActivity?>()
        val qrReads = AtomicInteger()
        val startCalls = AtomicInteger()
        val startResult = AtomicInteger(if (rejectStart) -1 else 0)
        val deviceStates = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val forbidden = AtomicInteger()
        val unexpectedPath = AtomicReference<String>()
        val detailGate = AtomicReference<java.util.concurrent.CountDownLatch?>()
        val detailEntered = AtomicReference<java.util.concurrent.CountDownLatch?>()
        var activeGate: java.util.concurrent.CountDownLatch? = null
        val originalClient = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val gson = Gson()
        val sample = Preview.devices.first()
        fun device(warm: Boolean) = sample.copy(id = if (warm) "fixture-warm-device" else "fixture-cold-device",
            name = if (warm) "NFC热启动离线设备" else "NFC冷启动离线设备",
            gene = sample.gene?.copy(status = deviceStates[if (warm) "fixture-warm-device" else "fixture-cold-device"] ?: 99))
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, saved: Bundle?) {
                if (activity !is MainActivity) return
                screen.set(activity)
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                // Keep public update/version discovery out of this offline test.
                for (name in listOf("lastUpdateCheck", "lastProtocolCheck"))
                    MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                        .setLong(vm, SystemClock.elapsedRealtime())
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        val fixture = originalClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") {
                forbidden.incrementAndGet()
                unexpectedPath.set(request.url.encodedPath)
                throw java.io.IOException("offline command rejected")
            }
            check(request.header("Authorization") == "offline-nfc-navigation")
            val data = when (request.url.encodedPath) {
                "/api/v1/ui/app/master" -> """{"account":{"id":"offline-nfc-navigation-user","name":"离线用户","pn":""},"favos":[]}"""
                "/api/v1/acc/wallet/owner" -> """{"aw":{"total":0.0,"owner":{"id":"offline-nfc-navigation-user"}}}"""
                "/api/v1/bill/lst-owner" -> "[]"
                "/api/v1/acc/score/mission-lst" -> """{"accScoreRsp":{"validScore":"0","daily":{"week":1}}}"""
                "/api/v1/qr/use" -> {
                    qrReads.incrementAndGet()
                    val qr = request.url.queryParameter("id")
                    check(qr in listOf("fixture-cold-qr", "fixture-warm-qr"))
                    """{"qr":{"type":3},"dev":${gson.toJson(device(qr == "fixture-warm-qr"))}}"""
                }
                "/api/v1/dev/start" -> {
                    check(automatic)
                    startCalls.incrementAndGet()
                    if (startResult.get() >= 0) deviceStates[checkNotNull(request.url.queryParameter("did"))] = 10
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture").body("""{"code":${startResult.get()}}""".toResponseBody()).build()
                }
                "/api/v1/ui/app/dev/status" -> gson.toJson(mapOf("device" to mapOf(
                    "id" to request.url.queryParameter("did"), "status" to 1, "gene" to mapOf("status" to (deviceStates[request.url.queryParameter("did")] ?: 99)))))
                "/api/v1/ui/app/dev/home/1" -> {
                    detailGate.getAndSet(null)?.let {
                        detailEntered.getAndSet(null)?.countDown()
                        check(it.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    gson.toJson(Preview.detail(sample.id).copy(
                        user = com.smartlife798.app.core.User(id = "offline-nfc-navigation-user"),
                        device = device(request.url.queryParameter("did") == "fixture-warm-device")))
                }
                else -> { forbidden.incrementAndGet(); unexpectedPath.set(request.url.encodedPath); "null" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":$data}""".toResponseBody()).build()
        }.build()
        fun find(label: String, visibleOnly: Boolean = true): android.view.accessibility.AccessibilityNodeInfo? {
            fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if ((!visibleOnly || node.isVisibleToUser) && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
                for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
                return null
            }
            return search(test.uiAutomation.rootInActiveWindow)
        }
        fun visible(label: String) = find(label) != null
        fun click(label: String) {
            var node = checkNotNull(find(label))
            while (!node.isClickable && node.parent != null) node = node.parent
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            started = true
            test.runOnMainSync {
                Preview.enabled = false
                Store.saveAutoCheckIn(false)
                session.saveLogin(LoginInfo(token = "offline-nfc-navigation", uid = "offline-nfc-navigation-user"), "")
                clientField.set(null, fixture)
                application.registerActivityLifecycleCallbacks(callbacks)
            }
            stage = "cold NDEF intent resolves and enters real detail without starting"
            val uri = "https://i.ilife798.com/nfc.html?q=fixture-cold-qr"
            test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED).setData(Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { visible("NFC冷启动离线设备") }
            SystemClock.sleep(350)
            check(qrReads.get() == 1 && forbidden.get() == 0)
            check(screen.get()?.intent?.data == null && Store.active() == null && Store.pendingStart() == null)
            stage = "Activity recreation does not replay the consumed card"
            val old = screen.get()!!
            test.runOnMainSync { old.recreate() }
            await { screen.get() !== old && visible("NFC冷启动离线设备") }
            SystemClock.sleep(300)
            check(qrReads.get() == 1)
            stage = "warm Android16 VIEW path reaches a second detail without autoStart"
            test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://i.hnkzy.com/nfc.html?q=fixture-warm-qr"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await { visible("NFC热启动离线设备") }
            SystemClock.sleep(350)
            check(qrReads.get() == 2 && forbidden.get() == 0 && Store.active() == null && Store.pendingStart() == null)
            if (automatic) {
                stage = "background NDEF delivery returns to detail; unverified URI cannot start"
                test.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await { screen.get()?.hasWindowFocus() == false }
                SystemClock.sleep(400) // Complete HOME stop before the next independent NFC launch.
                test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
                    .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED).setData(Uri.parse(uri))
                    .putExtra("autoStart", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                await { visible("NFC冷启动离线设备") }
                SystemClock.sleep(300)
                check(startCalls.get() == 0)
                stage = "simulated reader callback starts server-resolved device exactly once"
                lateinit var vm: MainViewModel
                val blockedDetail = java.util.concurrent.CountDownLatch(1)
                val detailStarted = java.util.concurrent.CountDownLatch(1)
                activeGate = blockedDetail
                detailGate.set(blockedDetail)
                detailEntered.set(detailStarted)
                test.runOnMainSync {
                    vm = ViewModelProvider(screen.get()!!)[MainViewModel::class.java]
                    vm.acceptNfcUris(listOf(uri), autoStart = true)
                }
                check(detailStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                SystemClock.sleep(150)
                check(startCalls.get() == 0) // A previous page snapshot cannot authorize the new tap.
                blockedDetail.countDown()
                await { startCalls.get() == 1 && (rejectStart || vm.active.value?.deviceId == "fixture-cold-device") }
                if (rejectStart) SystemClock.sleep(300)
                check(forbidden.get() == 0)
                stage = "recreation and repeated tap on running device never start or stop it again"
                val before = screen.get()!!
                test.runOnMainSync { before.recreate() }
                await { screen.get() !== before && visible("NFC冷启动离线设备") }
                test.runOnMainSync {
                    vm = ViewModelProvider(screen.get()!!)[MainViewModel::class.java]
                    if (rejectStart) vm.bumpRefresh() else vm.acceptNfcUris(listOf(uri), autoStart = true)
                }
                SystemClock.sleep(800)
                check(startCalls.get() == 1 && forbidden.get() == 0)
                stage = "diagnostic NFC child cannot resolve or start from an incoming intent"
                test.runOnMainSync { vm.setDeveloperToolsEnabled(true) }
                repeat(4) {
                    if (!visible("我的")) {
                        test.runOnMainSync { screen.get()!!.onBackPressedDispatcher.onBackPressed() }
                        SystemClock.sleep(900) // Let the shared return settle before another Back.
                    }
                }
                stage = "diagnostic test returns to bottom navigation"
                await { visible("我的") }
                click("我的")
                test.waitForIdleSync()
                stage = "diagnostic test scrolls to developer tools"
                repeat(12) {
                    if (!visible("开发者工具")) {
                        val bounds = android.graphics.Rect()
                        checkNotNull(test.uiAutomation.rootInActiveWindow).getBoundsInScreen(bounds)
                        val x = bounds.centerX()
                        val from = bounds.top + (bounds.height() * 0.75f).toInt()
                        val to = bounds.top + (bounds.height() * 0.47f).toInt()
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                            "input swipe $x $from $x $to 350")).use { it.readBytes() }
                        SystemClock.sleep(150)
                    }
                }
                await { visible("开发者工具") }
                click("开发者工具")
                stage = "diagnostic test opens NFC child"
                await { visible("NFC 采集") }
                click("NFC 采集")
                await { visible("开始采集") }
                val qrBeforeTool = qrReads.get()
                test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
                    .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED).setData(Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                SystemClock.sleep(500)
                check(qrReads.get() == qrBeforeTool && startCalls.get() == 1 && forbidden.get() == 0)
                result.putString("stream", if (rejectStart)
                    "PASS: background NDEF opens detail; one rejected simulated NFC start; recreation/refresh adds zero retries; terminal fixtures only\n"
                    else "PASS: background NDEF resumes real detail; forged URI extra cannot auto-start; simulated verified reader starts once; recreation/running retap adds zero commands; terminal fixtures only\n")
            } else {
                result.putString("stream", "PASS: cold NDEF and warm VIEW Activity/Compose detail navigation; consumed intent recreation makes zero extra QR reads; no commands/payments; terminal fixtures only\n")
            }
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}; qr=${qrReads.get()},forbidden=${forbidden.get()},path=${unexpectedPath.get()},start=${startCalls.get()},line=${e.stackTrace.firstOrNull { it.className.contains("NfcNavigationChecks") }?.lineNumber}\n")
        } finally {
            activeGate?.countDown()
            if (started) test.runOnMainSync {
                screen.get()?.viewModelStore?.clear()
                application.unregisterActivityLifecycleCallbacks(callbacks)
                Preview.enabled = false
                session.clear()
                Store.clearAccount()
                clientField.set(null, originalClient)
                screen.get()?.finish()
            }
        }
        test.finish(code, result)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        check(condition())
    }
}
