package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Controlled URI/VM integration. It does not claim a physical NFC antenna was exercised. */
object NfcEntryChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var stage = "account-free guard"
        var code = Activity.RESULT_CANCELED
        var started = false
        var activity: MainActivity? = null
        val original = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val calls = AtomicInteger()
        val serverType = AtomicInteger(3)
        val deviceReads = AtomicInteger()
        val directDeviceId = AtomicReference("123456789012345")
        val gate = AtomicReference<CountDownLatch?>()
        val entered = AtomicReference<CountDownLatch?>()
        val fixture = original.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET" && request.url.encodedPath == "/api/v1/ui/app/dev/home/1") {
                check(request.header("Authorization") == "offline-nfc-token")
                check(request.url.queryParameter("did") == "123456789012345")
                deviceReads.incrementAndGet()
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("""{"code":0,"data":{"device":{"id":"${directDeviceId.get()}","name":"离线直连设备","status":1,"bm":{"dtype":8},"gene":{"status":99}}}}""".toResponseBody()).build()
            }
            check(request.method == "GET" && request.url.encodedPath == "/api/v1/qr/use")
            check(request.header("Authorization") == "offline-nfc-token")
            check(request.url.queryParameter("id") == "fixture-qr")
            calls.incrementAndGet()
            gate.getAndSet(null)?.let {
                entered.getAndSet(null)?.countDown()
                check(it.await(10, TimeUnit.SECONDS))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"qr":{"type":${serverType.get()}},"dev":{"id":"fixture-device","name":"离线 NFC 设备"}}}""".toResponseBody()).build()
        }.build()
        val uri = "https://i.ilife798.com/nfc.html?q=fixture-qr"
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            started = true
            stage = "manifest accepts only supported official NFC paths"
            val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(uri))
                .setPackage(test.targetContext.packageName).addCategory(Intent.CATEGORY_DEFAULT)
            check(intent.resolveActivity(test.targetContext.packageManager)?.className == MainActivity::class.java.name)
            check(Intent(intent).setAction(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE)
                .resolveActivity(test.targetContext.packageManager)?.className == MainActivity::class.java.name)
            check(Intent(intent).setData(Uri.parse("https://attacker.test/nfc.html?q=fixture-qr"))
                .resolveActivity(test.targetContext.packageManager) == null)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            lateinit var vm: MainViewModel
            test.runOnMainSync {
                activity!!.setContent { }
                activity!!.viewModelStore.clear()
                Preview.enabled = false
                Store.saveAutoCheckIn(false)
                session.saveLogin(LoginInfo(token = "offline-nfc-token", uid = "offline-nfc-user"), "")
                clientField.set(null, fixture)
                vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
                vm.setAppResumed(true, allowAutoCheckIn = false)
            }
            stage = "URI extras cannot impersonate a verified card"
            val rawInput = kotlinx.coroutines.runBlocking {
                com.smartlife798.app.core.NfcIntentInput.read(Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(uri))
                    .putExtra("autoStart", true))
            }
            check(rawInput != null && !rawInput.allowsAutoStart())
            val fakeTag = NfcCaptureChecks.mockTag(android.nfc.NdefMessage(arrayOf(android.nfc.NdefRecord.createUri(uri))))
            val synthetic = kotlinx.coroutines.runBlocking {
                com.smartlife798.app.core.NfcIntentInput.read(Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(uri))
                    .putExtra(NfcAdapter.EXTRA_TAG, fakeTag).putExtra("autoStart", true))
            }
            check(synthetic != null && !synthetic.allowsAutoStart())
            stage = "Alipay and unknown card do not become guessed device queries"
            test.runOnMainSync { vm.acceptNfcUris(listOf("alipay://nfc/app?id=20002153&t=fixture-code")) }
            await { vm.message.value?.contains("支付宝") == true }
            check(calls.get() == 0 && vm.nfcDevice.value == null)
            test.runOnMainSync { vm.acceptNfcUris(listOf("https://attacker.test/nfc.html?q=fixture-qr")) }
            await { vm.message.value?.contains("暂不支持") == true }
            check(calls.get() == 0)
            stage = "one in-flight QR read produces server device, never a start command"
            val release = CountDownLatch(1)
            val querying = CountDownLatch(1)
            gate.set(release); entered.set(querying)
            test.runOnMainSync { vm.acceptNfcUris(listOf(uri)) }
            check(querying.await(5, TimeUnit.SECONDS))
            test.runOnMainSync { vm.acceptNfcUris(listOf(uri)) }
            SystemClock.sleep(50)
            check(calls.get() == 1 && vm.nfcDevice.value == null)
            release.countDown()
            await { vm.nfcDevice.value?.deviceId == "fixture-device" }
            check(Store.active() == null && Store.pendingStart() == null)
            test.runOnMainSync { vm.consumeNfcDevice(checkNotNull(vm.nfcDevice.value)) }
            check(vm.nfcDevice.value == null)
            stage = "non-device response does not navigate even if dev is present"
            serverType.set(1)
            test.runOnMainSync { vm.acceptNfcUris(listOf(uri)) }
            await { vm.message.value?.contains("未返回可用设备") == true }
            check(calls.get() == 2 && vm.nfcDevice.value == null)
            stage = "leaving foreground cancels pending navigation; background tap stays offline"
            val releaseLate = CountDownLatch(1)
            val queryingLate = CountDownLatch(1)
            gate.set(releaseLate); entered.set(queryingLate); serverType.set(3)
            test.runOnMainSync { vm.acceptNfcUris(listOf(uri)) }
            check(queryingLate.await(5, TimeUnit.SECONDS))
            test.runOnMainSync { vm.setAppResumed(false) }
            releaseLate.countDown()
            SystemClock.sleep(200)
            check(vm.nfcDevice.value == null)
            test.runOnMainSync { vm.acceptNfcUris(listOf(uri)) }
            SystemClock.sleep(100)
            check(calls.get() == 3)
            stage = "inline Alipay miniapp route confirms exact device; ordinary link has no automatic authority"
            val direct = "alipays://platformapi/startapp?appId=2019061465519660&page=" +
                java.net.URLEncoder.encode("devview/view?id=123456789012345", "UTF-8")
            test.runOnMainSync {
                vm.setAppResumed(true, allowAutoCheckIn = false)
                vm.acceptNfcUris(listOf(direct))
            }
            await { vm.nfcDevice.value?.deviceId == "123456789012345" }
            check(vm.nfcDevice.value?.autoStartTicket == null && deviceReads.get() == 1)
            test.runOnMainSync { vm.consumeNfcDevice(checkNotNull(vm.nfcDevice.value)) }
            directDeviceId.set("different-device")
            test.runOnMainSync { vm.acceptNfcUris(listOf(direct)) }
            await { deviceReads.get() == 2 && vm.message.value?.contains("未返回可用设备") == true }
            check(vm.nfcDevice.value == null)
            stage = "Preview has no NFC business reads or account persistence"
            test.runOnMainSync {
                vm.startPreview()
                vm.setAppResumed(true, allowAutoCheckIn = false)
                vm.acceptNfcUris(listOf(uri))
                vm.acceptNfcUris(listOf(direct))
            }
            SystemClock.sleep(100)
            check(calls.get() == 3 && vm.nfcDevice.value == null)
            check(deviceReads.get() == 2)
            check(vm.deviceEntry("fixture-device") == null)
            val packageManager = test.targetContext.packageManager
            fun defaultHandler(action: String) = Intent(action, Uri.parse(uri))
                .addCategory(Intent.CATEGORY_DEFAULT).resolveActivity(packageManager)?.packageName ?: "none"
            result.putString("dispatch", "implicitNdef=${defaultHandler(NfcAdapter.ACTION_NDEF_DISCOVERED)}," +
                "implicitView=${defaultHandler(Intent.ACTION_VIEW)}; no antenna/AAR simulation")
            result.putString("stream", "PASS: official URI/manifest, Alipay boundary, QR server resolution, duplicate guard, no start, background/Preview isolation\n")
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}\n")
        } finally {
            gate.getAndSet(null)?.countDown()
            if (started) test.runOnMainSync {
                activity?.viewModelStore?.clear()
                Preview.enabled = false
                session.clear()
                Store.clearAccount()
                clientField.set(null, original)
                activity?.finish()
            }
        }
        test.finish(code, result)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        check(condition())
    }
}
