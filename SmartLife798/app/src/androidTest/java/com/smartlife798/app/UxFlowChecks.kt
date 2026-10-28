package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.ThemeMode
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.Store
import com.smartlife798.app.vm.MainViewModel

/** Offline, account-free checks; never logs in, pays, scans or starts a device. */
object UxFlowChecks {
    fun run(test: Instrumentation, settingsOnly: Boolean = false) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val result = Bundle()
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var code = Activity.RESULT_CANCELED
        var accountFree = false
        // Store is the only SharedPreferences owner, even in test fixtures.
        val original = Store.startupPage()
        val policy = Store.updatePolicyJson
        val protocol = Store.originalAppVersion
        val theme = Store.themeModeName()
        val dynamicColor = Store.dynamicColor()
        try {
            check(Store.token.isNullOrBlank())
            accountFree = true
            for (page in StartupPage.entries) {
                stage = "persist $page across account cleanup"
                Preview.enabled = false
                Store.saveStartupPage(page)
                Store.clearAccount()
                check(Store.startupPage() == page)
                stage = "launch $page"
                activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("preview", true)) as MainActivity
                await(test, if (page == StartupPage.HOME) "扫码即刻使用" else "我的设备")
                check(text(test).contains("演示模式"))
                stage = "open settings and retain the wallet metric"
                click(test, "我的")
                await(test, "36.50")
                stage = "reveal startup-page radio option"
                reveal(test, "启动页面")
                val startupChoice = if (page == StartupPage.HOME) "设备页" else "首页"
                reveal(test, startupChoice, radioOnly = true)
                stage = "settings remain on screen after startup radio change"
                click(test, startupChoice, radioOnly = true)
                await(test, "启动页面")
                test.runOnMainSync {
                    val vm = ViewModelProvider(activity!!)[MainViewModel::class.java]
                    check(vm.startupPage.value != page)
                }
                check(Store.startupPage() == page) // preview choice is in-memory only
                if (!settingsOnly) {
                    stage = "wallet open and return"
                    scrollToTop(test)
                    click(test, "钱包")
                    await(test, "钱包余额")
                    await(test, "充值金额")
                    click(test, "返回")
                    await(test, "36.50")
                    reveal(test, "启动页面")
                    stage = "tab navigation and detail return"
                    click(test, "设备")
                    await(test, "我的设备")
                    click(test, "3 栋 2 层淋浴机 02")
                    await(test, "当前模式")
                    test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    await(test, "我的设备")
                    stage = "search no-match and clear"
                    setSearch(test, "NO-SUCH-DEVICE")
                    await(test, "没有匹配的设备")
                    click(test, "清除筛选")
                    await(test, "3 栋 2 层淋浴机 02")
                    stage = "trimmed search"
                    setSearch(test, "  洗衣房  ")
                    await(test, "3 栋 2 层淋浴机 02", present = false)
                    await(test, "洗衣房 3 号机")
                    click(test, "清除搜索")
                    await(test, "3 栋 2 层淋浴机 02")
                }
                stage = "select settings after search"
                if (!settingsOnly) click(test, "我的")
                stage = "settings visible after search"
                reveal(test, "启动页面")
                await(test, "启动页面")
                test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                await(test, "启动页面", present = false)
                stage = "settings survive background"
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                    "am start -W -n com.smartlife798.app/.MainActivity --ez preview true --activity-single-top",
                )).use { it.readBytes() }
                await(test, "启动页面")
                stage = "settings survive recreation"
                val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    test.runOnMainSync { activity!!.recreate() }
                    activity = test.waitForMonitorWithTimeout(monitor, 10_000) as MainActivity
                } finally {
                    test.removeMonitor(monitor)
                }
                await(test, "启动页面")
                // Capture only preview data, with a stable original filename on export.
                if (page == StartupPage.DEVICES) {
                    test.waitForIdleSync()
                    test.uiAutomation.waitForIdle(500, 5_000)
                    test.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(test.targetContext.cacheDir, "ux-me.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    stage = "reveal appearance choices"
                    reveal(test, "跟随系统")
                    stage = "dark selection preview isolation"
                    click(test, "深色")
                    test.runOnMainSync { check(Appearance.mode.value == ThemeMode.DARK) }
                    check(Store.themeModeName() == theme)
                    stage = "light selection preview isolation"
                    click(test, "浅色")
                    test.runOnMainSync { check(Appearance.mode.value == ThemeMode.LIGHT) }
                    check(Store.themeModeName() == theme)
                    stage = "system selection preview isolation"
                    click(test, "跟随系统")
                    test.runOnMainSync { check(Appearance.mode.value == ThemeMode.SYSTEM) }
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        stage = "reveal dynamic color"
                        reveal(test, "动态取色")
                        stage = "dynamic color preview isolation"
                        click(test, "动态取色开关")
                        test.runOnMainSync { check(Appearance.dynamicColor.value != dynamicColor) }
                        check(Store.dynamicColor() == dynamicColor)
                        test.runOnMainSync { Appearance.setDynamicColor(dynamicColor) }
                    } else {
                        stage = "dynamic color unavailable before Android 12"
                        check(!text(test).contains("动态取色"))
                    }
                    test.uiAutomation.waitForIdle(1_000, 5_000)
                    val frames = java.util.concurrent.CountDownLatch(1)
                    test.runOnMainSync {
                        val view = activity!!.window.decorView
                        view.postOnAnimation { view.postOnAnimation { frames.countDown() } }
                    }
                    check(frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    test.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(test.targetContext.cacheDir, "ux-appearance.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                test.runOnMainSync { activity?.finish() }
                test.waitForIdleSync()
                activity = null
            }
            stage = "installation cache isolation"
            check(Store.updatePolicyJson == policy && Store.originalAppVersion == protocol)
            result.putString("stream", if (settingsOnly)
                "PASS: settings-only startup choices, retention, background/recreation, appearance selection/toggle and preview/cache isolation\n"
            else "PASS: both startup routes, account cleanup retention, preview preference isolation, no settings jump, wallet open/back, tabs/detail/back, search/clear, settings recreation, appearance selection/toggle and installation cache isolation\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            if (accountFree) test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(test.targetContext.cacheDir, "ux-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; source=${frame?.fileName}:${frame?.lineNumber}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = false
            if (accountFree) Store.saveStartupPage(original)
            if (accountFree) test.runOnMainSync { Appearance.restore() }
        }
        test.finish(code, result)
    }

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow
    }

    private fun text(test: Instrumentation): String = nodes(root(test))
        .filter { fullyVisible(test, it) }
        .joinToString(" ") { it.text?.toString().orEmpty() }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }

    private fun await(test: Instrumentation, expected: String, present: Boolean = true) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            if (text(test).contains(expected) == present) return
            Thread.sleep(100)
        }
        error("Expected UI absent")
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target
    }

    /** Compose may retain off-screen semantics in an eagerly composed scroll container. */
    private fun fullyVisible(test: Instrumentation, node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val area = android.graphics.Rect()
        node.getBoundsInScreen(area)
        if (area.isEmpty) return false
        val viewport = android.graphics.Rect()
        root(test)?.getBoundsInScreen(viewport) ?: return false
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.isScrollable) {
                val scrollArea = android.graphics.Rect()
                ancestor.getBoundsInScreen(scrollArea)
                if (!viewport.intersect(scrollArea)) return false
            }
            ancestor = ancestor.parent
        }
        return viewport.contains(area)
    }

    private fun isRadio(node: AccessibilityNodeInfo): Boolean = clickable(node)?.let {
        it.isCheckable || it.className?.toString() == "android.widget.RadioButton"
    } == true

    private fun click(test: Instrumentation, label: String, radioOnly: Boolean = false) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            val node = nodes(root(test)).firstOrNull {
                (it.text?.toString() == label || it.contentDescription?.toString() == label) &&
                    fullyVisible(test, it) && (!radioOnly || isRadio(it))
            }
            if (node != null) {
                val target = clickable(node)
                if (target != null && target.isEnabled && fullyVisible(test, target) &&
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync()
                    return
                }
            }
            Thread.sleep(100)
        }
        error("Target unavailable")
    }

    private fun scrollToTop(test: Instrumentation) {
        repeat(12) {
            val scroller = nodes(root(test)).firstOrNull { it.isScrollable } ?: return
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            test.uiAutomation.waitForIdle(300, 5_000)
        }
    }

    private fun setSearch(test: Instrumentation, query: String) {
        val field = nodes(root(test)).first { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        test.waitForIdleSync()
    }

    private fun reveal(test: Instrumentation, label: String, radioOnly: Boolean = false) {
        repeat(12) {
            val matching = nodes(root(test)).filter {
                (it.text?.toString()?.contains(label) == true ||
                    it.contentDescription?.toString()?.contains(label) == true) &&
                    (!radioOnly || isRadio(it))
            }
            if (matching.any { node ->
                    fullyVisible(test, node) && (!radioOnly || clickable(node)?.let {
                        it.isEnabled && fullyVisible(test, it)
                    } == true)
                }) {
                test.uiAutomation.waitForIdle(300, 5_000)
                return
            }
            val candidate = matching.firstOrNull()
            val target = candidate?.let { if (radioOnly) clickable(it) else it }
            if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true) {
                test.uiAutomation.waitForIdle(300, 5_000)
                return@repeat
            }
            // Small drags avoid skipping a compact settings group with a whole-page scroll.
            val bounds = android.graphics.Rect()
            root(test)!!.getBoundsInScreen(bounds)
            val x = bounds.centerX()
            val from = bounds.top + (bounds.height() * 0.72f).toInt()
            val to = from - (bounds.height() * 0.18f).toInt()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                "input swipe $x $from $x $to 600",
            )).use { it.readBytes() }
            test.uiAutomation.waitForIdle(300, 5_000)
        }
        error("Option not visible")
    }
}
