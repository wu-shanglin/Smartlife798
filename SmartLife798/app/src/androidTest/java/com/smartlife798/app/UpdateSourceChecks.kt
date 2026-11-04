package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.smartlife798.app.ui.AppUpdatePrompt
import com.smartlife798.app.ui.theme.SmartLifeTheme
import android.os.Bundle
import com.smartlife798.app.core.*
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Terminal transport only; no accounts, installation or business actions. */
object UpdateSourceChecks {
    fun run(test: Instrumentation) {
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        check(Store.token.isNullOrBlank())
        val oldSource = Store.updateSource
        val oldPolicy = Store.updatePolicyJson
        val field = AppUpdate::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(null)
        val downloadField = UpdatePackage::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalDownload = downloadField.get(null)
        val requests = AtomicInteger()
        val failedHost = AtomicReference("github.com")
        val attemptedHosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val downloadHost = AtomicReference("")
        val failAll = AtomicBoolean(false)
        var vm: MainViewModel? = null
        var activity: MainActivity? = null
        var stage = "setup"
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            fun policy(code: Int, minimum: Int = 1) =
                """{"versionCode":$code,"versionName":"99.0.0","minSupportedVersionCode":$minimum,"notes":"fixture","sha256":"${"a".repeat(64)}"}"""
            val json = AtomicReference(policy(BuildConfig.VERSION_CODE))
            field.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                attemptedHosts.add(chain.request().url.host)
                if (failAll.get() || chain.request().url.host == failedHost.get()) throw java.io.IOException()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(json.get().toResponseBody()).build()
            }.build())
            downloadField.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                downloadHost.set(chain.request().url.host)
                throw java.io.IOException()
            }.build())
            Store.updatePolicyJson = null
            Store.updateSource = UpdateSource.GITHUB
            test.runOnMainSync { vm = MainViewModel(test.targetContext.applicationContext as android.app.Application) }
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.runOnMainSync {
                val model = requireNotNull(vm)
                activity!!.setContent { SmartLifeTheme {
                    val current by model.updatePolicy.collectAsState()
                    val visible by model.showUpdate.collectAsState()
                    val download by model.updateDownload.collectAsState()
                    AppUpdatePrompt(model, current, visible, false, download,
                        onDismiss = model::dismissAppUpdate, onExit = {}, onRetry = {}, onError = {})
                } }
            }
            fun checkUpdate(manual: Boolean = true) {
                val before = requests.get()
                test.runOnMainSync {
                    if (!manual) MainViewModel::class.java.getDeclaredField("lastUpdateCheck").apply {
                        isAccessible = true
                        setLong(vm, 0L)
                    }
                    vm!!.checkAppUpdate(manual = manual)
                }
                repeat(100) {
                    test.waitForIdleSync()
                    if (!vm!!.checkingUpdate.value && requests.get() > before) return
                    Thread.sleep(50)
                }
                error("check timeout")
            }
            stage = "automatic fallback without a newer version is silent"
            checkUpdate(manual = false)
            check(attemptedHosts == listOf("github.com", "gh-proxy.org") && vm!!.sourceFallback.value == null)
            check(downloadHost.get().isEmpty() && Store.updatePolicyJson == null)
            stage = "automatic fallback refusal"
            json.set(policy(9999, 9999))
            checkUpdate(manual = false)
            check(vm!!.sourceFallback.value?.source == UpdateSource.GH_PROXY)
            check(vm!!.updatePolicy.value == null)
            click(test, "否")
            check(vm!!.sourceFallback.value == null && downloadHost.get().isEmpty() && Store.updatePolicyJson == null)
            stage = "automatic retry does not repeat rejected version"
            checkUpdate(manual = false)
            check(vm!!.sourceFallback.value == null && downloadHost.get().isEmpty())
            stage = "manual retry permits fallback accepted without preference mutation"
            checkUpdate()
            click(test, "是，临时切换")
            repeat(100) { if (downloadHost.get().isEmpty()) Thread.sleep(20) }
            check(downloadHost.get() == "gh-proxy.org")
            check(Store.updateSource == UpdateSource.GITHUB && Store.updatePolicyJson == null)
            check(vm!!.updatePolicy.value?.required(BuildConfig.VERSION_CODE) == false)
            stage = "source retained after account clear and total failure"
            test.runOnMainSync { vm!!.cancelUpdateDownload() }
            Thread.sleep(100)
            stage = "stale mirror cannot replace known required policy or start download"
            Store.updatePolicyJson = policy(9999, 9999)
            json.set(policy(9998))
            downloadHost.set("")
            test.runOnMainSync { vm = MainViewModel(test.targetContext.applicationContext as android.app.Application) }
            checkUpdate(manual = false)
            check(vm!!.sourceFallback.value == null && downloadHost.get().isEmpty())
            check(vm!!.updatePolicy.value?.required(BuildConfig.VERSION_CODE) == true)
            check(Store.updatePolicyJson == policy(9999, 9999))
            stage = "preferred mirror works without a fallback confirmation"
            Store.updatePolicyJson = null
            json.set(policy(BuildConfig.VERSION_CODE))
            test.runOnMainSync { vm = MainViewModel(test.targetContext.applicationContext as android.app.Application) }
            test.runOnMainSync { vm!!.setUpdateSource(UpdateSource.GH_PROXY) }
            attemptedHosts.clear()
            checkUpdate(manual = false)
            check(attemptedHosts == listOf("gh-proxy.org") && vm!!.sourceFallback.value == null)
            stage = "preferred mirror failure checks official source second"
            failedHost.set("gh-proxy.org")
            json.set(policy(9999))
            attemptedHosts.clear()
            checkUpdate(manual = false)
            check(attemptedHosts == listOf("gh-proxy.org", "github.com"))
            check(vm!!.sourceFallback.value?.source == UpdateSource.GITHUB)
            test.runOnMainSync { vm!!.answerSourceFallback(false) }
            stage = "source retained after account clear and total failure"
            Store.clearAccount()
            check(Store.updateSource == UpdateSource.GH_PROXY)
            failAll.set(true)
            checkUpdate()
            check(vm!!.sourceFallback.value == null)
            result.putString("stream", "PASS: silent current version, preferred source order in both directions, automatic fallback refusal and deduplication, manual retry and acceptance, temporary download routing, unchanged preference, stale mirror preserves required policy, mirror cannot force/cache policy, logout retention, all-sources failure\n")
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName} line ${e.stackTrace.firstOrNull { it.className.contains("UpdateSourceChecks") }?.lineNumber}\n")
        } finally {
            test.runOnMainSync { vm?.cancelUpdateDownload(); activity?.finish() }
            field.set(null, original)
            downloadField.set(null, originalDownload)
            Store.updateSource = oldSource
            Store.updatePolicyJson = oldPolicy
        }
        test.finish(code, result)
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }

    private fun click(test: Instrumentation, text: String) {
        repeat(100) {
            if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
            var node = test.uiAutomation.windows.flatMap { window -> nodes(window.root) }
                .firstOrNull { it.isVisibleToUser && it.text?.toString() == text }
            if (node != null) {
                while (node != null && !node.isClickable) node = node.parent
                check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                test.waitForIdleSync()
                return
            }
            Thread.sleep(50)
        }
        error("Missing action $text")
    }
}
