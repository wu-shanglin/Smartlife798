package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.AlipaySdk
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

object AlipayLoginChecks {
    fun run(test: Instrumentation) {
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = Api.client
        val exchanges = AtomicInteger()
        var activity: MainActivity? = null
        var status = Activity.RESULT_CANCELED
        val report = Bundle()
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank() && !AlipaySdk.isInstalled(test.targetContext))
            val transport = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }
                .addInterceptor { chain ->
                    val req = chain.request()
                    val response = when (req.url.encodedPath) {
                        "/api/v1/acc/login" -> {
                            check(req.method == "POST" && req.header("Authorization") == null)
                            check(req.header("VersionCode") == "3.1.10")
                            check(req.url.queryParameterNames == setOf("sign") && req.url.queryParameter("sign") == "")
                            val body = Buffer().also { checkNotNull(req.body).writeTo(it) }.readUtf8()
                            check(body == """{"openCode":"alipay-fixture-code"}""")
                            when (exchanges.incrementAndGet()) {
                                1 -> """{"code":-98,"data":{"al":{"oid":"fixture-oid"}}}"""
                                2 -> """{"code":0,"data":{"al":{"uid":"alipay-fixture-user"}}}"""
                                else -> """{"code":0,"data":{"al":{"uid":"alipay-fixture-user","token":"alipay-fixture-token"}}}"""
                            }
                        }
                        else -> {
                            check(req.method == "GET")
                            """{"code":0,"data":null}"""
                        }
                    }
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                        .message("offline").body(response.toResponseBody()).build()
                }.build()
            clientField.set(null, transport)
            val login = test.startActivitySync(
                Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            activity = login
            val sms = awaitNode(test, "登录")
            val alipay = awaitNode(test, "支付宝授权登录")
            val smsBounds = Rect().apply(sms::getBoundsInScreen)
            val alipayBounds = Rect().apply(alipay::getBoundsInScreen)
            check(smsBounds.left == alipayBounds.left && smsBounds.right == alipayBounds.right)
            check(smsBounds.bottom <= alipayBounds.top && alipayBounds.height() > 0)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            login.openFileOutput("alipay-login.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            check(alipay.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode(test, "请先安装支付宝，或使用手机号登录")
            check(exchanges.get() == 0)
            lateinit var vm: MainViewModel
            test.runOnMainSync { vm = ViewModelProvider(login)[MainViewModel::class.java] }
            runBlocking {
                withTimeout(12_000) {
                    check(vm.loginWithAlipay { Res.Fail(-1, "cancelled fixture") } is Res.Fail)
                    check(exchanges.get() == 0)
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val pending = async {
                        vm.loginWithAlipay {
                            started.complete(Unit)
                            release.await()
                            Res.Ok("alipay-fixture-code")
                        }
                    }
                    started.await()
                    check(vm.loginMethod.value == MainViewModel.LoginMethod.ALIPAY)
                    check(vm.login("13800000000", "000000") is Res.Fail)
                    check(vm.loginWithAlipay { error("duplicate SDK call") } is Res.Fail)
                    release.complete(Unit)
                    check((pending.await() as? Res.Fail)?.code == -98)
                    check(!vm.loggedIn.value && Store.token == null)
                    check(vm.loginWithAlipay { Res.Ok("alipay-fixture-code") } is Res.Fail)
                    check(!vm.loggedIn.value && Store.token == null)
                    check(vm.loginWithAlipay { Res.Ok("alipay-fixture-code") } is Res.Ok)
                    check(vm.loggedIn.value && session.uid == "alipay-fixture-user" && Store.phone == null)
                    check(exchanges.get() == 3 && vm.loginMethod.value == null)
                }
            }
            status = Activity.RESULT_OK
            report.putString("stream", "PASS: login layout and missing app feedback, cancelled authorization, mutually exclusive login, exact anonymous exchange, phone fallback, missing token and successful session. Terminal fixtures only.\n")
        } catch (failure: Exception) {
            report.putString("stream", "FAIL: ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull()}; exchanges=${exchanges.get()}\n")
        } finally {
            test.runOnMainSync { activity?.let { it.viewModelStore.clear(); it.finish() }; session.clear() }
            clientField.set(null, original)
        }
        test.finish(status, report)
    }

    private fun awaitNode(test: Instrumentation, label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val nodes = descendants(test.uiAutomation.rootInActiveWindow).toList()
            val item = nodes.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (item != null) {
                return if (item.isClickable) item else item.parent?.takeIf { it.isClickable } ?: item
            }
            nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(120)
        }
        error("Missing login UI state")
    }

    private fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (index in 0 until node.childCount) yieldAll(descendants(node.getChild(index)))
    }
}
