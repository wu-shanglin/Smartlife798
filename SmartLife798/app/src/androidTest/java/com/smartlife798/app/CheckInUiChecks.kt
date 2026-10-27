package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.gson.JsonParser
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.LoginInfo
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.StoredProfile
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Real AppRoot, fixture identity, terminal API interceptor; run on the isolated offline emulator. */
object CheckInUiChecks {
    private const val TOKEN = "offline-checkin-ui-token"
    private const val UID = "offline-checkin-ui-user"
    private const val SERVER_TIME = 1793016000000L // Monday, 2026-10-26 in Asia/Shanghai.

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }
    }

    fun run(test: Instrumentation) {
        var stage = "account-free guard"
        var code = Activity.RESULT_CANCELED
        val result = Bundle()
        var activity: MainActivity? = null
        var fixtureStarted = false
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val originalClient = Api.client
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalToken = session.token
        val originalUid = session.uid
        val originalPhone = session.phone
        val originalPreview = Preview.enabled
        val preferences = test.targetContext.getSharedPreferences("smartlife798", Context.MODE_PRIVATE)
        val originalAuto = preferences.all["autoCheckIn"] as? Boolean
        val originalStartup = preferences.all["startupPage"] as? String
        val signed = AtomicBoolean()
        val reject = AtomicBoolean()
        val reads = AtomicInteger()
        val posts = AtomicInteger()
        val calls = AtomicInteger()
        val unknown = Collections.synchronizedList(mutableListOf<String>())
        val postGate = AtomicReference<Gate?>()
        val gates = mutableListOf<Gate>()
        val fixture = originalClient.newBuilder().dispatcher(Dispatcher()).addInterceptor { chain ->
            val request = chain.request()
            calls.incrementAndGet()
            check(request.header("Authorization") == TOKEN)
            check(request.header("ApplicationType") == "1,1")
            check(request.header("VersionCode") == session.versionHeader())
            val body = when (request.url.encodedPath) {
                "/api/v1/acc/score/mission-lst" -> {
                    check(request.method == "GET")
                    reads.incrementAndGet()
                    """{"code":0,"time":$SERVER_TIME,"data":{"accScoreRsp":{"validScore":"120","daily":{"ltime":$SERVER_TIME,"week":${if (signed.get()) 1 else 0}}},"dailyRSP":{"adId":"offline-ui-reward","score":5}}}"""
                }
                "/api/v1/acc/score/score-send" -> {
                    check(request.method == "POST")
                    posts.incrementAndGet()
                    check(request.url.queryParameter("s") == "1")
                    check(request.url.queryParameter("sign")?.matches(Regex("[0-9a-f]{32}")) == true)
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val params = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    check(params["adId"].asString == "offline-ui-reward" && params["weekday"].asInt == 1)
                    check(params["addScoreType"].asInt == 1 && params["addScore"].asInt == 5)
                    postGate.getAndSet(null)?.hold()
                    if (reject.get()) """{"code":-1,"msg":"离线签到拒绝"}"""
                    else {
                        signed.set(true)
                        """{"code":0,"time":$SERVER_TIME}"""
                    }
                }
                "/api/v1/ui/app/master" -> {
                    check(request.method == "GET")
                    """{"code":0,"time":$SERVER_TIME,"data":{"account":{"id":"$UID","name":"离线签到用户","pn":"","no":"OFFLINE","useScore":0},"pltTotalScore":"${if (signed.get()) 125 else 120}","favos":[]}}"""
                }
                "/api/v1/bill/lst-owner" -> {
                    check(request.method == "GET")
                    """{"code":0,"time":$SERVER_TIME,"data":[]}"""
                }
                "/api/v1/acc/wallet/owner" -> {
                    check(request.method == "GET")
                    """{"code":0,"time":$SERVER_TIME,"data":{"aw":{"total":12.0,"ofCash":12.0,"owner":{"id":"$UID"}}}}"""
                }
                else -> {
                    // Even unexpected paths terminate here; no real account/device action can escape.
                    unknown.add(request.url.encodedPath)
                    """{"code":-1,"msg":"离线测试未提供该路径"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
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
                "attemptEmpty" to (Store.checkInAttemptDay() == null),
                "tokenRejectedZero" to (session.tokenRejected.value == 0),
            )
            result.putString("guards", guards.entries.joinToString { "${it.key}=${it.value}" })
            check(guards.values.all { it })
            fixtureStarted = true
            clientField.set(null, fixture)
            val autoPost = Gate().also { gates.add(it); postGate.set(it) }
            test.runOnMainSync {
                Preview.enabled = false
                Store.saveAutoCheckIn(true)
                Store.saveStartupPage(StartupPage.HOME)
                session.saveLogin(LoginInfo(token = TOKEN, uid = UID), "")
                Store.saveProfile(StoredProfile(UID, "离线签到用户", "", no = "OFFLINE", totalScore = "120", useScore = 0))
            }

            stage = "real AppRoot starts automatic check-in on Home"
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val screen = activity ?: error("MainActivity unavailable")
            lateinit var vm: MainViewModel
            test.runOnMainSync { vm = ViewModelProvider(screen)[MainViewModel::class.java] }
            check(!screen.intent.getBooleanExtra("preview", false) && !Preview.enabled)
            check(autoPost.entered.await(8, TimeUnit.SECONDS))
            await { find(test, "扫码即刻使用") != null }
            autoPost.release.countDown()
            stage = "automatic success Snackbar is visible on Home"
            await { find(test, "自动签到成功") != null && !vm.checkingIn.value }
            check(posts.get() == 1 && vm.checkInStatus.value == "今日已自动签到")
            check(find(test, "扫码即刻使用") != null && !Preview.enabled)
            await { find(test, "我的") != null }
            val noticeBounds = android.graphics.Rect().also { find(test, "自动签到成功")!!.getBoundsInScreen(it) }
            val navBounds = android.graphics.Rect().also { actionNode(test, "我的").getBoundsInScreen(it) }
            check(noticeBounds.bottom <= navBounds.top)
            capture(test, "check-in-ui-home-success.png")

            stage = "My page keeps manual action available when automation is off"
            click(test, "我的")
            await { find(test, "自动签到开关") != null }
            reveal(test, "手动签到")
            click(test, "自动签到开关")
            await { !vm.autoCheckIn.value && !Store.autoCheckIn() }
            check(actionNode(test, "手动签到").isEnabled)

            stage = "manual click shows disabled loading state and coalesces repeated clicks"
            signed.set(false)
            val manualPost = Gate().also { gates.add(it); postGate.set(it) }
            val beforeManual = posts.get()
            click(test, "手动签到")
            check(manualPost.entered.await(8, TimeUnit.SECONDS))
            await { vm.checkingIn.value && find(test, "正在签到…") != null }
            val busy = actionNode(test, "正在签到…")
            check(!busy.isEnabled)
            val hasProgress = nodes(busy).any {
                it.rangeInfo != null || it.className?.toString() == "android.widget.ProgressBar"
            }
            result.putBoolean("busyProgressSemantics", hasProgress)
            check(hasProgress)
            repeat(3) { busy.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            test.waitForIdleSync()
            check(posts.get() == beforeManual + 1 && vm.checkingIn.value && !vm.autoCheckIn.value)
            capture(test, "check-in-ui-manual-busy.png")
            manualPost.release.countDown()

            stage = "manual success notice and enabled action are visible"
            await { find(test, "签到成功") != null && !vm.checkingIn.value }
            check(vm.checkInStatus.value == "今日已签到" && posts.get() == beforeManual + 1)
            check(actionNode(test, "手动签到").isEnabled && !vm.autoCheckIn.value)
            capture(test, "check-in-ui-manual-success.png")

            stage = "manual already-signed feedback is visible without a second POST"
            val beforeSignedRead = reads.get()
            val beforeSignedPost = posts.get()
            click(test, "手动签到")
            await {
                reads.get() == beforeSignedRead + 1 && !vm.checkingIn.value &&
                    matches(test, "今日已签到").size >= 2
            }
            check(posts.get() == beforeSignedPost && !vm.autoCheckIn.value)
            capture(test, "check-in-ui-already-signed.png")

            stage = "manual rejection is visible in status and Snackbar"
            signed.set(false)
            reject.set(true)
            val beforeFailure = posts.get()
            click(test, "手动签到")
            await {
                !vm.checkingIn.value && vm.checkInStatus.value.startsWith("签到未确认：离线签到拒绝") &&
                    matches(test, vm.checkInStatus.value).size >= 2
            }
            check(posts.get() == beforeFailure + 1 && actionNode(test, "手动签到").isEnabled)
            check(!vm.autoCheckIn.value && !Store.autoCheckIn() && !Preview.enabled)
            capture(test, "check-in-ui-rejected.png")
            check(unknown.isEmpty())
            result.putString("stream", "PASS: real AppRoot Home automatic-success Snackbar; My manual button works with automation off; busy disabled button and progress semantics; repeated busy clicks add one POST; manual success, already-signed and rejected feedback visible in UI; all API calls terminally intercepted with production headers; reads=${reads.get()},posts=${posts.get()},calls=${calls.get()}\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; reads=${reads.get()},posts=${posts.get()},unexpected=${unknown.size}\n")
            result.putString("failureFrames", failure.stackTrace.take(5).joinToString {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            })
            if (fixtureStarted && activity != null) {
                result.putString("fixtureTree", nodes(root(test)).filter {
                    it.packageName?.toString() == test.targetContext.packageName
                }.take(60).joinToString("; ") {
                    "text=${it.text?.toString().orEmpty()},desc=${it.contentDescription?.toString().orEmpty()},enabled=${it.isEnabled}," +
                        "clickable=${it.isClickable},range=${it.rangeInfo != null}"
                })
                capture(test, "check-in-ui-failure.png")
            }
        } finally {
            gates.forEach { it.release.countDown() }
            test.runOnMainSync {
                activity?.let { screen ->
                    screen.setContent { }
                    screen.viewModelStore.clear()
                    screen.finish()
                }
            }
            if (fixtureStarted) {
                Preview.enabled = false
                Store.clearAccount()
                val editor = preferences.edit()
                if (originalAuto == null) editor.remove("autoCheckIn") else editor.putBoolean("autoCheckIn", originalAuto)
                if (originalStartup == null) editor.remove("startupPage") else editor.putString("startupPage", originalStartup)
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

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun matches(test: Instrumentation, text: String): List<AccessibilityNodeInfo> =
        nodes(root(test)).filter {
            it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
        }

    private fun find(test: Instrumentation, text: String): AccessibilityNodeInfo? = matches(test, text).firstOrNull()

    private fun actionParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable && !target.isCheckable) target = target.parent
        return target
    }

    private fun actionNode(test: Instrumentation, text: String): AccessibilityNodeInfo =
        matches(test, text).firstNotNullOfOrNull { actionParent(it) } ?: error("UI action unavailable")

    private fun click(test: Instrumentation, text: String) {
        check(actionNode(test, text).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync()
    }

    private fun reveal(test: Instrumentation, text: String) {
        repeat(8) {
            if (find(test, text) != null) return
            val scroll = nodes(root(test)).firstOrNull { it.isScrollable && it.isVisibleToUser }
                ?: error("Scrollable page unavailable")
            check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            test.waitForIdleSync()
            SystemClock.sleep(150)
        }
        error("UI action not visible")
    }

    private fun capture(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(test.targetContext.filesDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        check(condition()) { "Timed out" }
    }
}
