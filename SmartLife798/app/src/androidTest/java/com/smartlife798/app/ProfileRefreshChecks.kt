package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.MeScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Terminal offline fixtures: never use this instrumentation against a retained account. */
object ProfileRefreshChecks {
    private const val TOKEN = "offline-profile-refresh-token"
    private const val UID = "offline-profile-refresh-user"
    private const val SERVER_TIME = 1793016000000L

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var started = false
        var tickAtForeground = -1
        var currentTick: () -> Int = { -1 }
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val preferences = test.targetContext.getSharedPreferences("smartlife798", Context.MODE_PRIVATE)
        val originalAuto = preferences.all["autoCheckIn"] as? Boolean
        val masterReads = AtomicInteger()
        val walletReads = AtomicInteger()
        val statusReads = AtomicInteger()
        val otherCalls = AtomicInteger()
        val walletGate = AtomicReference<CountDownLatch?>()
        val walletEntered = CountDownLatch(1)
        val fixture = originalClient.newBuilder().addInterceptor { chain ->
            check(chain.request().header("Authorization") == TOKEN)
            check(chain.request().method == "GET")
            val body = when (chain.request().url.encodedPath) {
                "/api/v1/ui/app/master" -> {
                    masterReads.incrementAndGet()
                    """{"code":0,"time":$SERVER_TIME,"data":{"account":{"id":"$UID","name":"离线刷新用户","pn":"","useScore":0},"favos":[]}}"""
                }
                "/api/v1/acc/wallet/owner" -> {
                    walletReads.incrementAndGet()
                    walletGate.getAndSet(null)?.let {
                        walletEntered.countDown()
                        if (!it.await(30, TimeUnit.SECONDS)) throw java.io.InterruptedIOException("offline gate timeout")
                    }
                    """{"code":0,"data":{"aw":{"total":12.0,"ofCash":12.0,"owner":{"id":"$UID"}}}}"""
                }
                "/api/v1/acc/score/mission-lst" -> {
                    statusReads.incrementAndGet()
                    """{"code":0,"time":$SERVER_TIME,"data":{"accScoreRsp":{"validScore":"120","daily":{"week":1}},"dailyRSP":{"adId":"offline-profile-refresh","score":5}}}"""
                }
                else -> {
                    otherCalls.incrementAndGet()
                    """{"code":-1,"msg":"离线未提供路径"}"""
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        fun counts() = listOf(masterReads.get(), walletReads.get(), statusReads.get(), otherCalls.get())
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null &&
                Store.active() == null && Store.pendingStart() == null && Store.recents().isEmpty() &&
                Store.pinned().isEmpty() && session.tokenRejected.value == 0)
            started = true
            clientField.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            val screen = activity
            lateinit var vm: MainViewModel
            test.runOnMainSync {
                screen.setContent { }
                screen.viewModelStore.clear()
                Preview.enabled = false
                Store.saveAutoCheckIn(false)
                session.saveLogin(LoginInfo(token = TOKEN, uid = UID), "")
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(true, allowAutoCheckIn = false)
            }

            stage = "first entry reads exactly one profile, wallet and signed status"
            val visible = mutableStateOf(true)
            // This fixture replaces AppRoot and drives its own foreground events.
            // Supply the matching lifecycle instead of relying on the emulator
            // window's visibility (multi-user/locked screens can leave it stopped).
            lateinit var pageOwner: LifecycleOwner
            test.runOnMainSync {
                pageOwner = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
                }
                currentTick = { vm.refreshTick.value }
                screen.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
                        SmartLifeTheme { if (visible.value) MeScreen(vm, onOpenWallet = {}) }
                    }
                }
            }
            await { vm.profile.value != null && vm.wallet.value != null && vm.signedInToday.value == true && !vm.checkingIn.value }
            SystemClock.sleep(300)
            check(counts() == listOf(1, 1, 1, 0))

            stage = "debounced navigation and page recreation keep loaded state without more reads"
            repeat(3) {
                val previousTick = vm.refreshTick.value
                test.runOnMainSync { vm.bumpRefresh() }
                await { vm.refreshTick.value > previousTick }
                SystemClock.sleep(100)
                test.runOnMainSync { visible.value = false }
                test.waitForIdleSync()
                test.runOnMainSync { visible.value = true }
                test.waitForIdleSync()
            }
            SystemClock.sleep(300)
            check(counts() == listOf(1, 1, 1, 0))

            stage = "foreground change invalidates once and still never sends check-in"
            tickAtForeground = vm.refreshTick.value
            test.runOnMainSync {
                (pageOwner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED
                vm.setAppResumed(false)
                vm.setAppResumed(true, allowAutoCheckIn = false)
                (pageOwner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
                vm.bumpRefresh()
            }
            stage = "foreground debounced refresh tick reaches the resumed page"
            await { vm.refreshTick.value > tickAtForeground }
            stage = "foreground change invalidates once and still never sends check-in"
            await { counts() == listOf(2, 2, 2, 0) && !vm.checkingIn.value }
            SystemClock.sleep(300)
            test.runOnMainSync { vm.refreshMePage() }
            SystemClock.sleep(200)
            check(counts() == listOf(2, 2, 2, 0))

            stage = "explicit refresh waits for the full read and coalesces overlapping gestures"
            val release = CountDownLatch(1)
            walletGate.set(release)
            var first: Job? = null
            var duplicate: Job? = null
            try {
                test.runOnMainSync { first = vm.refreshMePage(force = true) }
                check(walletEntered.await(8, TimeUnit.SECONDS))
                check(first?.isActive == true)
                test.runOnMainSync { duplicate = vm.refreshMePage(force = true) }
                check(duplicate === first)
            } finally {
                release.countDown()
            }
            await { first?.isCompleted == true }
            check(counts() == listOf(3, 3, 3, 0))

            stage = "Preview and account change discard the prior page refresh epoch"
            val installedDeveloperMode = Store.developerToolsEnabled()
            test.runOnMainSync {
                vm.startPreview()
                vm.setDeveloperToolsEnabled(!installedDeveloperMode)
            }
            check(Store.developerToolsEnabled() == installedDeveloperMode)
            val beforePreview = counts()
            test.runOnMainSync { check(vm.refreshMePage(force = true) == null) }
            check(counts() == beforePreview)
            test.runOnMainSync {
                screen.setContent { }
                vm.logout()
                check(vm.developerToolsEnabled.value == installedDeveloperMode)
                session.saveLogin(LoginInfo(token = TOKEN, uid = UID), "")
                vm.refreshMePage()
            }
            await { counts() == listOf(4, 4, 4, 0) && !vm.checkingIn.value }
            result.putString("stream", "PASS: first Me entry reads once; delayed navigation ticks and three remounts make zero extra reads; one foreground change refreshes once; explicit refresh waits for gated wallet and coalesces duplicate entry; account change invalidates and Preview performs no requests; only terminal offline GET fixtures, no score-send/bill/device writes\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; counts=${counts()}; " +
                "tickBefore=$tickAtForeground,tickNow=${currentTick()},preview=${Preview.enabled}," +
                "loggedIn=${session.isLoggedIn},activityState=${activity?.lifecycle?.currentState}," +
                "source=${frame?.fileName}:${frame?.lineNumber}\n")
        } finally {
            walletGate.getAndSet(null)?.countDown()
            test.runOnMainSync {
                activity?.let {
                    it.setContent { }
                    it.viewModelStore.clear()
                    it.finish()
                }
            }
            Preview.enabled = false
            if (started) {
                session.clear()
                val editor = preferences.edit()
                if (originalAuto == null) editor.remove("autoCheckIn") else editor.putBoolean("autoCheckIn", originalAuto)
                check(editor.commit())
            }
            clientField.set(null, originalClient)
            fixture.dispatcher.executorService.shutdown()
        }
        test.finish(code, result)
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        check(condition()) { "Timed out" }
    }
}
