package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.ui.*
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** Only the isolated APK, synthetic account and terminal interceptors. */
object FeatureGuideChecks {
    fun run(test: Instrumentation, orderOnly: Boolean = false) {
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val result = Bundle()
        var stage = "guard"
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        check(Store.token.isNullOrBlank())
        val prefsField = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val prefs = prefsField.get(null) as android.content.SharedPreferences
        val saved = prefs.all.toMap()
        val client = Api.client
        val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        var status = BillStatus.UNPAID
        var closeCode = 0
        var confirmCancellation = true
        var vm: MainViewModel? = null
        try {
            prefs.edit().clear().commit()
            test.runOnMainSync { Appearance.restore() }
            Store.initializeGuide()
            check(!Store.guideBasicsDone())
            val fixture = client.newBuilder().apply { interceptors().clear() }.addInterceptor { chain ->
                val request = chain.request()
                var responseCode = 0
                val data = when (request.url.encodedPath) {
                    "/api/v1/bill/view-full" -> { reads.incrementAndGet(); """{"bill":{"id":"fixture-bill","status":$status}}""" }
                    "/api/v1/trans/close" -> {
                        check(request.url.queryParameter("id") == "fixture-bill")
                        closes.incrementAndGet(); Thread.sleep(200)
                        responseCode = closeCode
                        if (closeCode == 0 && confirmCancellation) status = BillStatus.CANCELLED
                        "null"
                    }
                    "/api/v1/bill/lst-owner" -> """[{"id":"fixture-bill","status":$status}]"""
                    "/api/v1/bill/pay/channels" -> """{"channels":[]}"""
                    else -> { responseCode = -1; "null" }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"code":$responseCode,"msg":"fixture refusal","data":$data}""".toResponseBody()).build()
            }.build()
            field.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.runOnMainSync {
                vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
            }
            if (!orderOnly) {
                stage = "first-use visible"
                await(test, "轻盈体验，从登录开始")
                snapshot(test, "guide-first.png")
                stage = "first-use navigation"
                click(test, "下一步")
                stage = "second page visible"
                await(test, "打开应用，碰一碰直达设备")
                stage = "back button"
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await(test, "轻盈体验，从登录开始")
                check(Store.guideBasicsDone() && Store.guideSeen() == setOf("welcome:2"))
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "打开应用，碰一碰直达设备")
                click(test, "下一步")
                await(test, "从你习惯的页面开始")
                click(test, "开始使用")
                check(Store.guideBasicsDone() && FeatureGuide.pending(Store.guideSeen()).isEmpty())
                await(test, "手机号登录")
                stage = "account retention and unified replay"
                Store.clearAccount()
                check(Store.guideBasicsDone() && Store.guideSeen().size == FeatureGuide.topics.size)
                test.runOnMainSync { Appearance.setMode(ThemeMode.DARK); vm!!.replayGuides() }
                await(test, "使用指南")
                await(test, "轻盈体验，从登录开始")
                snapshot(test, "guide-replay-dark.png")
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "打开应用，碰一碰直达设备")
                click(test, "下一步")
                await(test, "从你习惯的页面开始")
                click(test, "开始使用")
                test.runOnMainSync { Appearance.setMode(ThemeMode.LIGHT) }
                stage = "developer reset guard"
                val seenBefore = Store.guideSeen()
                test.runOnMainSync { vm!!.resetGuides() }
                check(Store.guideSeen() == seenBefore && vm!!.guideDeck.value == null)
                stage = "developer reset confirmation and layout"
                test.runOnMainSync {
                    vm!!.setDeveloperToolsEnabled(true)
                    activity!!.setContent { SmartLifeTheme {
                        val batch = vm!!.guideDeck.collectAsState().value
                        if (batch != null) FeatureGuideScreen(batch, onRead = { vm!!.readGuideCard(batch.id, it) },
                            onFinish = { vm!!.finishGuide(batch.id) }, onExit = { vm!!.dismissGuide(batch.id) })
                        else DeveloperToolsScreen(true, onOpenNfc = {}, onCopyToken = {}, onResetGuide = vm!!::resetGuides, onDisable = {}, onBack = {})
                    } }
                }
                await(test, "重置引导卡片")
                snapshot(test, "developer-tools.png")
                click(test, "重置引导卡片")
                await(test, "重置引导卡片？")
                click(test, "取消")
                check(Store.guideSeen() == seenBefore)
                click(test, "重置引导卡片")
                click(test, "重置并查看")
                check(Store.guideSeen().isEmpty() && !Store.guideBasicsDone())
                check(Store.developerToolsEnabled() && Store.themeModeName() == ThemeMode.LIGHT.name)
                check(vm!!.guideDeck.value?.topics == FeatureGuide.current())
                await(test, "轻盈体验，从登录开始")
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await(test, "重置引导卡片")
                check(Store.guideSeen().isEmpty() && !Store.guideBasicsDone())
                test.runOnMainSync { vm!!.replayGuides() }
                await(test, "轻盈体验，从登录开始")
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "打开应用，碰一碰直达设备")
                click(test, "下一步")
                await(test, "从你习惯的页面开始")
                click(test, "开始使用")
                await(test, "重置引导卡片")
            }
            stage = "read-only state blocks cancellation"
            session.token = "fixture-token"
            status = BillStatus.CONFIRMING
            runBlocking { check(vm!!.cancelBill("fixture-bill") is Res.Fail) }
            check(closes.get() == 0)
            stage = "server refusal preserved"
            status = BillStatus.UNPAID; closeCode = -1
            runBlocking { check(vm!!.cancelBill("fixture-bill") is Res.Fail) }
            check(closes.get() == 1 && status == BillStatus.UNPAID)
            stage = "accepted but unconfirmed is not cancelled"
            closeCode = 0; confirmCancellation = false
            runBlocking { check(vm!!.cancelBill("fixture-bill") is Res.Fail) }
            check(closes.get() == 2)
            stage = "cancel UI confirmation and authoritative readback"
            confirmCancellation = true
            test.runOnMainSync { activity!!.setContent { SmartLifeTheme {
                OrdersScreen(vm!!, onOpenDevice = { _, _ -> })
            } } }
            click(test, "设备消费")
            await(test, "账单详情")
            click(test, "取消订单")
            await(test, "取消这笔未付订单？")
            snapshot(test, "payment-cancel.png")
            click(test, "保留订单")
            check(closes.get() == 2)
            click(test, "取消订单")
            click(test, "确认取消")
            val until = android.os.SystemClock.elapsedRealtime() + 5000
            while (status != BillStatus.CANCELLED && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            check(closes.get() == 3 && status == BillStatus.CANCELLED)
            check(reads.get() >= 6)
            if (!orderOnly) {
                stage = "old-install migration"
                prefs.edit().remove("guideStarted").remove("guideBasicsDone").remove("guideSeen").commit()
                Store.saveStartupPage(StartupPage.DEVICES)
                Store.initializeGuide()
                check(Store.guideBasicsDone() && FeatureGuide.pending(Store.guideSeen()).size == FeatureGuide.topics.size)
                stage = "upgrade uses the same fullscreen cards without basics"
                test.runOnMainSync {
                    activity!!.viewModelStore.clear()
                    vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
                    val batch = checkNotNull(vm!!.guideDeck.value)
                    check(batch.topics == FeatureGuide.current())
                    activity!!.setContent { SmartLifeTheme {
                        FeatureGuideScreen(batch, onRead = { vm!!.readGuideCard(batch.id, it) },
                            onFinish = { vm!!.finishGuide(batch.id) }, onExit = {})
                    } }
                }
                await(test, "了解新功能")
                await(test, "轻盈体验，从登录开始")
                snapshot(test, "guide-update.png")
                click(test, "下一步")
                await(test, "打开应用，碰一碰直达设备")
                check(Store.guideSeen() == setOf("welcome:2"))
            }
            result.putString("stream", if (orderOnly) "PASS: blocked confirming; server refusal; unconfirmed close; order detail cancel UI and authoritative readback; terminal fixtures only\n" else "PASS: first-use next/back/no-skip; retained learning; unified dark replay; developer reset guard/confirmation; old-install migration and fullscreen incremental batch; blocked confirming; server refusal; unconfirmed close; order detail cancel UI and authoritative readback; terminal fixtures only\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            snapshot(test, "guide-failure.png")
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull { it.className.contains("FeatureGuideChecks") }?.lineNumber}; closes=${closes.get()}, reads=${reads.get()}\n")
        } finally {
            test.runOnMainSync { activity?.viewModelStore?.clear(); activity?.finish(); session.clear() }
            field.set(null, client)
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
            test.runOnMainSync { Appearance.restore() }
        }
        test.finish(code, result)
    }
    private fun snapshot(test: Instrumentation, name: String) {
        test.uiAutomation.waitForIdle(500, 5000)
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(test.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun find(test: Instrumentation, text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.windows.flatMap { nodes(it.root) }
            .firstOrNull { it.text?.toString() == text && it.isVisibleToUser }
    }
    private fun await(test: Instrumentation, text: String) {
        repeat(80) { if (find(test, text) != null) return; Thread.sleep(100) }; error("missing UI label")
    }
    private fun click(test: Instrumentation, text: String) {
        await(test, text)
        var node = find(test, text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        test.waitForIdleSync()
    }
}
