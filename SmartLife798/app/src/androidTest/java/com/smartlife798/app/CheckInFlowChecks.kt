package com.smartlife798.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.gson.JsonParser
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Fixture identities and terminal interceptors only; no actual HTTP request is forwarded. */
object CheckInFlowChecks {
    private const val SERVER_TIME = 1793016000000L // 2026-10-26 20:00 Asia/Shanghai, Monday.
    private enum class Scenario { SUCCESS, ALREADY, UNCONFIRMED, REJECTED, TIMEOUT, NO_TIME, NO_WEEK, NO_REWARD, NO_ACCOUNT, READ_TIMEOUT }
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
    }

    fun run(test: Instrumentation) {
        var stage = "account-free guard"
        var code = Activity.RESULT_CANCELED
        val result = Bundle()
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val originalUid = session.uid
        val originalPhone = session.phone
        val preferences = test.targetContext.getSharedPreferences("smartlife798", Context.MODE_PRIVATE)
        val originalAuto = preferences.all["autoCheckIn"] as? Boolean
        val originalPreview = Preview.enabled
        val viewModels = ViewModelStore()
        val scenario = AtomicReference(Scenario.SUCCESS)
        val signed = AtomicBoolean()
        val posts = AtomicInteger()
        val reads = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val missionGate = AtomicReference<Gate?>()
        val logoutGate = AtomicReference<Gate?>()
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val gates = mutableListOf<Gate>()
        var fixtureStarted = false
        lateinit var vm: MainViewModel
        var vmCreated = false
        val fixture = originalClient.newBuilder().dispatcher(okhttp3.Dispatcher()).addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val activeScenario = scenario.get()
            val body = when {
                path.endsWith("/score/mission-lst") -> {
                    check(request.method == "GET")
                    reads.incrementAndGet()
                    events.add("read")
                    if (activeScenario == Scenario.READ_TIMEOUT) throw InterruptedIOException("offline read timeout")
                    missionGate.getAndSet(null)?.hold()
                    val time = if (activeScenario == Scenario.NO_TIME) "" else ",\"time\":$SERVER_TIME"
                    val week = if (activeScenario == Scenario.NO_WEEK) "" else
                        ",\"week\":${if (activeScenario == Scenario.ALREADY || signed.get()) 1 else 0}"
                    val reward = if (activeScenario == Scenario.NO_REWARD) "" else
                        ",\"dailyRSP\":{\"adId\":\"offline-reward\",\"score\":5}"
                    if (activeScenario == Scenario.NO_ACCOUNT)
                        """{"code":0$time,"data":{"dailyRSP":{"adId":"offline-reward","score":5}}}"""
                    else """{"code":0$time,"data":{"accScoreRsp":{"validScore":"120","daily":{"ltime":$SERVER_TIME$week}}$reward}}"""
                }
                path.endsWith("/score/score-send") -> {
                    check(request.method == "POST")
                    posts.incrementAndGet()
                    events.add("post")
                    check(request.url.queryParameter("s") == "1")
                    check(request.url.queryParameter("sign")?.matches(Regex("[0-9a-f]{32}")) == true)
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val params = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    check(params.keySet() == setOf("adId", "weekday", "addScoreType", "addScore"))
                    check(params["adId"].asString == "offline-reward" && params["weekday"].asInt == 1)
                    check(params["addScoreType"].asInt == 1 && params["addScore"].asInt == 5)
                    when (activeScenario) {
                        Scenario.TIMEOUT -> throw InterruptedIOException("offline timeout fixture")
                        Scenario.REJECTED -> """{"code":-1,"msg":"离线签到拒绝"}"""
                        else -> {
                            signed.set(activeScenario != Scenario.UNCONFIRMED)
                            """{"code":0,"time":$SERVER_TIME}"""
                        }
                    }
                }
                path.endsWith("/master") -> """{"code":0,"time":$SERVER_TIME,"data":{"account":{"id":"offline-checkin-user","name":"离线用户","useScore":0},"pltTotalScore":"125","favos":[]}}"""
                path.endsWith("/logout") -> {
                    logoutTokens.add(request.header("Authorization"))
                    logoutGate.getAndSet(null)?.hold()
                    """{"code":0}"""
                }
                else -> error("Unexpected offline check-in path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()

        fun create(clearAttempt: Boolean = true) {
            test.runOnMainSync {
                viewModels.clear()
                Preview.enabled = false
                if (clearAttempt) Store.clearAccount()
                session.token = "offline-checkin-token"
                session.uid = "offline-checkin-user"
                Store.token = session.token
                Store.uid = session.uid
                vm = ViewModelProvider(viewModels,
                    ViewModelProvider.AndroidViewModelFactory(test.targetContext.applicationContext as Application))
                    .get(MainViewModel::class.java)
                vmCreated = true
                vm.setAppResumed(false)
            }
        }

        fun foreground() = test.runOnMainSync { vm.setAppResumed(true) }
        fun reset(selected: Scenario) {
            scenario.set(selected)
            signed.set(false)
            events.clear()
            reads.set(0)
            posts.set(0)
            create()
        }

        try {
            val guards = linkedMapOf(
                "storedTokenEmpty" to Store.token.isNullOrBlank(),
                "sessionTokenEmpty" to session.token.isNullOrBlank(),
                "profileEmpty" to (Store.profileJson == null),
                "activeEmpty" to (Store.active() == null),
                "pendingStartEmpty" to (Store.pendingStart() == null),
                "recentsEmpty" to Store.recents().isEmpty(),
                "pinnedEmpty" to Store.pinned().isEmpty(),
                "attemptEmpty" to (Store.checkInAttemptDay() == null),
                "tokenRejectedZero" to (session.tokenRejected.value == 0),
            )
            result.putString("guards", guards.entries.joinToString { "${it.key}=${it.value}" })
            check(guards.values.all { it })
            fixtureStarted = true
            clientField.set(null, fixture)
            check(preferences.edit().remove("autoCheckIn").commit())
            create()
            check(vm.autoCheckIn.value && Store.autoCheckIn())

            stage = "default enabled and confirmed read-write-read"
            foreground()
            await { vm.checkInStatus.value == "今日已自动签到" && vm.profile.value?.totalScore == "125" }
            check(posts.get() == 1 && reads.get() == 2)
            check(events.take(3) == listOf("read", "post", "read"))
            check(Store.checkInAttemptDay() == "2026-10-26" && vm.profile.value?.totalScore == "125")
            repeat(5) { foreground() }
            test.waitForIdleSync()
            check(posts.get() == 1 && reads.get() == 2)

            stage = "reopening enabled switch preserves confirmed daily status without requests"
            test.runOnMainSync {
                vm.setAutoCheckIn(false)
                check(vm.checkInStatus.value == "自动签到已关闭")
                vm.setAutoCheckIn(true)
            }
            test.waitForIdleSync()
            check(vm.checkInStatus.value == "今日已签到" && posts.get() == 1 && reads.get() == 2)

            stage = "already signed never posts"
            reset(Scenario.ALREADY)
            foreground()
            await { vm.checkInStatus.value == "今日已签到" }
            check(posts.get() == 0 && reads.get() == 1 && Store.checkInAttemptDay() == null)

            stage = "disabled preference and Preview send no requests"
            reset(Scenario.SUCCESS)
            test.runOnMainSync { vm.setAutoCheckIn(false) }
            foreground()
            test.waitForIdleSync()
            check(!Store.autoCheckIn() && reads.get() == 0 && posts.get() == 0)
            create()
            check(!vm.autoCheckIn.value)
            test.runOnMainSync {
                vm.startPreview()
                vm.setAutoCheckIn(true)
                vm.setAppResumed(true)
            }
            test.waitForIdleSync()
            check(reads.get() == 0 && posts.get() == 0 && !Store.autoCheckIn())
            check(vm.checkInStatus.value == "演示模式下不执行签到")
            Preview.enabled = false
            Store.saveAutoCheckIn(true)

            for (failure in listOf(Scenario.REJECTED, Scenario.TIMEOUT)) {
                stage = "$failure never replays after foreground or VM recreation"
                reset(failure)
                foreground()
                await { vm.checkInStatus.value.startsWith("自动签到未确认") }
                check(posts.get() == 1 && Store.checkInAttemptDay() == "2026-10-26")
                foreground()
                await { vm.checkInStatus.value.startsWith("今日签到已提交过") }
                create(clearAttempt = false)
                foreground()
                await { vm.checkInStatus.value.startsWith("今日签到已提交过") }
                check(posts.get() == 1)
            }

            stage = "accepted write without signed read-back remains unconfirmed"
            reset(Scenario.UNCONFIRMED)
            foreground()
            await { vm.checkInStatus.value.startsWith("签到已受理，状态待确认") }
            check(posts.get() == 1 && reads.get() == 2)
            await { !vm.checkingIn.value }
            foreground()
            await { vm.checkInStatus.value.startsWith("今日签到已提交过") }
            check(posts.get() == 1)

            stage = "switching off during a pending read never submits"
            reset(Scenario.SUCCESS)
            val disabledRead = Gate().also { gates.add(it); missionGate.set(it) }
            foreground()
            check(disabledRead.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync { vm.setAutoCheckIn(false) }
            disabledRead.release.countDown()
            await { readJobComplete(vm) }
            check(posts.get() == 0 && Store.checkInAttemptDay() == null && !Store.autoCheckIn())
            Store.saveAutoCheckIn(true)

            for (missing in listOf(Scenario.NO_TIME, Scenario.NO_ACCOUNT, Scenario.NO_REWARD)) {
                stage = "$missing never fabricates a command"
                reset(missing)
                foreground()
                await { !vm.checkInStatus.value.startsWith("正在") && vm.checkInStatus.value != "尚未检查签到" }
                check(reads.get() == 1 && posts.get() == 0 && Store.checkInAttemptDay() == null)
            }

            stage = "repeated foreground coalesces pending status request"
            reset(Scenario.SUCCESS)
            val sharedRead = Gate().also { gates.add(it); missionGate.set(it) }
            foreground()
            check(sharedRead.entered.await(8, TimeUnit.SECONDS))
            repeat(5) { foreground() }
            check(reads.get() == 1 && posts.get() == 0)
            sharedRead.release.countDown()
            await { vm.checkInStatus.value == "今日已自动签到" }
            check(posts.get() == 1 && reads.get() == 2)

            stage = "late previous-session read never submits"
            reset(Scenario.SUCCESS)
            val oldRead = Gate().also { gates.add(it); missionGate.set(it) }
            foreground()
            check(oldRead.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync { session.token = "offline-replacement-token" }
            oldRead.release.countDown()
            await { readJobComplete(vm) }
            check(posts.get() == 0 && Store.checkInAttemptDay() == null)

            stage = "Preview cancels the previous status result"
            reset(Scenario.SUCCESS)
            val previewRead = Gate().also { gates.add(it); missionGate.set(it) }
            foreground()
            check(previewRead.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync { vm.startPreview() }
            previewRead.release.countDown()
            await { readJobComplete(vm) }
            check(posts.get() == 0 && Store.checkInAttemptDay() == null)
            check(vm.checkInStatus.value == "演示模式下不执行签到")

            stage = "logout immediately cancels pending check-in and late logout preserves replacement session"
            reset(Scenario.SUCCESS)
            val logoutRead = Gate().also { gates.add(it); missionGate.set(it) }
            val pendingLogout = Gate().also { gates.add(it); logoutGate.set(it) }
            foreground()
            check(logoutRead.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync {
                vm.logout()
                check(!vm.loggedIn.value && session.token == null && Store.token == null)
            }
            check(pendingLogout.entered.await(8, TimeUnit.SECONDS))
            check(logoutTokens.last() == "offline-checkin-token")
            logoutRead.release.countDown()
            await { readJobComplete(vm) }
            check(posts.get() == 0 && Store.checkInAttemptDay() == null)
            test.runOnMainSync {
                session.saveLogin(LoginInfo(token = "offline-new-login-token", uid = "offline-new-login-user"), "13800000000")
            }
            pendingLogout.release.countDown()
            await { fixture.dispatcher.runningCallsCount() == 0 }
            test.waitForIdleSync()
            check(session.token == "offline-new-login-token" && session.uid == "offline-new-login-user")
            check(Store.token == "offline-new-login-token" && Store.uid == "offline-new-login-user")
            check(posts.get() == 0)

            stage = "manual check-in works with automation disabled and reports success"
            reset(Scenario.SUCCESS)
            test.runOnMainSync { vm.setAutoCheckIn(false) }
            foreground()
            val manualRead = Gate().also { gates.add(it); missionGate.set(it) }
            test.runOnMainSync { vm.checkInNow() }
            check(manualRead.entered.await(8, TimeUnit.SECONDS))
            check(vm.checkingIn.value)
            test.runOnMainSync {
                vm.checkInNow()
                vm.setAutoCheckIn(false)
                vm.setAppResumed(true)
            }
            check(reads.get() == 1 && posts.get() == 0)
            manualRead.release.countDown()
            await { !vm.checkingIn.value }
            check(vm.checkInStatus.value == "今日已签到" && vm.message.value == "签到成功")
            check(!vm.autoCheckIn.value && !Store.autoCheckIn() && posts.get() == 1 && reads.get() == 2)

            stage = "manual fresh read recognizes existing check-in without posting again"
            test.runOnMainSync { vm.checkInNow() }
            await { !vm.checkingIn.value }
            check(vm.message.value == "今日已签到" && posts.get() == 1 && reads.get() == 3)
            Store.saveAutoCheckIn(true)

            stage = "explicit retry after a rejected auto post reads current state then submits once"
            reset(Scenario.REJECTED)
            foreground()
            await { !vm.checkingIn.value }
            check(posts.get() == 1 && vm.message.value?.contains("离线签到拒绝") == true)
            foreground()
            await { !vm.checkingIn.value }
            check(posts.get() == 1)
            scenario.set(Scenario.SUCCESS)
            events.clear()
            test.runOnMainSync { vm.checkInNow() }
            await { !vm.checkingIn.value }
            check(posts.get() == 2 && events.take(3) == listOf("read", "post", "read"))
            check(vm.message.value == "签到成功")

            stage = "failed status query reports a reason and leaves manual retry available"
            reset(Scenario.READ_TIMEOUT)
            foreground()
            await { !vm.checkingIn.value }
            check(posts.get() == 0 && Store.checkInAttemptDay() == null)
            check(vm.message.value == "签到检查失败：网络不可用或请求超时")
            scenario.set(Scenario.SUCCESS)
            test.runOnMainSync { vm.checkInNow() }
            await { !vm.checkingIn.value }
            check(posts.get() == 1 && vm.message.value == "签到成功")

            stage = "original absent-week fallback submits automatically once without inventing success"
            reset(Scenario.NO_WEEK)
            foreground()
            await { !vm.checkingIn.value }
            check(posts.get() == 1 && vm.checkInStatus.value.startsWith("签到已受理，状态待确认"))
            check(vm.message.value != "签到成功")
            foreground()
            await { !vm.checkingIn.value }
            check(posts.get() == 1)
            scenario.set(Scenario.SUCCESS)
            test.runOnMainSync { vm.checkInNow() }
            await { !vm.checkingIn.value }
            check(posts.get() == 1 && vm.message.value == "今日已签到")

            stage = "manual action still refuses missing time or server reward"
            for (missing in listOf(Scenario.NO_TIME, Scenario.NO_REWARD)) {
                reset(missing)
                test.runOnMainSync { vm.setAutoCheckIn(false) }
                foreground()
                test.runOnMainSync { vm.checkInNow() }
                await { !vm.checkingIn.value }
                check(reads.get() == 1 && posts.get() == 0 && Store.checkInAttemptDay() == null)
                check(!vm.message.value.isNullOrBlank())
                Store.saveAutoCheckIn(true)
            }

            stage = "rapid disable and enable resumes a cancelled check without duplicate writes"
            reset(Scenario.SUCCESS)
            val toggledRead = Gate().also { gates.add(it); missionGate.set(it) }
            foreground()
            check(toggledRead.entered.await(8, TimeUnit.SECONDS))
            test.runOnMainSync {
                vm.setAutoCheckIn(false)
                vm.setAutoCheckIn(true)
            }
            toggledRead.release.countDown()
            await { vm.checkInStatus.value == "今日已自动签到" && !vm.checkingIn.value }
            check(posts.get() == 1 && vm.message.value == "自动签到成功")

            stage = "failed durable write blocks both automatic and explicit retry POSTs"
            reset(Scenario.SUCCESS)
            val prefsField = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val realPreferences = prefsField.get(null)
            val failedCommits = AtomicInteger()
            val failingPreferences = object : android.content.SharedPreferences by preferences {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : android.content.SharedPreferences.Editor by editor {
                        override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            // Mirror Android's memory-before-disk behavior, then report a failed disk commit.
                            editor.apply()
                            failedCommits.incrementAndGet()
                            return false
                        }
                    }
                }
            }
            prefsField.set(null, failingPreferences)
            try {
                foreground()
                await { !vm.checkingIn.value }
                check(posts.get() == 0 && Store.checkInAttemptDay() == "2026-10-26")
                test.runOnMainSync { vm.checkInNow() }
                await { !vm.checkingIn.value }
                check(posts.get() == 0 && reads.get() == 2 && failedCommits.get() == 2)
            } finally { prefsField.set(null, realPreferences) }

            stage = "new foreground waits for previous ViewModel cleanup instead of losing check-in"
            reset(Scenario.SUCCESS)
            val commandMutex = MainViewModel::class.java.getDeclaredField("CHECK_IN_COMMAND").apply {
                isAccessible = true
            }.get(null) as kotlinx.coroutines.sync.Mutex
            check(commandMutex.tryLock())
            var heldCommand = true
            try {
                foreground()
                test.waitForIdleSync()
                check(vm.checkingIn.value && reads.get() == 0 && posts.get() == 0)
                commandMutex.unlock()
                heldCommand = false
                await { !vm.checkingIn.value && vm.checkInStatus.value == "今日已自动签到" }
                check(posts.get() == 1 && reads.get() == 2)
            } finally { if (heldCommand) commandMutex.unlock() }

            stage = "manual Preview is explanatory and preserves every account preference"
            reset(Scenario.SUCCESS)
            val savedBeforePreview = preferences.all
            test.runOnMainSync {
                vm.startPreview()
                vm.checkInNow()
            }
            check(reads.get() == 0 && posts.get() == 0 && !vm.checkingIn.value)
            check(vm.message.value == "演示模式下不执行签到" && preferences.all == savedBeforePreview)

            stage = "logout clears attempt and preserves disabled installation preference"
            reset(Scenario.ALREADY)
            check(Store.markCheckInAttempt("2026-10-26", session.token!!))
            check(!Store.markCheckInAttempt("2026-10-26", session.token!!))
            test.runOnMainSync {
                vm.setAutoCheckIn(false)
                vm.logout()
            }
            await { !vm.loggedIn.value && session.token == null }
            check(Store.checkInAttemptDay() == null && !Store.autoCheckIn())
            result.putString("stream", "PASS: default enabled; global success/error notices; manual works with automation off, fresh signed read and explicit retry; original absent-week fallback prepares once but strict read-back never fabricates success; failed durable commit blocks auto/manual POST; automatic lock waits; rapid toggle recovery; read-post-confirm; concurrent foreground coalescing; rejection/timeout never auto replay across VM recreation; missing time/account/reward blocks submit; old token and Preview responses rejected; pending logout cancels check-in and preserves replacement session; logout retains install preference; entirely offline fixtures\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("state", "reads=${reads.get()},posts=${posts.get()},busy=${if (vmCreated) vm.checkingIn.value else false},status=${if (vmCreated) vm.checkInStatus.value else "not initialized"}")
            result.putString("failureFrames", failure.stackTrace.take(4).joinToString { "${it.methodName}:${it.lineNumber}" })
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}\n")
        } finally {
            gates.forEach { it.release.countDown() }
            test.runOnMainSync { viewModels.clear() }
            if (fixtureStarted) {
                Preview.enabled = false
                Store.clearAccount()
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
            Preview.enabled = originalPreview
            clientField.set(null, originalClient)
            fixture.dispatcher.executorService.shutdown()
        }
        test.finish(code, result)
    }

    private fun readJobComplete(vm: MainViewModel): Boolean =
        (MainViewModel::class.java.getDeclaredField("checkInJob").apply { isAccessible = true }
            .get(vm) as? kotlinx.coroutines.Job)?.isCompleted == true

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(30)
        }
        check(condition()) { "Timed out" }
    }
}
