package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartlife798.app.core.OriginalAppVersion
import com.smartlife798.app.core.AppUpdate
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.UpdatePackage
import com.smartlife798.app.core.UpdatePolicy
import com.smartlife798.app.vm.MainViewModel
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic policy/UI checks require an account-free emulator, with its network disabled. */
object UpdateFlowChecks {
    fun run(instrumentation: Instrumentation, mode: String, expectedHash: String) {
        instrumentation.runOnMainSync { ServiceLocator.init(instrumentation.targetContext) }
        val result = Bundle()
        var stage = "initialization"
        var activity: Activity? = null
        var resultCode = Activity.RESULT_CANCELED
        val cachedPolicy = Store.updatePolicyJson
        val cachedOriginal = Store.originalAppVersion
        val autoCheckUpdate = Store.autoCheckUpdate
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
                    stage = "prepare signed offline update fixture"
                    val latest = nextVersion(3)
                    val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 3, latest, 1, "最新说明", expectedHash,
                        listOf(
                            com.smartlife798.app.core.UpdateRelease(nextVersion(1), "FIRST-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease(latest, "THIRD-SKIPPED-RELEASE"),
                            com.smartlife798.app.core.UpdateRelease(BuildConfig.VERSION_NAME, "ALREADY-INSTALLED"),
                            com.smartlife798.app.core.UpdateRelease(nextVersion(2), "SECOND-SKIPPED-RELEASE"),
                        ))
                    checkUpdateUi(instrumentation, policy) { currentStage ->
                        stage = currentStage
                    }
                    result.putString("stream", "PASS: silent prefetch until verified, cross-version notes, defer without installer, disabled automatic checks across task restart, manual check reuses cached APK, explicit update launches installer intent, back dismisses\n")
                }
                "forced-exit" -> {
                    check(Store.active() == null && Store.pendingStart() == null)
                    val latest = nextVersion(1)
                    val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 1, latest,
                        BuildConfig.VERSION_CODE + 1, "离线强制更新退出测试", "a".repeat(64))
                    val json = Gson().toJson(policy)
                    Store.updatePolicyJson = json
                    Store.autoCheckUpdate = false
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
                    check(!nodeText(instrumentation.uiAutomation.rootInActiveWindow).contains("暂不更新"))
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
                    stage = "legacy preview extra cannot bypass mandatory update"
                    activity = launch(preview = true)
                    awaitText(instrumentation, "需要更新至 $latest", true)
                    check(!nodeText(instrumentation.uiAutomation.rootInActiveWindow).contains("演示模式"))
                    check(Store.updatePolicyJson == json)
                    result.putString("stream", "PASS: mandatory policy survives task exit, download busy exit and legacy intent extra; no demo bypass\n")
                }
                else -> error("Unknown test mode")
            }
            resultCode = Activity.RESULT_OK
        } catch (error: Exception) {
            val frame = error.stackTrace.firstOrNull { it.className == UpdateFlowChecks::class.java.name }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName} line ${frame?.lineNumber}\n")
        } finally {
            Store.updatePolicyJson = cachedPolicy
            Store.autoCheckUpdate = autoCheckUpdate
            Store.originalAppVersion = cachedOriginal
            OriginalAppVersion.restore(cachedOriginal)
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            val field = Store::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val prefs = field.get(null) as android.content.SharedPreferences
            check(prefs.edit().commit())
        }
        instrumentation.finish(resultCode, result)
    }

    private fun checkUpdateUi(test: Instrumentation, policy: UpdatePolicy, stage: (String) -> Unit) {
        val context = test.targetContext
        val fixture = File(context.filesDir, "update-fixture.apk")
        check(fixture.isFile && policy.sha256.matches(Regex("[0-9a-f]{64}")))
        check(UpdatePackage.canInstall(context)) { "Grant install-source permission to the offline fixture before running" }
        val monitor = test.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("content")
            addDataType("application/vnd.android.package-archive")
        }, null, true)
        val metadata = AppUpdate::class.java.getDeclaredField("client").apply { isAccessible = true }
        val apk = UpdatePackage::class.java.getDeclaredField("client").apply { isAccessible = true }
        val oldMetadata = metadata.get(null)
        val oldApk = apk.get(null)
        val checks = AtomicInteger()
        val downloads = AtomicInteger()
        val releaseDownload = CountDownLatch(1)
        val downloadStarted = CountDownLatch(1)
        val readyApk = File(context.cacheDir, "updates/${policy.cacheApkName}")
        readyApk.delete()
        var screen: Activity? = null
        fun launch(): Activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val title = "发现新版本 ${policy.versionName}"
        try {
            Store.updatePolicyJson = null
            Store.autoCheckUpdate = true
            metadata.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                checks.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(Gson().toJson(policy).toResponseBody()).build()
            }.build())
            apk.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                downloads.incrementAndGet()
                downloadStarted.countDown()
                check(releaseDownload.await(25, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("fixture").body(fixture.source().buffer().asResponseBody(contentLength = fixture.length())).build()
            }.build())

            stage("first resume downloads silently")
            screen = launch()
            check(downloadStarted.await(10, TimeUnit.SECONDS))
            awaitText(test, "检查客户端更新", true)
            awaitText(test, title, false)
            check(monitor.hits == 0 && !readyApk.exists())

            stage("verified download shows the update prompt and notes")
            releaseDownload.countDown()
            awaitText(test, title, true)
            val text = nodeText(test.uiAutomation.rootInActiveWindow)
            check((1..3).all { text.contains(nextVersion(it)) })
            check(text.contains("FIRST-SKIPPED-RELEASE") && text.contains("SECOND-SKIPPED-RELEASE"))
            check(text.contains("THIRD-SKIPPED-RELEASE") && !text.contains("ALREADY-INSTALLED"))
            check(readyApk.isFile && monitor.hits == 0)

            stage("defer ordinary update and keep the package")
            check(clickText(test.uiAutomation.rootInActiveWindow, "暂不更新"))
            awaitText(test, title, false)
            check(monitor.hits == 0 && readyApk.isFile)
            val vm = androidx.lifecycle.ViewModelProvider(screen as MainActivity)[MainViewModel::class.java]
            test.runOnMainSync { vm.setAutoCheckUpdate(false) }
            check(!Store.autoCheckUpdate)
            Store.clearAccount()
            check(!Store.autoCheckUpdate)
            test.runOnMainSync { screen!!.finish() }

            stage("automatic checks disabled after task restart")
            screen = launch()
            awaitText(test, "检查客户端更新", true)
            val restarted = androidx.lifecycle.ViewModelProvider(screen as MainActivity)[MainViewModel::class.java]
            test.runOnMainSync { restarted.checkAppUpdate() }
            check(!restarted.autoCheckUpdate.value && !restarted.checkingUpdate.value)
            check(checks.get() == 1 && monitor.hits == 0)

            stage("manual check reopens the prompt using the verified cache")
            check(clickText(test.uiAutomation.rootInActiveWindow, "检查客户端更新"))
            awaitText(test, title, true)
            check(downloads.get() == 1 && checks.get() == 2 && monitor.hits == 0)

            stage("explicit update hands the cached package to the system installer")
            check(clickText(test.uiAutomation.rootInActiveWindow, "立即更新"))
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (monitor.hits == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(monitor.hits == 1 && downloads.get() == 1)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText(test, title, false)
            check(screen?.isFinishing == false)
        } finally {
            releaseDownload.countDown()
            test.runOnMainSync { screen?.finish() }
            test.removeMonitor(monitor)
            metadata.set(null, oldMetadata)
            apk.set(null, oldApk)
            readyApk.delete()
        }
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
