package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.RecentDevice
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Account-owned read races against a terminal transport; no SDK or real business operations. */
object AccountReadChecks {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated account guard"
        var started = false
        var activity: MainActivity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val prefsField = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val prefs = test.targetContext.getSharedPreferences("smartlife798", Context.MODE_PRIVATE)
        val originalAuto = prefs.all["autoCheckIn"] as? Boolean
        val original = Api.client
        val reads = AtomicInteger()
        val unexpected = AtomicInteger()
        val gate = AtomicReference<Gate?>()
        var heldGate: Gate? = null
        val body = AtomicReference("""{"code":0,"data":[{"id":"old","ctime":1,"status":1},{"id":"paid","ctime":3,"status":3},{"id":"new","ctime":2,"status":4}]}""")
        val fixture = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }
            .addInterceptor { chain ->
                val request = chain.request()
                val json = when (request.url.encodedPath) {
                    "/api/v1/bill/lst-owner" -> {
                        check(request.method == "GET" && request.header("Authorization")?.startsWith("offline-account-") == true)
                        check(request.url.queryParameter("page") == "0" && request.url.queryParameter("size") == "50")
                        reads.incrementAndGet()
                        gate.getAndSet(null)?.let {
                            it.entered.countDown()
                            check(it.release.await(10, TimeUnit.SECONDS))
                        }
                        body.get()
                    }
                    "/api/v1/acc/logout" -> """{"code":0}"""
                    else -> {
                        unexpected.incrementAndGet()
                        """{"code":-1}"""
                    }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("offline").body(json.toResponseBody()).build()
            }.build()
        fun lookup(vm: MainViewModel) = MainViewModel::class.java.getDeclaredField("unpaidLookupJob")
            .apply { isAccessible = true }.get(vm) as Job
        fun stall(): Gate = Gate().also { gate.set(it); heldGate = it }
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null &&
                Store.active() == null && Store.pendingStart() == null)
            started = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val screen = activity
            lateinit var vm: MainViewModel
            test.runOnMainSync {
                screen.setContent { }
                screen.viewModelStore.clear()
                Store.saveAutoCheckIn(false)
                clientField.set(null, fixture)
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.requestPaymentForUnpaid("未登录")
            }
            stage = "logged-out lookup sends no request"
            SystemClock.sleep(150)
            check(reads.get() == 0 && vm.payRequest.value == null && vm.message.value == null)
            test.runOnMainSync {
                session.saveLogin(LoginInfo(token = "offline-account-first", uid = "offline-account-user"), "")
            }
            stage = "repeated payment lookup shares the badge read"
            val first = stall()
            lateinit var badge: Job
            test.runOnMainSync {
                repeat(6) { vm.requestPaymentForUnpaid("待付款") }
                badge = scope.launch { vm.refreshOrderBadge() }
            }
            check(first.entered.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(150)
            check(reads.get() == 1)
            first.release.countDown()
            runBlocking { lookup(vm).join(); badge.join() }
            check(vm.payRequest.value?.bill?.id == "new" && vm.unpaidCount.value == 2)
            test.runOnMainSync { vm.requestPaymentForUnpaid("重复提示") }
            check(reads.get() == 1)
            test.runOnMainSync { vm.consumePayRequest() }

            stage = "replaced token rejects late payment target and message"
            val replaced = stall()
            test.runOnMainSync { vm.requestPaymentForUnpaid("旧账号") }
            check(replaced.entered.await(5, TimeUnit.SECONDS))
            test.runOnMainSync {
                session.saveLogin(LoginInfo(token = "offline-account-next", uid = "offline-account-next-user"), "")
            }
            replaced.release.countDown()
            runBlocking { lookup(vm).join() }
            check(vm.payRequest.value == null && vm.message.value == null)

            stage = "logout cancels in-flight lookup before another login"
            val loggedOut = stall()
            test.runOnMainSync { vm.requestPaymentForUnpaid("登出前") }
            check(loggedOut.entered.await(5, TimeUnit.SECONDS))
            val oldLookup = lookup(vm)
            test.runOnMainSync {
                vm.logout()
                session.saveLogin(LoginInfo(token = "offline-account-third", uid = "offline-account-third-user"), "")
            }
            loggedOut.release.countDown()
            runBlocking { oldLookup.join() }
            check(oldLookup.isCancelled && vm.payRequest.value == null && vm.message.value == null)

            stage = "failed lookup offers retry without claiming no unpaid bills"
            body.set("""{"code":-1,"msg":"offline failure"}""")
            test.runOnMainSync { vm.requestPaymentForUnpaid("待付款") }
            runBlocking { lookup(vm).join() }
            check(vm.message.value == "账单查询失败，请到订单页刷新后重试")
            body.set("""{"code":0,"data":[]}""")
            test.runOnMainSync { vm.clearMessage(); vm.requestPaymentForUnpaid("待付款") }
            runBlocking { lookup(vm).join() }
            check(vm.message.value?.contains("未查询到待支付账单") == true)

            stage = "history waits for cache restore and preserves newer scan ordering"
            val cache = stall()
            gate.set(null)
            val beforeHistory = reads.get()
            Store.saveRecents(listOf(RecentDevice("scan", "刚扫码", 7, 300L, 0, true),
                RecentDevice("used", "本机名称", 7, 200L, 5)))
            prefsField.set(null, object : SharedPreferences by prefs {
                override fun getString(key: String?, defValue: String?): String? {
                    val value = prefs.getString(key, defValue)
                    if (key == "recents") {
                        cache.entered.countDown()
                        check(cache.release.await(10, TimeUnit.SECONDS))
                    }
                    return value
                }
            })
            body.set("""{"code":0,"data":[{"ctime":100,"dev":{"id":"used","name":"服务端名称","bm":{"dtype":7}}}]}""")
            lateinit var history: Job
            test.runOnMainSync {
                screen.viewModelStore.clear()
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                history = scope.launch { vm.syncCloudHistory() }
            }
            check(cache.entered.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(150)
            check(reads.get() == beforeHistory)
            cache.release.countDown()
            runBlocking { history.join() }
            check(vm.recents.value.map { it.id } == listOf("scan", "used"))
            check(vm.recents.value[1].let { it.name == "服务端名称" && it.lastUsedAt == 200L && it.uses == 5 })
            check(unexpected.get() == 0)
            result.putString("stream", "PASS: logged-out zero reads; six taps and badge share one read; newest unpaid selected; token replacement/logout reject late target; failure retry; history waits for cache and preserves scans/counts; terminal transport only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; reads=${reads.get()},unexpected=${unexpected.get()}; source=${error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }}\n")
        } finally {
            heldGate?.release?.countDown()
            scope.cancel()
            prefsField.set(null, prefs)
            test.runOnMainSync {
                activity?.let { it.setContent { }; it.viewModelStore.clear(); it.finish() }
                if (started) session.clear()
            }
            if (started) {
                val editor = prefs.edit()
                if (originalAuto == null) editor.remove("autoCheckIn") else editor.putBoolean("autoCheckIn", originalAuto)
                check(editor.commit())
            }
            clientField.set(null, original)
            fixture.dispatcher.executorService.shutdown()
        }
        test.finish(code, result)
    }
}
