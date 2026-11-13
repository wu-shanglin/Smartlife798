package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.ui.*
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
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
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val bill = """{"id":"fixture-bill","status":1,"payment":1.0,"prds":[{"name":"充值账单夹具"}]}"""
        var vm: MainViewModel? = null
        try {
            prefs.edit().clear().commit()
            test.runOnMainSync { Appearance.restore() }
            Store.initializeGuide()
            check(!Store.guideBasicsDone())
            val fixture = client.newBuilder().apply { interceptors().clear() }.addInterceptor { chain ->
                val request = chain.request()
                if (request.method != "GET" || request.url.encodedPath.startsWith("/api/v1/trans/") ||
                    request.url.encodedPath.startsWith("/api/v1/ord/")) writes.incrementAndGet()
                var responseCode = 0
                val data = when (request.url.encodedPath) {
                    "/api/v1/bill/view-full" -> { reads.incrementAndGet(); """{"bill":$bill}""" }
                    "/api/v1/bill/lst-owner" -> "[$bill]"
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
                await(test, "登陆后使用")
                snapshot(test, "guide-first.png")
                val (welcome, nfc, startup) = FeatureGuide.current()
                val welcomeTitle = bounds(test, welcome.title)
                val welcomeBody = bounds(test, welcome.body)
                val welcomeAction = bounds(test, "下一步")
                stage = "first-use navigation"
                click(test, "下一步")
                stage = "second page visible"
                await(test, "碰一碰使用设备")
                snapshot(test, "guide-nfc.png")
                stage = "NFC step matches first step alignment and shows compatibility note"
                check(bounds(test, nfc.title).top == welcomeTitle.top)
                check(bounds(test, nfc.body).top == welcomeBody.top)
                check(bounds(test, "下一步").top == welcomeAction.top)
                check(nfc.body.contains("保持本应用在前台") && nfc.body.contains("暂不支持支付宝碰一碰"))
                val note = "仅支持慧生活"
                await(test, note)
                if (test.targetContext.resources.configuration.fontScale >= 1.6f) {
                    stage = "large text scroll keeps navigation in place"
                    val nextBounds = bounds(test, "下一步")
                    val previousBounds = bounds(test, "上一步")
                    val noteBounds = bounds(test, note)
                    val x = noteBounds.centerX()
                    val from = (noteBounds.bottom + nextBounds.top) / 2
                    val to = bounds(test, "碰一碰使用设备").centerY()
                    ParcelFileDescriptor.AutoCloseInputStream(
                        test.uiAutomation.executeShellCommand("input swipe $x $from $x $to 350"),
                    ).use { it.readBytes() }
                    snapshot(test, "guide-nfc-scrolled.png")
                    check(bounds(test, note).top < noteBounds.top)
                    check(bounds(test, "下一步") == nextBounds && bounds(test, "上一步") == previousBounds)
                }
                stage = "previous step button"
                click(test, "上一步")
                await(test, "登陆后使用")
                check(Store.guideBasicsDone() && Store.guideSeen() == setOf("welcome:2"))
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "碰一碰使用设备")
                stage = "system back returns to previous step"
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await(test, "登陆后使用")
                click(test, "下一步")
                await(test, "碰一碰使用设备")
                stage = "finish first-use guide"
                click(test, "下一步")
                await(test, "选择启动页面")
                snapshot(test, "guide-startup.png")
                stage = "startup step matches first step alignment"
                check(bounds(test, startup.title).top == welcomeTitle.top)
                check(bounds(test, startup.body).top == welcomeBody.top)
                check(bounds(test, "开始使用").top == welcomeAction.top)
                click(test, "开始使用")
                check(Store.guideBasicsDone() && FeatureGuide.pending(Store.guideSeen()).isEmpty())
                await(test, "手机号登录")
                stage = "account retention and unified replay"
                Store.clearAccount()
                check(Store.guideBasicsDone() && Store.guideSeen().size == FeatureGuide.topics.size)
                test.runOnMainSync { Appearance.setMode(ThemeMode.DARK); vm!!.replayGuides() }
                await(test, "使用指南")
                await(test, "登陆后使用")
                snapshot(test, "guide-replay-dark.png")
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "碰一碰使用设备")
                click(test, "下一步")
                await(test, "选择启动页面")
                click(test, "完成")
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
                await(test, "登陆后使用")
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await(test, "重置引导卡片")
                check(Store.guideSeen().isEmpty() && !Store.guideBasicsDone())
                test.runOnMainSync { vm!!.replayGuides() }
                await(test, "登陆后使用")
                check(find(test, "跳过") == null)
                click(test, "下一步")
                await(test, "碰一碰使用设备")
                click(test, "下一步")
                await(test, "选择启动页面")
                click(test, "完成")
                await(test, "重置引导卡片")
            }
            stage = "defer payment preserves the unpaid recharge bill without a command"
            session.token = "fixture-token"
            test.runOnMainSync { activity!!.setContent { SmartLifeTheme {
                OrdersScreen(vm!!, onOpenDevice = { _, _ -> })
            } } }
            repeat(2) {
                click(test, "充值账单夹具")
                await(test, "账单详情")
                await(test, "暂不支付")
                check(find(test, "取消订单") == null && find(test, "已取消") == null)
                check(find(test, "去支付") != null)
                val before = reads.get()
                click(test, "暂不支付")
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                while (find(test, "账单详情") != null && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                check(find(test, "账单详情") == null)
                await(test, "充值账单夹具")
                check(find(test, "未付款") != null && find(test, "已取消") == null)
                check(reads.get() == before && writes.get() == 0)
            }
            check(reads.get() == 2)
            if (!orderOnly) {
                stage = "old-install migration"
                prefs.edit().remove("guideStarted").remove("guideBasicsDone").remove("guideSeen").commit()
                Store.saveStartupPage(StartupPage.DEVICES)
                Store.initializeGuide()
                check(Store.guideBasicsDone() && FeatureGuide.pending(Store.guideSeen()).size == FeatureGuide.topics.size)
                stage = "upgrade uses the same fullscreen cards for unread topics"
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
                await(test, "登陆后使用")
                snapshot(test, "guide-update.png")
                click(test, "下一步")
                await(test, "碰一碰使用设备")
                check(Store.guideSeen() == setOf("welcome:2"))
                click(test, "下一步")
                await(test, "选择启动页面")
                click(test, "完成")
                check(vm!!.guideDeck.value == null && FeatureGuide.pending(Store.guideSeen()).isEmpty())
            }
            result.putString("stream", if (orderOnly) "PASS: defer payment closes detail, preserves unpaid recharge bill and permits reopening; no close/cancel/prepay/confirm commands; terminal fixtures only\n" else "PASS: first-use next/back/no-skip; retained learning; unified dark replay; developer reset guard/confirmation; old-install migration and fullscreen incremental batch; defer payment preserves unpaid recharge bill, reopening succeeds, zero business commands; terminal fixtures only\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            snapshot(test, "guide-failure.png")
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull { it.className.contains("FeatureGuideChecks") }?.lineNumber}; writes=${writes.get()}, reads=${reads.get()}\n")
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
        // Accessibility can become idle before the visual-only entry animation settles.
        Thread.sleep(1_000)
        test.uiAutomation.waitForIdle(500, 5000)
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(test.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
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
    private fun bounds(test: Instrumentation, text: String): Rect =
        Rect().also { checkNotNull(find(test, text)).getBoundsInScreen(it) }

    private fun click(test: Instrumentation, text: String) {
        await(test, text)
        var node = find(test, text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        test.waitForIdleSync()
    }
}
