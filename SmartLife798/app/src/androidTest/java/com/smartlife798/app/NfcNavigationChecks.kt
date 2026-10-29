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
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "empty account guard"
        var started = false
        val application = test.targetContext.applicationContext as Application
        val screen = AtomicReference<MainActivity?>()
        val qrReads = AtomicInteger()
        val forbidden = AtomicInteger()
        val unexpectedPath = AtomicReference<String>()
        val originalClient = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val gson = Gson()
        val sample = Preview.devices.first()
        fun device(warm: Boolean) = sample.copy(id = if (warm) "fixture-warm-device" else "fixture-cold-device",
            name = if (warm) "NFC热启动离线设备" else "NFC冷启动离线设备",
            gene = sample.gene?.copy(status = 99))
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
                "/api/v1/qr/use" -> {
                    qrReads.incrementAndGet()
                    val qr = request.url.queryParameter("id")
                    check(qr in listOf("fixture-cold-qr", "fixture-warm-qr"))
                    """{"qr":{"type":3},"dev":${gson.toJson(device(qr == "fixture-warm-qr"))}}"""
                }
                "/api/v1/ui/app/dev/status" -> gson.toJson(mapOf("device" to mapOf(
                    "id" to request.url.queryParameter("did"), "status" to 1, "gene" to mapOf("status" to 99))))
                "/api/v1/ui/app/dev/home/1" -> gson.toJson(Preview.detail(sample.id).copy(
                    device = device(request.url.queryParameter("did") == "fixture-warm-device")))
                else -> { forbidden.incrementAndGet(); unexpectedPath.set(request.url.encodedPath); "null" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":$data}""".toResponseBody()).build()
        }.build()
        fun visible(label: String): Boolean {
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser && node.text?.toString() == label) return true
                return (0 until node.childCount).any { contains(node.getChild(it)) }
            }
            return contains(test.uiAutomation.rootInActiveWindow)
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
            result.putString("stream", "PASS: cold NDEF and warm VIEW Activity/Compose detail navigation; consumed intent recreation makes zero extra QR reads; no commands/payments; terminal fixtures only\n")
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}; qr=${qrReads.get()},forbidden=${forbidden.get()},path=${unexpectedPath.get()}\n")
        } finally {
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
