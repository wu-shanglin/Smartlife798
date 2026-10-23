package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
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
                    val latest = "1.8.3"
                    val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 3, latest, 1, "最新说明", "a".repeat(64),
                        listOf(
                            com.smartlife798.app.core.UpdateRelease("1.8.1", "FIRST-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease("1.8.3", "THIRD-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease(BuildConfig.VERSION_NAME, "ALREADY-INSTALLED"),
                            com.smartlife798.app.core.UpdateRelease("1.8.2", "SECOND-SKIPPED-RELEASE"),
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
                    check(text.contains("1.8.3") && text.contains("1.8.2") && text.contains("1.8.1"))
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
                    result.putString("stream", "PASS: first-resume cached update prompt, all three skipped release notes, installed-version exclusion, dismiss and manual reopen\n")
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
