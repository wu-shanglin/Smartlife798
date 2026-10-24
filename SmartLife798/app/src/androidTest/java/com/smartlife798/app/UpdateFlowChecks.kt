package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.OriginalAppVersion
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.UpdatePolicy
import com.smartlife798.app.vm.MainViewModel
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking

/** Synthetic policy/UI checks require an account-free emulator, with its network disabled. */
object UpdateFlowChecks {
    fun run(instrumentation: Instrumentation, mode: String) {
        instrumentation.runOnMainSync { ServiceLocator.init(instrumentation.targetContext) }
        val result = Bundle()
        var stage = "initialization"
        var activity: Activity? = null
        var resultCode = Activity.RESULT_CANCELED
        val cachedPolicy = Store.updatePolicyJson
        val cachedOriginal = Store.originalAppVersion
        try {
            check(Store.token.isNullOrBlank())
            when (mode) {
                "original-live" -> {
                    stage = "public distribution discovery"
                    val version = runBlocking { OriginalAppVersion.fetch() }
                    OriginalAppVersion.accept(version)
                    check(Store.originalAppVersion == OriginalAppVersion.current.value)
                    OriginalAppVersion.restore(Store.originalAppVersion)
                    check(OriginalAppVersion.current.value == OriginalAppVersion.select(version))
                    result.putString("stream", "PASS: public original distribution version $version, installation cache and restore; no business requests\n")
                }
                "update-ui" -> {
                    stage = "cached startup policy"
                    val latest = nextVersion(3)
                    val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 3, latest, 1, "最新说明", "a".repeat(64),
                        listOf(
                            com.smartlife798.app.core.UpdateRelease(nextVersion(1), "FIRST-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease(latest, "THIRD-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease(BuildConfig.VERSION_NAME, "ALREADY-INSTALLED"),
                            com.smartlife798.app.core.UpdateRelease(nextVersion(2), "SECOND-SKIPPED-RELEASE"),
                        ))
                    Store.updatePolicyJson = Gson().toJson(policy)
                    activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    stage = "first resume shows all three skipped releases"
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                    var text = ""
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.waitForIdleSync()
                        text = nodeText(instrumentation.uiAutomation.rootInActiveWindow)
                        if (text.contains("THIRD-SKIPPED-RELEASE")) break
                        Thread.sleep(100)
                    }
                    check((1..3).all { text.contains(nextVersion(it)) })
                    check(text.contains("FIRST-SKIPPED-RELEASE") && text.contains("SECOND-SKIPPED-RELEASE"))
                    check(!text.contains("ALREADY-INSTALLED"))
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(instrumentation.targetContext.cacheDir, "update-history-ui.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    stage = "dismiss action"
                    check(clickText(instrumentation.uiAutomation.rootInActiveWindow, "稍后更新"))
                    stage = "dismissed prompt"
                    awaitText(instrumentation, "THIRD-SKIPPED-RELEASE", false)
                    // Wait for the offline request timeout before the manual check (single-flight guard).
                    Thread.sleep(16_000)
                    stage = "manual check entry"
                    check(clickText(instrumentation.uiAutomation.rootInActiveWindow, "检查客户端更新"))
                    stage = "manual prompt reopen"
                    awaitText(instrumentation, "THIRD-SKIPPED-RELEASE", true)
                    stage = "ordinary back dismisses without leaving app"
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitText(instrumentation, "THIRD-SKIPPED-RELEASE", false)
                    check(activity?.isFinishing == false)
                    result.putString("stream", "PASS: first-resume cached update prompt, dynamic cross-version notes, installed-version exclusion, dismiss/manual reopen and ordinary back stays in app\n")
                }
                "forced-exit" -> {
                    check(Store.active() == null && Store.pendingStart() == null)
                    val latest = nextVersion(1)
                    val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 1, latest,
                        BuildConfig.VERSION_CODE + 1, "离线强制更新退出测试", "a".repeat(64))
                    val json = Gson().toJson(policy)
                    Store.updatePolicyJson = json
                    fun launch(preview: Boolean = false): MainActivity =
                        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("preview", preview)) as MainActivity
                    fun awaitRemoved(screen: MainActivity) {
                        val manager = instrumentation.targetContext.getSystemService(android.app.ActivityManager::class.java)
                        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            val removed = manager.appTasks.none {
                                it.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
                            }
                            if ((screen.isFinishing || screen.isDestroyed) && removed) return
                            Thread.sleep(100)
                        }
                        error("Mandatory back did not remove the app task")
                    }
                    stage = "mandatory outside tap keeps restriction"
                    val first = launch().also { activity = it }
                    awaitText(instrumentation, "需要更新至 $latest", true)
                    check(!nodeText(instrumentation.uiAutomation.rootInActiveWindow).contains("稍后更新"))
                    instrumentation.uiAutomation.executeShellCommand("input tap 2 400").use { fd ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes()
                    }
                    awaitText(instrumentation, "需要更新至 $latest", true)
                    check(!first.isFinishing)
                    stage = "mandatory back exits and preserves cached policy"
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitRemoved(first)
                    check(Store.updatePolicyJson == json)
                    stage = "relaunch remains mandatory and busy back also exits"
                    val second = launch().also { activity = it }
                    awaitText(instrumentation, "需要更新至 $latest", true)
                    instrumentation.runOnMainSync {
                        val vm = androidx.lifecycle.ViewModelProvider(second)[MainViewModel::class.java]
                        // Busy UI fixture only: no download, network or account side effect.
                        val field = MainViewModel::class.java.getDeclaredField("_updateDownload").apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val flow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<MainViewModel.UpdateDownload>
                        flow.value = MainViewModel.UpdateDownload(busy = true, percent = 33)
                    }
                    awaitText(instrumentation, "下载进度 33%", true)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitRemoved(second)
                    check(Store.updatePolicyJson == json)
                    stage = "preview ignores mandatory policy without modifying cache"
                    activity = launch(preview = true)
                    awaitText(instrumentation, "演示模式", true)
                    check(!nodeText(instrumentation.uiAutomation.rootInActiveWindow).contains("需要更新至 $latest"))
                    check(Store.updatePolicyJson == json)
                    result.putString("stream", "PASS: outside tap stays mandatory, back removes task, relaunch preserves restriction, busy back exits, ordinary policy cache and preview isolation\n")
                }
                "preview" -> {
                    stage = "preview refresh isolation"
                    instrumentation.runOnMainSync {
                        val vm = MainViewModel(instrumentation.targetContext.applicationContext as android.app.Application)
                        vm.startPreview()
                        vm.checkAppUpdate(manual = true)
                        check(!vm.checkingUpdate.value)
                        check(!vm.showUpdate.value)
                    }
                    check(Store.updatePolicyJson == cachedPolicy && Store.originalAppVersion == cachedOriginal)
                    try { runBlocking { OriginalAppVersion.fetch() }; error("Preview fetched version") }
                    catch (_: IllegalStateException) { }
                    result.putString("stream", "PASS: preview rejects update/version refresh and leaves installation caches unchanged\n")
                }
                else -> error("Unknown test mode")
            }
            resultCode = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            Preview.enabled = false
            Store.updatePolicyJson = cachedPolicy
            Store.originalAppVersion = cachedOriginal
            OriginalAppVersion.restore(cachedOriginal)
        }
        instrumentation.finish(resultCode, result)
    }

    private fun nextVersion(offset: Int): String = BuildConfig.VERSION_NAME.split('.').map(String::toInt)
        .let { "${it[0]}.${it[1]}.${it[2] + offset}" }

    private fun nodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return node.text?.toString().orEmpty() + (0 until node.childCount).joinToString(" ") { nodeText(node.getChild(it)) }
    }

    private fun awaitText(instrumentation: Instrumentation, text: String, present: Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (nodeText(instrumentation.uiAutomation.rootInActiveWindow).contains(text) == present) return
            Thread.sleep(100)
        }
        error("UI did not reach expected state")
    }

    private fun clickText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target = target.parent
            }
        }
        return (0 until node.childCount).any { clickText(node.getChild(it), text) }
    }
}
