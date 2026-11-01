package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.*
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

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
        val logins = AtomicInteger()
        val unexpectedWrites = AtomicInteger()
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank())
            val client = original.newBuilder().apply { interceptors().removeAll { it is OfflineTransport.Router } }.addInterceptor { chain ->
                val req = chain.request()
                val data = when (req.url.encodedPath) {
                    "/api/v1/acc/login" -> { logins.incrementAndGet(); """{"al":{"token":"offline-login-token","uid":"offline-login-user"}}""" }
                    "/api/v1/ui/app/master" -> { Thread.sleep(200); """{"account":{"id":"offline-login-user","name":"离线登录用户"},"favos":[]}""" }
                    "/api/v1/acc/score/mission-lst" -> { missions.incrementAndGet(); """{"accScoreRsp":{"validScore":"1","daily":{"ltime":${System.currentTimeMillis()},"week":127}}}""" }
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
            stage = "fill fields"
            enter(nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }[0], "13800000000")
            await { nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.firstOrNull()?.text?.toString() == "13800000000" }
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
            await {
                val root = test.uiAutomation.rootInActiveWindow
                var button = nodes(root).firstOrNull { it.text?.toString() == "登录" }
                while (button != null && !button.isClickable) button = button.parent
                val bounds = android.graphics.Rect()
                val screen = android.graphics.Rect()
                button?.getBoundsInScreen(bounds)
                root?.getBoundsInScreen(screen)
                if (button?.isEnabled == true && screen.contains(bounds.centerX(), bounds.centerY())) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                        "input tap ${bounds.centerX()} ${bounds.centerY()}"
                    )).use { it.readBytes() }
                    true
                } else false
            }
            stage = "owned initialization after composition removal"
            await { missions.get() > 0 && nodes(test.uiAutomation.rootInActiveWindow).any { it.text?.toString()?.contains("离线登录用户") == true } }
            check(logins.get() == 1 && unexpectedWrites.get() == 0)
            result.putString("stream", "PASS: actual login removes LoginScreen; account initialization and check-in read complete afterward; one login, zero SMS/pay/device writes; terminal fixtures\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; classes=${nodes(test.uiAutomation.rootInActiveWindow).map { it.className }.distinct()},editable=${nodes(test.uiAutomation.rootInActiveWindow).count { it.isEditable }}; fields=${nodes(test.uiAutomation.rootInActiveWindow).filter { it.isEditable }.map { "hint=${it.hintText},length=${it.text?.length}" }}; login=${logins.get()},missions=${missions.get()},unexpectedWrites=${unexpectedWrites.get()}\n")
        } finally {
            test.runOnMainSync { activity?.let { it.viewModelStore.clear(); it.finish() }; session.clear() }
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
