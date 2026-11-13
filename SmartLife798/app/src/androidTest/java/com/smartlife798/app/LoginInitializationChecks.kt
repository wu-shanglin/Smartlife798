package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Click the real login button: its composition disappears before account initialization completes. */
object LoginInitializationChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var activity: MainActivity? = null
        var code = Activity.RESULT_CANCELED
        var stage = "account guard"
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = Api.client
        val missions = AtomicInteger()
        val allowCheckInResponse = CountDownLatch(1)
        val captchaRequests = AtomicInteger()
        val smsRequests = AtomicInteger()
        val logins = AtomicInteger()
        val unexpectedWrites = AtomicInteger()
        var savedAutoCheckIn: Boolean? = null
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank())
            savedAutoCheckIn = Store.autoCheckIn()
            Store.saveAutoCheckIn(true)
            val client = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }.addInterceptor { chain ->
                val req = chain.request()
                val data = when (req.url.encodedPath) {
                    "/api/v1/captcha/" -> {
                        val image = captchaRequests.incrementAndGet() > 1
                        return@addInterceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                            .code(if (image) 200 else 503).message("offline captcha")
                            .body(if (image) captchaImage().toResponseBody("image/png".toMediaType()) else "".toResponseBody())
                            .build()
                    }
                    "/api/v1/acc/login/code" -> {
                        val body = Buffer().also { checkNotNull(req.body).writeTo(it) }.readUtf8()
                        check(req.method == "POST" && req.header("Authorization") == null)
                        check(com.google.gson.JsonParser.parseString(body).asJsonObject["authCode"].asString == "TEST")
                        smsRequests.incrementAndGet()
                        "null"
                    }
                    "/api/v1/acc/login" -> {
                        val now = System.currentTimeMillis()
                        val body = Buffer().also { checkNotNull(req.body).writeTo(it) }.readUtf8()
                        val sign = req.url.queryParameter("sign")
                        check(req.method == "POST" && req.header("Authorization") == null)
                        check(req.header("VersionCode") == "3.1.10" && req.header("user-agent") == "Android_ilife798_3.1.10")
                        check(body == """{"un":"13800000000","authCode":"000000"}""")
                        check(req.url.queryParameterNames == setOf("sign"))
                        check(sign == LoginSign.sign("13800000000", "000000", now) ||
                            sign == LoginSign.sign("13800000000", "000000", now - 30_000))
                        Thread.sleep(700)
                        when (logins.incrementAndGet()) {
                            1 -> return@addInterceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("offline")
                                .body("""{"code":-1,"msg":"短信验证码错误"}""".toResponseBody()).build()
                            2 -> "null"
                            3 -> """{"al":{"uid":"offline-login-user"}}"""
                            else -> """{"al":{"token":"offline-login-token","uid":"offline-login-user"}}"""
                        }
                    }
                    "/api/v1/ui/app/master" -> { Thread.sleep(200); """{"account":{"id":"offline-login-user","name":"离线登录用户"},"favos":[]}""" }
                    "/api/v1/acc/score/mission-lst" -> {
                        missions.incrementAndGet()
                        // Check login feedback before a later check-in notice can replace it.
                        check(allowCheckInResponse.await(12, TimeUnit.SECONDS))
                        """{"accScoreRsp":{"validScore":"1","daily":{"ltime":${System.currentTimeMillis()},"week":127}}}"""
                    }
                    "/api/v1/bill/lst-owner" -> "[]"
                    "/api/v1/acc/wallet/owner" -> """{"aw":{"total":20.0}}"""
                    else -> { if (req.method != "GET") unexpectedWrites.incrementAndGet(); "null" }
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("offline")
                    .body("""{"code":0,"time":${System.currentTimeMillis()},"data":$data}""".toResponseBody()).build()
            }.build()
            field.set(null, client)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            stage = "editable fields"
            await { nodes(test.uiAutomation.rootInActiveWindow).count { it.isEditable } >= 3 }
            fun enter(node: AccessibilityNodeInfo, value: String) {
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
            }
            fun visibleText(value: String) = nodes(test.uiAutomation.rootInActiveWindow).any {
                it.text?.toString() == value && it.isVisibleToUser
            }
            fun clickAction(label: String, byDescription: Boolean = false) {
                await {
                    val root = test.uiAutomation.rootInActiveWindow
                    val visibleNodes = nodes(root)
                    var button = visibleNodes.firstOrNull {
                        val name = if (byDescription) it.contentDescription else it.text
                        name?.toString() == label
                    }
                    while (button != null && !button.isClickable) button = button.parent
                    val bounds = Rect()
                    val screen = Rect()
                    button?.getBoundsInScreen(bounds)
                    root?.getBoundsInScreen(screen)
                    if (button?.isVisibleToUser != true || !screen.contains(bounds.centerX(), bounds.centerY())) {
                        visibleNodes.firstOrNull { it.isScrollable }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        false
                    } else if (button.isEnabled) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                            "input tap ${bounds.centerX()} ${bounds.centerY()}"
                        )).use { it.readBytes() }
                        true
                    } else false
                }
            }
            stage = "captcha failure and retry"
            await { visibleText("重试") }
            check(nodes(test.uiAutomation.rootInActiveWindow).count { it.text?.toString() == "重试" } == 1)
            clickAction("重试")
            await {
                captchaRequests.get() == 2 && nodes(test.uiAutomation.rootInActiveWindow).any {
                    it.contentDescription?.toString() == "图形验证码" && it.isVisibleToUser
                }
            }
            stage = "tap captcha to refresh"
            clickAction("图形验证码", byDescription = true)
            await {
                captchaRequests.get() == 3 && nodes(test.uiAutomation.rootInActiveWindow).any {
                    it.contentDescription?.toString() == "图形验证码" && it.isVisibleToUser
                }
            }
            stage = "form order"
            val form = nodes(test.uiAutomation.rootInActiveWindow)
            val fields = form.filter { it.isEditable }.map { Rect().apply(it::getBoundsInScreen) }
            val send = Rect().apply { form.first { it.text?.toString() == "获取验证码" }.getBoundsInScreen(this) }
            val image = Rect().apply {
                form.first { it.contentDescription?.toString() == "图形验证码" }.getBoundsInScreen(this)
            }
            check(fields.size == 3 && fields.none { it.isEmpty })
            check(fields.map { it.left }.distinct().size == 1 && fields[1].right == fields[2].right)
            check(fields[0].bottom <= fields[1].top && fields[1].bottom <= fields[2].top)
            check(fields[1].right <= image.left && fields[1].top < image.bottom && image.top < fields[1].bottom)
            check(fields[2].right <= send.left && fields[2].top < send.bottom && send.top < fields[2].bottom)
            for (index in listOf(0, 1, 2, 0)) {
                stage = "focus field $index"
                val fieldNode = nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[index]
                val target = Rect().apply(fieldNode::getBoundsInScreen)
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        target.centerX().toFloat(), target.centerY().toFloat(), 0)
                    try { test.sendPointerSync(event) } finally { event.recycle() }
                }
                await {
                    nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.getOrNull(index)?.isFocused == true
                }
                // Measure the field itself. The keyboard can temporarily crop the
                // bottom field's screen bounds while it animates into the viewport.
                test.runOnMainSync {
                    val window = checkNotNull(activity).window
                    val ime = window.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ime.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                }
                SystemClock.sleep(350)
                val focused = nodes(test.uiAutomation.rootInActiveWindow).first { it.isEditable && it.isFocused }
                val bounds = Rect().apply(focused::getBoundsInScreen)
                check(bounds.height() == fields[index].height())
            }
            stage = "fill phone and request SMS through fixture"
            enter(nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[0], "138 0000 0000")
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.firstOrNull()?.text?.toString() == "13800000000" }
            enter(nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[1], "TEST")
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[1].text?.toString() == "TEST" }
            stage = "tap SMS action"
            clickAction("获取验证码")
            stage = "SMS countdown"
            await {
                nodes(test.uiAutomation.rootInActiveWindow).any {
                    it.text?.toString()?.matches(Regex("[0-9]+s")) == true && it.isVisibleToUser
                }
            }
            check(smsRequests.get() == 1)
            // Re-read after Compose replaces the semantics nodes for the phone edit.
            await {
                val sms = nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.getOrNull(2)
                if (sms?.isVisibleToUser != true) {
                    nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                } else {
                    enter(sms, "000000")
                    true
                }
            }
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.getOrNull(2)?.text?.toString() == "000000" }
            stage = "non-overlapping update entry"
            val all = nodes(test.uiAutomation.rootInActiveWindow)
            val loginBounds = android.graphics.Rect()
            val updateBounds = android.graphics.Rect()
            all.first { it.text?.toString() == "登录" }.getBoundsInScreen(loginBounds)
            all.first { it.text?.toString() == "检查客户端更新" }.getBoundsInScreen(updateBounds)
            check(!android.graphics.Rect.intersects(loginBounds, updateBounds))
            stage = "submit login"
            clickAction("登录")
            stage = "rejection feedback"
            await { visibleText("登录中…") }
            await { visibleText("短信验证码错误") }
            check(logins.get() == 1 && Store.token.isNullOrBlank())
            stage = "missing account response remains on login"
            clickAction("登录")
            await { visibleText("登录响应缺少账号信息，请重试") }
            check(Store.token.isNullOrBlank())
            stage = "missing token response remains on login"
            clickAction("登录")
            await { visibleText("登录响应缺少令牌") }
            check(Store.token.isNullOrBlank())
            stage = "successful login feedback"
            clickAction("登录")
            await { visibleText("登录成功") }
            allowCheckInResponse.countDown()
            stage = "owned initialization after composition removal"
            lateinit var vm: MainViewModel
            test.runOnMainSync { vm = ViewModelProvider(checkNotNull(activity))[MainViewModel::class.java] }
            await {
                missions.get() > 0 && vm.profile.value?.name == "离线登录用户" &&
                    nodes(test.uiAutomation.rootInActiveWindow).none { it.isEditable }
            }
            check(logins.get() == 4 && smsRequests.get() == 1 && unexpectedWrites.get() == 0)
            result.putString("stream", "PASS: stable field heights across focus changes, formatted phone paste, captcha retry, SMS countdown, and four signed login attempts; profile initialization and check-in read complete after LoginScreen is removed. Terminal fixtures only.\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull()}; classes=${nodes(test.uiAutomation.rootInActiveWindow).map { it.className }.distinct()},editable=${nodes(test.uiAutomation.rootInActiveWindow).count { it.isEditable }}; fields=${nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.map { "hint=${it.hintText},length=${it.text?.length}" }}; login=${logins.get()},sms=${smsRequests.get()},missions=${missions.get()},unexpectedWrites=${unexpectedWrites.get()}\n")
        } finally {
            allowCheckInResponse.countDown()
            test.runOnMainSync { activity?.let { it.viewModelStore.clear(); it.finish() }; session.clear() }
            savedAutoCheckIn?.let { Store.saveAutoCheckIn(it) }
            field.set(null, original)
        }
        test.finish(code, result)
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; SystemClock.sleep(80) }
        error("Expected UI state not reached")
    }
}
