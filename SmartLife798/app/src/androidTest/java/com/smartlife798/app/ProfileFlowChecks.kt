package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.gson.JsonParser
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.ActiveUsage
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.RecentDevice
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.StoredProfile
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.MeScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Fully intercepted fixture accounts only; never sends a real account or device command. */
object ProfileFlowChecks {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }
    }

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var fixtureStarted = false
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val originalUid = session.uid
        val originalPhone = session.phone
        val preferences = test.targetContext.getSharedPreferences("smartlife798", Context.MODE_PRIVATE)
        val originalAuto = preferences.all["autoCheckIn"] as? Boolean
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val requests = AtomicInteger()
        val patches = Collections.synchronizedList(mutableListOf<String>())
        val rejectPatch = AtomicBoolean()
        val rejectMasterToken = AtomicBoolean()
        val patchGate = AtomicReference<Gate?>()
        val masterGate = AtomicReference<Gate?>()
        val loginGate = AtomicReference<Gate?>()
        val gates = mutableListOf<Gate>()
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val path = chain.request().url.encodedPath
            val body = when {
                path.endsWith("/acc/login") -> {
                    check(chain.request().method == "POST")
                    loginGate.getAndSet(null)?.hold()
                    """{"code":0,"data":{"al":{"token":"offline-late-login-token","uid":"offline-late-login-user"}}}"""
                }
                path.endsWith("/acc/upt") -> {
                    check(chain.request().method == "POST")
                    val buffer = Buffer()
                    chain.request().body!!.writeTo(buffer)
                    patches.add(buffer.readUtf8())
                    patchGate.getAndSet(null)?.hold()
                    if (rejectPatch.get()) """{"code":-1,"msg":"离线保存失败"}"""
                    else """{"code":0}"""
                }
                path.endsWith("/master") -> {
                    masterGate.getAndSet(null)?.hold()
                    if (rejectMasterToken.get()) """{"code":-99}"""
                    else """{"code":0,"data":{"account":{"id":"offline-profile","name":"旧资料","phone":"","useScore":0},"pltTotalScore":"120","favos":[]}}"""
                }
                else -> """{"code":0,"data":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val guards = linkedMapOf(
                "storedTokenEmpty" to Store.token.isNullOrBlank(),
                "sessionTokenEmpty" to session.token.isNullOrBlank(),
                "profileEmpty" to (Store.profileJson == null),
                "activeEmpty" to (Store.active() == null),
                "pendingStartEmpty" to (Store.pendingStart() == null),
                "recentsEmpty" to Store.recents().isEmpty(),
                "pinnedEmpty" to Store.pinned().isEmpty(),
                "tokenRejectedZero" to (session.tokenRejected.value == 0),
            )
            result.putString("guards", guards.entries.joinToString { "${it.key}=${it.value}" })
            check(guards.values.all { it })
            fixtureStarted = true
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
                session.token = "offline-profile-fixture"
                Store.saveProfile(StoredProfile("offline-profile", "离线资料", "", totalScore = "120", useScore = 0))
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }

            stage = "partial nickname patch and server preference persistence"
            runBlocking {
                check(vm.changeNickname("  已保存昵称  ") is Res.Ok)
                check(vm.setUseScore(true) is Res.Ok)
            }
            check(JsonParser.parseString(patches[0]).asJsonObject.entrySet().map { it.key } == listOf("name"))
            check(JsonParser.parseString(patches[0]).asJsonObject["name"].asString == "已保存昵称")
            check(JsonParser.parseString(patches[1]).asJsonObject.entrySet().map { it.key } == listOf("useScore"))
            check(JsonParser.parseString(patches[1]).asJsonObject["useScore"].asInt == 1)
            check(vm.profile.value?.name == "已保存昵称" && vm.profile.value?.useScore == 1)
            check(vm.profile.value?.totalScore == "120")
            test.runOnMainSync {
                screen.viewModelStore.clear()
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }
            check(vm.profile.value?.name == "已保存昵称" && vm.profile.value?.useScore == 1)
            check(Store.profile()?.name == "已保存昵称" && Store.profile()?.useScore == 1)

            stage = "server rejection preserves both memory and disk"
            val beforeFailure = Store.profileJson
            rejectPatch.set(true)
            runBlocking { check(vm.changeNickname("不能保存的昵称") is Res.Fail) }
            check(vm.profile.value?.name == "已保存昵称" && Store.profileJson == beforeFailure)
            check(!vm.savingProfile.value)
            rejectPatch.set(false)

            stage = "entry guard coalesces concurrent account mutations"
            val heldPatch = Gate().also { gates.add(it); patchGate.set(it) }
            val first = callers.async { vm.changeNickname("防重昵称") }
            check(heldPatch.entered.await(8, TimeUnit.SECONDS))
            val countAtHold = patches.size
            runBlocking { check(vm.setUseScore(false) is Res.Fail) }
            check(patches.size == countAtHold && vm.savingProfile.value)
            heldPatch.release.countDown()
            runBlocking { check(first.await() is Res.Ok) }
            check(vm.profile.value?.name == "防重昵称" && vm.profile.value?.useScore == 1)

            stage = "stale master cannot overwrite an accepted nickname"
            val heldMaster = Gate().also { gates.add(it); masterGate.set(it) }
            test.runOnMainSync { vm.refreshMaster() }
            check(heldMaster.entered.await(8, TimeUnit.SECONDS))
            runBlocking { check(vm.changeNickname("最新昵称") is Res.Ok) }
            heldMaster.release.countDown()
            await { !vm.loadingMaster.value }
            check(vm.profile.value?.name == "最新昵称" && Store.profile()?.name == "最新昵称")

            stage = "late token rejection cannot invalidate the new session"
            val rejectedBefore = session.tokenRejected.value
            rejectMasterToken.set(true)
            val heldRejection = Gate().also { gates.add(it); masterGate.set(it) }
            val staleRequest = callers.async { Api.master() }
            check(heldRejection.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync { session.token = "offline-new-profile-fixture" }
            heldRejection.release.countDown()
            runBlocking { check((staleRequest.await() as? Res.Fail)?.code == -99) }
            test.waitForIdleSync()
            check(session.token == "offline-new-profile-fixture" && session.tokenRejected.value == rejectedBefore)
            rejectMasterToken.set(false)

            stage = "queued token rejection cannot log out a replacement session"
            var rejectionRecorded = false
            test.runOnMainSync {
                screen.viewModelStore.clear()
                session.onTokenRejected(session.token)
                rejectionRecorded = session.isCurrentTokenRejected()
                session.token = "offline-profile-fixture"
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }
            test.waitForIdleSync()
            check(rejectionRecorded && session.token == "offline-profile-fixture" && !session.isCurrentTokenRejected())
            check(vm.profile.value?.name == "最新昵称")

            stage = "preview transition rejects the previous account response"
            val heldOld = Gate().also { gates.add(it); patchGate.set(it) }
            val old = callers.async { vm.changeNickname("旧账号迟到昵称") }
            check(heldOld.entered.await(8, TimeUnit.SECONDS))
            val storedBeforePreview = Store.profileJson
            test.runOnMainSync { vm.startPreview() }
            heldOld.release.countDown()
            runBlocking {
                try {
                    old.await()
                    error("Old account mutation was accepted after Preview")
                } catch (_: CancellationException) { }
            }
            await { !vm.savingProfile.value }
            check(vm.profile.value?.id == Preview.profile.id && Store.profileJson == storedBeforePreview)

            stage = "preview nickname and score never write or send"
            val requestsBeforePreview = requests.get()
            runBlocking {
                check(vm.changeNickname("演示昵称") is Res.Ok)
                check(vm.setUseScore(true) is Res.Ok)
            }
            check(vm.profile.value?.name == "演示昵称" && vm.profile.value?.useScore == 1)
            check(Store.profileJson == storedBeforePreview && requests.get() == requestsBeforePreview)

            stage = "preview profile layout capture"
            test.runOnMainSync {
                vm.startPreview()
                screen.setContent { SmartLifeTheme { MeScreen(vm, onOpenWallet = {}) } }
            }
            test.waitForIdleSync()
            SystemClock.sleep(700)
            val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            File(test.targetContext.filesDir, "profile-demo.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            check(requests.get() == requestsBeforePreview && Store.profileJson == storedBeforePreview)

            stage = "Preview exit preserves account A disk cache until successful account B login"
            test.runOnMainSync {
                screen.setContent { }
                Preview.enabled = false
                session.saveLogin(LoginInfo(token = "offline-cache-a-token", uid = "offline-cache-a"), "13800000000")
                Store.saveProfile(StoredProfile("offline-cache-a", "账号A缓存", "", totalScore = "11", useScore = 1))
                Store.saveRecents(listOf(RecentDevice("offline-a-device", "账号A设备", 5, 1L)))
                Store.setPinned("offline-a-device", true)
                val usage = ActiveUsage("offline-a-device", "账号A设备", 5, 1L, 92)
                Store.setActive(usage)
                Store.setPendingStart(usage)
                Store.saveFavoritesCache("[]")
                check(Store.markCheckInAttempt("2026-10-26", session.token!!))
                Store.saveAutoCheckIn(false)
            }
            val retainedAccountA = preferences.all
            val requestsBeforeExit = requests.get()
            test.runOnMainSync {
                vm.startPreview()
                vm.logout()
            }
            test.waitForIdleSync()
            check(session.token == null && !vm.loggedIn.value && !Preview.enabled)
            check(preferences.all == retainedAccountA && requests.get() == requestsBeforeExit)
            session.saveLogin(LoginInfo(token = "offline-cache-b-token", uid = "offline-cache-b"), "13800000000")
            check(Store.uid == "offline-cache-b" && Store.token == "offline-cache-b-token")
            check(Store.profileJson == null && Store.favoritesCacheJson() == null)
            check(Store.recents().isEmpty() && Store.pinned().isEmpty())
            check(Store.active() == null && Store.pendingStart() == null && Store.checkInAttemptDay() == null)
            check(!Store.autoCheckIn() && requests.get() == requestsBeforeExit)

            stage = "only a proven same uid may retain account cache on successful login"
            Store.saveProfile(StoredProfile("offline-cache-b", "账号B缓存", "", totalScore = "22"))
            val retainedAccountB = Store.profileJson
            session.saveLogin(LoginInfo(token = "offline-cache-b-renewed", uid = "offline-cache-b"), "13800000000")
            check(Store.profileJson == retainedAccountB && !Store.autoCheckIn())
            Store.uid = null
            session.saveLogin(LoginInfo(token = "offline-cache-b-unknown-owner", uid = "offline-cache-b"), "13800000000")
            check(Store.profileJson == null && !Store.autoCheckIn())
            check(requests.get() == requestsBeforeExit)

            for (exitPreview in listOf(false, true)) {
                stage = "late login after Preview transition and exit=$exitPreview never publishes credentials"
                test.runOnMainSync { if (Preview.enabled) vm.logout() }
                val storedBeforeLateLogin = preferences.all
                val requestsBeforeLateLogin = requests.get()
                val heldLogin = Gate().also { gates.add(it); loginGate.set(it) }
                val lateLogin = callers.async { vm.login("13800000000", "000000") }
                check(heldLogin.entered.await(8, TimeUnit.SECONDS))
                test.runOnMainSync {
                    vm.startPreview()
                    if (exitPreview) vm.logout()
                }
                heldLogin.release.countDown()
                var loginCancelled = false
                runBlocking {
                    try {
                        lateLogin.await()
                    } catch (_: CancellationException) {
                        loginCancelled = true
                    }
                }
                test.waitForIdleSync()
                check(loginCancelled && preferences.all == storedBeforeLateLogin)
                check(session.token == null && Preview.enabled == !exitPreview)
                check(vm.loggedIn.value == !exitPreview)
                check(if (exitPreview) vm.profile.value == null else vm.profile.value?.id == Preview.profile.id)
                // Only the intercepted login request ran; no stale-success follow-up reads.
                check(requests.get() == requestsBeforeLateLogin + 1)
            }
            result.putString("stream", "PASS: partial nickname/score POSTs, failure preservation, cached profile after VM recreation, concurrent entry guard, stale master rejection, late/queued token rejection preserves replacement session, Preview rejects late account response, Preview memory-only changes, Preview exit retains A disk cache, successful B login clears account caches and preserves installation preference, only proven same uid retains caches, late login after entering or exiting Preview cannot publish credentials; offline profile screenshot saved; patches=${patches.size}\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            gates.forEach { it.release.countDown() }
            callers.cancel()
            test.runOnMainSync {
                activity?.let { screen ->
                    screen.setContent { }
                    screen.viewModelStore.clear()
                    screen.finish()
                }
            }
            Preview.enabled = false
            if (fixtureStarted) {
                Store.clearAccount()
                // Instrumentation may end the process immediately; wait for preceding apply() writes.
                val editor = preferences.edit()
                if (originalAuto == null) editor.remove("autoCheckIn") else editor.putBoolean("autoCheckIn", originalAuto)
                if (!editor.commit()) {
                    result.putString("stream", "FAIL at fixture cleanup: preference flush failed\n")
                    code = Activity.RESULT_CANCELED
                }
            }
            session.token = originalToken
            session.uid = originalUid
            session.phone = originalPhone
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
