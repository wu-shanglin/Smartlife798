package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Terminal HTTP fixtures, no socket: compare the same artificial read latency across APKs. */
object ReadPerformanceChecks {
    fun run(test: Instrumentation, verify: Boolean) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "empty account guard"
        var activity: MainActivity? = null
        var started = false
        val original = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val counts = listOf(AtomicInteger(), AtomicInteger(), AtomicInteger())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val unexpected = AtomicInteger()
        val fixture = original.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.header("Authorization") == "offline-reads-token")
            val index = when (request.url.encodedPath) {
                "/api/v1/ui/app/master" -> 0
                "/api/v1/acc/wallet/owner" -> 1
                "/api/v1/bill/lst-owner" -> 2
                else -> { unexpected.incrementAndGet(); error("unprovided offline route") }
            }
            counts[index].incrementAndGet()
            val inFlight = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, inFlight) }
            try {
                Thread.sleep(150)
            } finally {
                active.decrementAndGet()
            }
            val body = when (index) {
                0 -> """{"code":0,"data":{"account":{"id":"offline-reads-user","name":"离线性能用户","pn":""},"favos":[]}}"""
                1 -> """{"code":0,"data":{"aw":{"total":12.0,"owner":{"id":"offline-reads-user"}}}}"""
                else -> """{"code":0,"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            started = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            lateinit var vm: MainViewModel
            test.runOnMainSync {
                activity!!.setContent { }
                activity!!.viewModelStore.clear()
                Preview.enabled = false
                Store.saveAutoCheckIn(false)
                clientField.set(null, fixture)
                session.saveLogin(LoginInfo(token = "offline-reads-token", uid = "offline-reads-user"), "")
                vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
            }
            stage = "single full refresh critical path"
            var began = SystemClock.elapsedRealtime()
            runBlocking { withContext(Dispatchers.Main) { vm.refreshAll() } }
            val singleMs = SystemClock.elapsedRealtime() - began
            val singleCounts = counts.map { it.get() }
            val singlePeak = peak.get()
            check(singleCounts == listOf(1, 1, 1))
            counts.forEach { it.set(0) }; peak.set(0)
            stage = "overlapping full refreshes and wallet read"
            began = SystemClock.elapsedRealtime()
            runBlocking {
                withContext(Dispatchers.Main) {
                    (List(4) { async { vm.refreshAll() } } + async { vm.refreshWallet() }).awaitAll()
                }
            }
            val overlapMs = SystemClock.elapsedRealtime() - began
            val overlapCounts = counts.map { it.get() }
            check(unexpected.get() == 0)
            if (verify) {
                check(singlePeak == 3)
                check(overlapCounts == listOf(1, 1, 1))
                stage = "Preview cancels pending reads and prevents late account publication"
                counts.forEach { it.set(0) }
                runBlocking {
                    withContext(Dispatchers.Main) {
                        val pending = async { vm.refreshAll() }
                        kotlinx.coroutines.delay(30)
                        vm.startPreview()
                        try { pending.await() } catch (_: kotlinx.coroutines.CancellationException) { }
                        kotlinx.coroutines.delay(200)
                        check(vm.profile.value?.name != "离线性能用户")
                        val after = counts.map { it.get() }
                        vm.refreshAll()
                        check(counts.map { it.get() } == after)
                    }
                }
            }
            result.putString("stream", "PASS: singleMs=$singleMs,singleCounts=$singleCounts,peak=$singlePeak; " +
                "overlapMs=$overlapMs,overlapCounts=$overlapCounts; terminal150msFixture=true\n")
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}; counts=${counts.map { it.get() }}\n")
        } finally {
            if (started) test.runOnMainSync {
                activity?.viewModelStore?.clear()
                session.clear()
                Preview.enabled = false
                Store.clearAccount()
                clientField.set(null, original)
                activity?.finish()
            }
        }
        test.finish(code, result)
    }
}
