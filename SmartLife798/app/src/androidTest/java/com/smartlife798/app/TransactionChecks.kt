package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

/** Account-owned work is tested only with terminal HTTP responses and the wallet channel; no SDK calls. */
object TransactionChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated guard"
        var activity: MainActivity? = null
        val original = Api.client
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prepay = AtomicInteger()
        val confirmed = AtomicInteger()
        val created = AtomicInteger()
        val unexpected = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val fixture = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }.addInterceptor { chain ->
                val req = chain.request()
                val data = when (req.url.encodedPath) {
                    "/api/v1/acc/wallet/owner" -> """{"aw":{"total":20.0,"ep":{"id":"offline-ep"},"owner":{"id":"offline-user"}}}"""
                    "/api/v1/trans/prepay/92" -> { prepay.incrementAndGet(); entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); "\"\"" }
                    "/api/v1/trans/confirm" -> { confirmed.incrementAndGet(); "null" }
                    "/api/v1/bill/save" -> { created.incrementAndGet(); "1234" }
                    else -> { unexpected.incrementAndGet(); "null" }
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"code":0,"data":$data}""".toResponseBody()).build()
            }.build()
            lateinit var vm: MainViewModel
            test.runOnMainSync { field.set(null, fixture); vm = ViewModelProvider(activity!!)[MainViewModel::class.java] }
            val product = Product(id = "offline-product", name = "离线商品")
            stage = "logged-out transaction guard"
            runBlocking {
                check(vm.payBill(activity!!, "offline-bill", PayChannel(92)) is PaymentFlow.Outcome.Unavailable)
                check(vm.createRechargeBill(product) is Res.Fail)
                check(vm.signAlipay(activity!!) is Res.Fail)
            }
            check(prepay.get() == 0 && created.get() == 0)
            test.runOnMainSync { session.token = "offline-transaction-token"; session.uid = "offline-user" }
            runBlocking { vm.refreshWallet() }
            stage = "cross-entry lock survives caller cancellation"
            val payer = scope.async { vm.payBill(activity!!, "offline-bill", PayChannel(92)) }
            check(entered.await(5, TimeUnit.SECONDS))
            runBlocking {
                check(vm.createRechargeBill(product) is Res.Fail)
                check(vm.signAlipay(activity!!) is Res.Fail)
                payer.cancelAndJoin()
                check(vm.payBill(activity!!, "duplicate", PayChannel(92)) is PaymentFlow.Outcome.Unavailable)
            }
            check(prepay.get() == 1 && created.get() == 0)
            release.countDown()
            val until = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (confirmed.get() != 1 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(30)
            check(confirmed.get() == 1)
            stage = "completed transaction releases lock"
            runBlocking {
                var response: Res<String> = Res.Fail(-1, "pending")
                repeat(20) { if (response is Res.Fail) { delay(30); response = vm.createRechargeBill(product) } }
                check((response as? Res.Ok)?.data == "1234")
            }
            check(created.get() == 1 && unexpected.get() == 0)
            result.putString("stream", "PASS: logged-out writes rejected; payment/recharge/signing share entry lock; caller cancellation keeps in-flight lock; one prepay/confirm; completion permits one recharge; terminal fixtures, no SDK\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; prepay=${prepay.get()},confirm=${confirmed.get()},created=${created.get()},unexpected=${unexpected.get()}\n")
        } finally {
            release.countDown(); scope.cancel()
            test.runOnMainSync { activity?.let { it.viewModelStore.clear(); it.finish() }; session.clear() }
            field.set(null, original)
        }
        test.finish(code, result)
    }
}
