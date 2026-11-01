package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.FixtureData
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.Res
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Deterministically stall disk restoration without stalling Main; no real transport. */
object FavoriteCacheChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var started = false
        var gate: CountDownLatch? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val prefsField = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val prefs = prefsField.get(null) as SharedPreferences
        val savedAuto = prefs.all["autoCheckIn"] as? Boolean
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = Api.client
        val reads = AtomicInteger()
        val otherReads = AtomicInteger()
        val unexpected = AtomicInteger()
        val mutations = AtomicInteger()
        val mutationEntered = CountDownLatch(1)
        val mutationRelease = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val fixture = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }.addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.method != "GET" -> {
                    unexpected.incrementAndGet()
                    """{"code":-1}"""
                }
                request.url.encodedPath == "/api/v1/dev/favo" -> {
                    mutations.incrementAndGet()
                    mutationEntered.countDown()
                    check(mutationRelease.await(8, TimeUnit.SECONDS))
                    """{"code":0}"""
                }
                request.url.encodedPath == "/api/v1/ui/app/master" -> {
                    reads.incrementAndGet()
                    """{"code":0,"data":{"favos":[]}}"""
                }
                request.url.encodedPath == "/api/v1/bill/lst-owner" -> {
                    otherReads.incrementAndGet()
                    """{"code":0,"data":[]}"""
                }
                else -> {
                    unexpected.incrementAndGet()
                    """{"code":-1}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            check(Store.active() == null && Store.pendingStart() == null)
            started = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            test.runOnMainSync {
                activity!!.setContent { }
                activity!!.viewModelStore.clear()

                Store.saveAutoCheckIn(false)
                clientField.set(null, fixture)
                session.saveLogin(LoginInfo(token = "offline-cache-token", uid = "offline-cache-user"), "")
            }
            for (scenario in listOf("restore", "master", "logout", "account", "malformed")) {
                stage = scenario
                session.token = "offline-cache-token"
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1).also { gate = it }
                Store.saveFavoritesCache(if (scenario == "malformed") "[invalid" else
                    """[{"id":"offline-cache-device","name":"离线缓存"}]""")
                prefsField.set(null, object : SharedPreferences by prefs {
                    override fun getString(key: String?, defValue: String?): String? {
                        val value = prefs.getString(key, defValue)
                        if (key == "favcache") {
                            check(Looper.myLooper() != Looper.getMainLooper())
                            entered.countDown()
                            check(release.await(8, TimeUnit.SECONDS))
                        }
                        return value
                    }
                })
                lateinit var vm: MainViewModel
                test.runOnMainSync {
                    activity!!.viewModelStore.clear()

                    vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
                }
                check(entered.await(5, TimeUnit.SECONDS))
                val jobField = MainViewModel::class.java.getDeclaredField("favoriteRestoreJob").apply { isAccessible = true }
                val restore = jobField.get(vm) as Job
                // This Main round trip must complete while cache IO is still gated.
                test.runOnMainSync { check(vm.favorites.value.isEmpty()) }
                if (scenario == "master") {
                    lateinit var refresh: Job
                    test.runOnMainSync { refresh = vm.refreshMaster() }
                    runBlocking { refresh.join() }
                    check(reads.get() == 1)
                } else if (scenario == "logout") {
                    test.runOnMainSync { session.clear(); vm.logout() }
                }
                if (scenario == "account") {
                    test.runOnMainSync {
                        session.saveLogin(LoginInfo(token = "offline-cache-next-token", uid = "offline-cache-next-user"), "")
                    }
                }
                val expected = vm.favorites.value
                release.countDown()
                runBlocking { restore.join() }
                test.runOnMainSync {
                    when (scenario) {
                        "restore" -> check(vm.favorites.value.single().id == "offline-cache-device")
                        "malformed" -> check(vm.favorites.value.isEmpty())
                        else -> check(vm.favorites.value == expected)
                    }
                }
                prefsField.set(null, prefs)
                if (scenario == "master") check(Store.favoritesCacheJson() == "[]")
            }
            check(reads.get() == 1 && otherReads.get() == 1 && unexpected.get() == 0)
            stage = "duplicate favorite and logout"
            lateinit var mutationVm: MainViewModel
            test.runOnMainSync {
                activity!!.viewModelStore.clear()
                session.token = "offline-cache-token"
                mutationVm = ViewModelProvider(activity!!)[MainViewModel::class.java]
            }
            val mutation = scope.async { mutationVm.toggleFavorite(Device(id = "offline-mutation")) }
            check(mutationEntered.await(5, TimeUnit.SECONDS))
            runBlocking { check(mutationVm.toggleFavorite(Device(id = "offline-mutation")) is Res.Fail) }
            test.runOnMainSync { mutationVm.logout() }
            mutationRelease.countDown()
            runBlocking { check(runCatching { mutation.await() }.exceptionOrNull() is CancellationException) }
            check(mutations.get() == 1 && mutationVm.favorites.value.isEmpty() && Store.favoritesCacheJson() == null)
            result.putString("stream", "PASS: duplicate favorite rejected; logout defeats late mutation/cache write; cache read/parse off Main; stalled IO does not block UI; empty server snapshot, account change and logout defeat late restore; malformed cache ignored; only terminal master/bills/favorite fixtures, no real business writes\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; source=${error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }}\n")
        } finally {
            gate?.countDown()
            mutationRelease.countDown()
            scope.cancel()
            prefsField.set(null, prefs)
            test.runOnMainSync {
                activity?.let { it.setContent { }; it.viewModelStore.clear(); it.finish() }
            }

            if (started) {
                session.clear()
                val editor = prefs.edit()
                if (savedAuto == null) editor.remove("autoCheckIn") else editor.putBoolean("autoCheckIn", savedAuto)
                check(editor.commit())
            }
            clientField.set(null, original)
            fixture.dispatcher.executorService.shutdown()
        }
        test.finish(code, result)
    }
}
