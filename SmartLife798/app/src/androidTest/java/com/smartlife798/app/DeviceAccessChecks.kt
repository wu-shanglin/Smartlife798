@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.ui.DeviceDetailScreen
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** All traffic terminates in this interceptor. No credentials, payments or physical commands. */
object DeviceAccessChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "empty account guard"
        var fixtureStarted = false
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val originalUid = session.uid
        val owner = AtomicReference("other")
        val servedId = AtomicReference("access-fixture")
        val status = AtomicInteger(99)
        val commands = AtomicInteger()
        val reads = AtomicInteger()
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            fixtureStarted = true
            clientField.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val data = when {
                    path.endsWith("/dev/start") || path.endsWith("/dev/end") -> {
                        commands.incrementAndGet()
                        error("forbidden fixture command")
                    }
                    path.endsWith("/dev/home/1") -> {
                        reads.incrementAndGet()
                        val user = owner.get().takeIf { it.isNotEmpty() }?.let { """, "user":{"id":"$it"}""" }.orEmpty()
                        """{"device":{"id":"${servedId.get()}","name":"占用隔离设备","status":1,"bm":{"dtype":8},"gene":{"status":${status.get()}}},"payItems":[{"type":21}]$user}"""
                    }
                    path.endsWith("/dev/status") -> """{"device":{"id":"access-fixture","status":1,"gene":{"status":${status.get()}}}}"""
                    path.endsWith("/master") -> """{"account":{"id":"me","name":"离线夹具"},"favos":[]}"""
                    else -> "[]"
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("""{"code":0,"data":$data}""".toResponseBody()).build()
            }.build())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("preview", true)) as MainActivity
            val screen = activity
            lateinit var vm: MainViewModel
            var scroll = LazyListState()
            var render = 0
            fun show(auto: Boolean = false) = test.runOnMainSync {
                Preview.enabled = false
                session.token = "offline-access-fixture"
                session.uid = "me"
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                val entry = ++render
                scroll = LazyListState()
                screen.setContent { SmartLifeTheme { key(entry) {
                    DeviceDetailScreen(vm, "access-fixture", onBack = {}, onOpenOrders = {}, autoStart = auto,
                        listState = scroll)
                } } }
            }
            stage = "foreign owner and stale standby disable both commands and automatic entry"
            show(auto = true)
            await(test) { text(test).contains("他人使用中，暂不可用") }
            check(!enabled(test, "他人使用中，暂不可用"))
            val stale = Device(id = "access-fixture", status = 1, bm = DeviceModel(dtype = 8), gene = Gene(status = 99))
            runBlocking {
                check((vm.startDevice(stale, 21, "") as Res.Fail).code == ErrorCodes.DEVICE_ACCESS_CHANGED)
                check((vm.stopDevice(stale) as Res.Fail).code == ErrorCodes.DEVICE_ACCESS_CHANGED)
            }
            Thread.sleep(500)
            check(commands.get() == 0 && vm.active.value == null)
            stage = "foreign owner removes only a matching stale bookmark without stopping it"
            @Suppress("UNCHECKED_CAST")
            val active = MainViewModel::class.java.getDeclaredField("_active").apply { isAccessible = true }
                .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ActiveUsage?>
            val old = ActiveUsage("access-fixture", "旧夹具书签", 8, 1L, 21)
            test.runOnMainSync { Store.setActive(old); active.value = old }
            runBlocking { vm.loadDevice("access-fixture") }
            check(active.value == null && Store.active() == null && commands.get() == 0)
            val different = old.copy(deviceId = "different-device")
            test.runOnMainSync { Store.setActive(different); active.value = different }
            runBlocking { vm.loadDevice("access-fixture") }
            check(active.value == different && Store.active()?.deviceId == "different-device")
            servedId.set("foreign-response")
            runBlocking { check(vm.loadDevice("access-fixture") is Res.Fail) }
            check(active.value == different && commands.get() == 0)
            servedId.set("access-fixture")
            test.runOnMainSync { Store.setActive(null); active.value = null }

            stage = "own running device permits ending, unknown running device is never adopted"
            owner.set("me"); status.set(10); show()
            await(test) { enabled(test, "结束使用并结算") }
            owner.set(""); show()
            await(test) { text(test).contains("暂无法确认使用者") }
            check(!enabled(test, "设备使用中，暂不可用"))
            check(vm.active.value == null)

            stage = "account change between display and tap is rejected before start and refreshes notice"
            status.set(99); owner.set(""); show()
            await(test) { enabled(test, "立即使用") }
            owner.set("other")
            click(test, "立即使用")
            await(test) { text(test).contains("他人使用中，暂不可用") }
            check(commands.get() == 0 && reads.get() >= 5)
            check(Store.active() == null && Store.pendingStart() == null)
            stage = "ownership release refreshes without reopening"
            owner.set("")
            await(test) { enabled(test, "立即使用") }
            stage = "scroll updates before finger release"
            val bounds = android.graphics.Rect()
            screen.window.decorView.getWindowVisibleDisplayFrame(bounds)
            val x = bounds.width() * 0.85f
            val y = bounds.height() * 0.72f
            val down = SystemClock.uptimeMillis()
            fun motion(action: Int, targetY: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, targetY, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            motion(MotionEvent.ACTION_DOWN, y)
            try {
                for (i in 1..8) { motion(MotionEvent.ACTION_MOVE, y - i * 24f); Thread.sleep(16) }
                await(test) { (scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0) }
            } finally { motion(MotionEvent.ACTION_UP, y - 192f) }
            check((scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0) && commands.get() == 0)
            result.putString("stream", "PASS: foreign/unknown ownership disables controls and automatic entry; stale standby/changed owner send zero commands; own running controls and automatic release refresh work; real drag updates scroll before UP; terminal fixtures only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = false
            if (fixtureStarted) Store.clearAccount()
            session.token = originalToken
            session.uid = originalUid
            clientField.set(null, originalClient)
        }
        test.finish(code, result)
    }

    private fun nodes(test: Instrumentation): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        return walk(test.uiAutomation.rootInActiveWindow)
    }
    private fun text(test: Instrumentation) = nodes(test).joinToString(" ") { it.text?.toString().orEmpty() }
    private fun enabled(test: Instrumentation, label: String): Boolean {
        var node = nodes(test).firstOrNull { it.text?.toString() == label } ?: return false
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.isEnabled && node.isClickable
    }
    private fun click(test: Instrumentation, label: String) {
        var node = nodes(test).first { it.text?.toString() == label }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(test: Instrumentation, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(100) }
        error("UI condition timed out")
    }
}
