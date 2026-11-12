package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.*
import com.smartlife798.app.vm.MainViewModel

/** Capture the real navigation in light/dark themes, using only the runner's terminal fixtures. */
object VisualAlignmentChecks {
    fun run(test: Instrumentation) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated account guard"
        var activity: MainActivity? = null
        var started = false
        val originalTheme = Store.themeModeName()
        val originalDynamic = Store.dynamicColor()
        val originalStartup = Store.startupPage()
        val originalDeveloper = Store.developerToolsEnabled()
        val originalAuto = Store.autoCheckIn()
        val captures = mutableListOf<String>()
        fun capture(name: String) {
            SystemClock.sleep(500)
            val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            java.io.File(test.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            captures += name
        }
        fun open(label: String) { await { click(test, label) } }
        fun back() {
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            SystemClock.sleep(300)
        }
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            started = true
            Store.saveStartupPage(StartupPage.HOME)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            lateinit var vm: MainViewModel
            test.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                stage = "main pages $theme"
                test.runOnMainSync { Appearance.setMode(theme); Appearance.setDynamicColor(false) }
                stage = "home navigation $theme"
                if (theme != ThemeMode.LIGHT) open("首页")
                stage = "home content $theme"
                await { find(test, "扫码使用") != null }
                if (theme == ThemeMode.LIGHT) {
                    stage = "pinned home card retains its height"
                    await { vm.favorites.value.size >= 2 }
                    val first = vm.favorites.value.first()
                    test.runOnMainSync { if (first.id in vm.pinned.value) vm.togglePinned(first.id) }
                    SystemClock.sleep(300)
                    val width = test.targetContext.resources.displayMetrics.widthPixels
                    val height = test.targetContext.resources.displayMetrics.heightPixels
                    val density = test.targetContext.resources.displayMetrics.density
                    val scanCard = cardBounds(test, "扫码使用")
                    check(scanCard.height() >= (144 * density).toInt()) { "Scan entry is too short: $scanCard" }
                    shell(test, "input swipe ${width / 2} ${height * 3 / 4} ${width / 2} ${height / 2} 250")
                    SystemClock.sleep(300)
                    val before = cardBounds(test, first.name)
                    check(before.top > 40 && before.bottom < height - 120) { "Pin card not fully visible: $before, screen=$height" }
                    check(before.left == scanCard.left && before.width() >= (174 * density).toInt()) {
                        "Favorite card is too narrow or misaligned: $before, scan=$scanCard"
                    }
                    val fontScale = test.targetContext.resources.configuration.fontScale
                    if (fontScale <= 1f && scanCard.width() >= (288 * density).toInt()) {
                        check(scanCard.right - before.right >= (96 * density).toInt()) {
                            "Favorite card leaves too little room for the next device: $before, scan=$scanCard"
                        }
                    }
                    test.runOnMainSync { vm.togglePinned(first.id) }
                    await { first.id in vm.pinned.value && find(test, "置顶") != null }
                    val after = cardBounds(test, first.name)
                    check(before.height() == after.height()) { "Pin changed card height: $before -> $after" }
                    shell(test, "input swipe ${width / 2} ${height / 3} ${width / 2} ${height * 4 / 5} 250")
                    SystemClock.sleep(300)
                }
                capture("home-${theme.name.lowercase()}")
                open("设备")
                await { find(test, "3 栋 2 层淋浴机 02") != null }
                capture("devices-${theme.name.lowercase()}")
                open("3 栋 2 层淋浴机 02")
                await { find(test, "当前模式") != null }
                await { find(test, "已收藏") != null && find(test, "已置顶") != null }
                check(find(test, "订单") == null) { "Device detail exposes the orders shortcut" }
                capture("detail-${theme.name.lowercase()}")
                back()
                open("订单")
                await { find(test, "订单与记录") != null }
                capture("orders-${theme.name.lowercase()}")
                open("我的")
                await { find(test, "修改昵称") != null }
                capture("me-${theme.name.lowercase()}")
                stage = "update button $theme"
                await {
                    val button = find(test, "检查更新")
                    if (button == null) {
                        nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable && it.isVisibleToUser }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        false
                    } else true
                }
                capture("update-${theme.name.lowercase()}")
                repeat(6) {
                    nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable && it.isVisibleToUser }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    SystemClock.sleep(100)
                }
                stage = "wallet navigation $theme"
                open("钱包")
                await { find(test, "充值金额") != null }
                capture("wallet-${theme.name.lowercase()}")
                back()
            }
            test.runOnMainSync { Appearance.setMode(ThemeMode.LIGHT); vm.setDeveloperToolsEnabled(true) }
            stage = "developer tools and NFC"
            open("开发者工具")
            await { find(test, "探索与调试") != null }
            capture("developer-tools")
            open("NFC 采集")
            await { find(test, "开始采集") != null }
            capture("nfc-capture")
            open("返回开发者工具")
            await { find(test, "返回我的") != null }
            open("返回我的")
            await { find(test, "我的") != null && find(test, "返回我的") == null }
            stage = "feature guide"
            test.runOnMainSync { vm.replayGuides() }
            await { find(test, "下一步") != null }
            capture("feature-guide")
            val cards = vm.guideDeck.value?.topics?.size ?: error("guide deck missing")
            repeat(cards - 1) { open("下一步"); SystemClock.sleep(400) }
            open("开始使用")
            await { vm.guideDeck.value == null }
            stage = "scanner upper edge and manual input"
            open("首页")
            await { find(test, "扫码使用") != null }
            SystemClock.sleep(500)
            val density = test.targetContext.resources.displayMetrics.density
            val edgeInset = (8 * density).toInt()
            val scanCard = cardBounds(test, "扫码使用")
            shell(test, "input tap ${scanCard.centerX()} ${scanCard.top + edgeInset}")
            await { find(test, "手动输入") != null }
            capture("scan")
            open("手动输入")
            await { find(test, "查询设备") != null }
            capture("manual-input")
            back()
            await { find(test, "手动输入") != null }
            back()
            await { find(test, "扫码使用") != null }
            stage = "scanner lower edge and surrounding gap"
            SystemClock.sleep(500)
            val returnedScanCard = cardBounds(test, "扫码使用")
            shell(test, "input tap ${returnedScanCard.centerX()} ${returnedScanCard.bottom + edgeInset}")
            SystemClock.sleep(300)
            check(find(test, "扫码使用") != null && find(test, "手动输入") == null)
            shell(test, "input tap ${returnedScanCard.centerX()} ${returnedScanCard.bottom - edgeInset}")
            await { find(test, "手动输入") != null }
            back()
            await { find(test, "扫码使用") != null }
            stage = "login"
            test.runOnMainSync { vm.logout() }
            await {
                find(test, "手机号登录") != null && nodes(test.uiAutomation.rootInActiveWindow).any {
                    it.contentDescription?.toString() == "图形验证码" && it.isVisibleToUser
                }
            }
            capture("login")
            test.runOnMainSync { Appearance.setMode(ThemeMode.DARK) }
            capture("login-dark")
            result.putString("stream", "PASS: real AppRoot screenshots ${captures.joinToString()}; terminal transport, no writes or NFC capture\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            capture("visual-failure")
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}:${error.message}; captured=${captures.joinToString()}; visible=" +
                nodes(test.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" | ") + "\n")
        } finally {
            test.runOnMainSync {
                activity?.let { it.setContent { }; it.viewModelStore.clear(); it.finish() }
                if (started) {
                    session.clear()
                    Appearance.setMode(ThemeMode.fromName(originalTheme))
                    Appearance.setDynamicColor(originalDynamic)
                    Store.saveStartupPage(originalStartup)
                    Store.saveDeveloperToolsEnabled(originalDeveloper)
                    Store.saveAutoCheckIn(originalAuto)
                }
            }
            if (started) {
                val field = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
                check((field.get(null) as android.content.SharedPreferences).edit().commit())
            }
        }
        test.finish(code, result)
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun find(test: Instrumentation, label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        val matches = nodes(test.uiAutomation.rootInActiveWindow).filter {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }
        return if (label in setOf("首页", "设备", "订单", "我的", "扫码")) matches.lastOrNull() else matches.firstOrNull()
    }
    private fun click(test: Instrumentation, label: String): Boolean {
        var node = find(test, label)
        if (node == null) {
            val scroll = nodes(test.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable && it.isVisibleToUser }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            return false
        }
        while (!node!!.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun cardBounds(test: Instrumentation, label: String): Rect {
        var node = find(test, label) ?: error("card missing")
        while (!node.isClickable) node = node.parent ?: error("card action missing")
        return Rect().also { node.getBoundsInScreen(it) }
    }
    private fun shell(test: Instrumentation, command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        test.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Expected UI state not reached")
    }
}
