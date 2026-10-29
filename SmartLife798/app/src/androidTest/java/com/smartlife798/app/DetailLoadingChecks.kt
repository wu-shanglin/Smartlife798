package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.vm.MainViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Full navigation with delayed, fully intercepted reads; no real account or commands. */
object DetailLoadingChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = Api.client
        val originalToken = session.token
        val originalUid = session.uid
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var replaced = false
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            val sample = Preview.devices.first()
            val fixture = OkHttpClient.Builder().addInterceptor { chain ->
                val body = when {
                    chain.request().url.encodedPath.endsWith("/dev/home/1") -> {
                        reads.incrementAndGet()
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                        """{"code":0,"data":${Gson().toJson(Preview.detail(sample.id))}}"""
                    }
                    chain.request().url.encodedPath.endsWith("/master") ->
                        """{"code":0,"data":{"favos":${Gson().toJson(Preview.devices)}}}"""
                    else -> """{"code":0,"data":[]}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            field.set(null, fixture)
            replaced = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            stage = "open source list in preview"
            // A selected tab no longer exposes a redundant click action in Compose 1.10.
            // A Devices startup preference already satisfies this navigation precondition.
            await(test) {
                val current = root(test)
                nodes(current).any { it.text?.toString() == "我的设备" } || click(current, "设备")
            }
            stage = "source device rows visible"
            await(test) { nodes(root(test)).any { it.text?.toString() == sample.name } }
            // Let source refresh settle before testing the destination's own navigation tick.
            Thread.sleep(800)
            test.runOnMainSync {
                Preview.enabled = false
                session.token = "offline-detail-fixture"
                session.uid = "preview-user"
            }
            lateinit var vm: MainViewModel
            test.runOnMainSync { vm = ViewModelProvider(activity!!)[MainViewModel::class.java] }
            val before = vm.refreshTick.value
            stage = "request begins before navigation settles"
            check(click(root(test), sample.name.orEmpty()))
            check(entered.await(5, TimeUnit.SECONDS))
            stage = "navigation tick does not restart slow first read"
            await(test) { vm.refreshTick.value > before }
            test.runOnMainSync { vm.bumpRefresh() }
            Thread.sleep(500)
            check(reads.get() == 1)
            release.countDown()
            await(test) { nodes(root(test)).any { it.text?.toString() == "当前模式" } }
            Thread.sleep(400)
            check(reads.get() == 1)
            stage = "later refresh remains active"
            test.runOnMainSync { vm.bumpRefresh() }
            await(test) { reads.get() == 2 }
            stage = "back and reopen performs a new read"
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await(test) {
                val current = nodes(root(test))
                current.any { it.text?.toString() == "我的设备" && it.isVisibleToUser } &&
                    current.none { it.contentDescription?.toString() == "返回" && it.isVisibleToUser }
            }
            check(click(root(test), sample.name.orEmpty()))
            await(test) { reads.get() >= 3 }
            await(test) { nodes(root(test)).any { it.text?.toString() == "当前模式" } }
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            result.putString("stream", "PASS: actual card navigation starts read during entry, overlapping navigation/refresh ticks keep one slow request, later refresh and back/reopen remain active; offline fixtures only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}, reads=${reads.get()}\n")
        } finally {
            release.countDown()
            test.runOnMainSync { activity?.finish() }
            test.waitForIdleSync()
            if (replaced) field.set(null, original)
            session.token = originalToken
            session.uid = originalUid
            Preview.enabled = false
        }
        test.finish(code, result)
    }

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun click(node: AccessibilityNodeInfo?, text: String): Boolean {
        for (match in nodes(node).filter { it.text?.toString() == text && it.isVisibleToUser }) {
            var target: AccessibilityNodeInfo? = match
            while (target != null) {
                if (target.isClickable && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = target.parent
            }
        }
        return false
    }

    private fun await(test: Instrumentation, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(80)
        }
        error("UI did not reach expected state")
    }
}
