package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.*
import java.io.ByteArrayOutputStream
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
            fun clickAction(label: String) {
                await {
                    val root = test.uiAutomation.rootInActiveWindow
                    var button = nodes(root).firstOrNull { it.text?.toString() == label }
                    while (button != null && !button.isClickable) button = button.parent
                    val bounds = Rect()
                    val screen = Rect()
                    button?.getBoundsInScreen(bounds)
                    root?.getBoundsInScreen(screen)
                    if (button?.isEnabled == true && screen.contains(bounds.centerX(), bounds.centerY())) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                            "input tap ${bounds.centerX()} ${bounds.centerY()}"
                        )).use { it.readBytes() }
                        true
                    } else false
                }
            }
            stage = "captcha failure and retry"
            await { visibleText("图形验证码加载失败") }
            check(nodes(test.uiAutomation.rootInActiveWindow).count { it.text?.toString() == "重试" } == 1)
            check(!visibleText("换一张"))
            clickAction("重试")
            await { visibleText("换一张") && captchaRequests.get() == 2 }
            stage = "form order"
            val form = nodes(test.uiAutomation.rootInActiveWindow)
            val fields = form.filter { it.isEditable }.map { Rect().apply(it::getBoundsInScreen) }
            val send = Rect().apply { form.first { it.text?.toString() == "获取短信验证码" }.getBoundsInScreen(this) }
            check(fields.size == 3 && fields.map { it.width() }.distinct().size == 1)
            check(fields[0].bottom <= fields[1].top && fields[1].bottom <= send.top && send.bottom <= fields[2].top)
            stage = "fill phone and request SMS through fixture"
            enter(nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[0], "13800000000")
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.firstOrNull()?.text?.toString() == "13800000000" }
            enter(nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[1], "TEST")
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[1].text?.toString() == "TEST" }
            clickAction("获取短信验证码")
            await {
                nodes(test.uiAutomation.rootInActiveWindow).any {
                    it.text?.toString()?.endsWith("秒后重新获取") == true && it.isVisibleToUser
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
            stage = "login button"
            // Compose can report an off-screen node as visible; scroll to its actual bounds.
            repeat(3) {
                nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(250)
            }
            stage = "rejection feedback and non-overlapping update entry"
            val all = nodes(test.uiAutomation.rootInActiveWindow)
            val loginBounds = android.graphics.Rect()
            val updateBounds = android.graphics.Rect()
            all.first { it.text?.toString() == "登录" }.getBoundsInScreen(loginBounds)
            all.first { it.text?.toString() == "检查客户端更新" }.getBoundsInScreen(updateBounds)
            check(!android.graphics.Rect.intersects(loginBounds, updateBounds))
            clickAction("登录")
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
            await { missions.get() > 0 && nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString()?.contains("离线登录用户") == true } }
            check(logins.get() == 4 && smsRequests.get() == 1 && unexpectedWrites.get() == 0)
            result.putString("stream", "PASS: actual login removes LoginScreen; account initialization and check-in read complete afterward; separate update/login bounds, pending/rejected/missing-account/missing-token/success feedback; full-width form order, captcha retry and SMS countdown; four signed login attempts and one SMS request handled by terminal fixtures, no pay/device writes\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; classes=${nodes(test.uiAutomation.rootInActiveWindow).map { it.className }.distinct()},editable=${nodes(test.uiAutomation.rootInActiveWindow).count { it.isEditable }}; fields=${nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.map { "hint=${it.hintText},length=${it.text?.length}" }}; login=${logins.get()},missions=${missions.get()},unexpectedWrites=${unexpectedWrites.get()}\n")
        } finally {
            allowCheckInResponse.countDown()
            test.runOnMainSync { activity?.let { it.viewModelStore.clear(); it.finish() }; session.clear() }
            savedAutoCheckIn?.let { Store.saveAutoCheckIn(it) }
            field.set(null, original)
        }
        test.finish(code, result)
    }
    private fun captchaImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(144, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("TEST", 20f, 35f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 30f
        })
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; SystemClock.sleep(80) }
        error("Expected UI state not reached")
    }
}
