package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.UpdatePackage
import com.smartlife798.app.core.UpdatePolicy
import com.smartlife798.app.ui.AppUpdatePrompt
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Long notes and controlled download failure/cancellation; every APK request is intercepted. */
object UpdateProgressChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val field = UpdatePackage::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(null)
        val release = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val calls = AtomicInteger()
        var replaced = false
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            field.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                val latch = if (calls.incrementAndGet() == 1) release else releaseRetry
                check(latch.await(20, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("fixture").body("".toResponseBody()).build()
            }.build())
            replaced = true
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            test.waitForIdleSync()
            val screen = activity
            lateinit var vm: MainViewModel
            lateinit var download: MutableStateFlow<MainViewModel.UpdateDownload>
            val policy = UpdatePolicy(BuildConfig.VERSION_CODE + 1, "99.0.0", 1,
                (1..45).joinToString("\n") { "更新说明 $it：优化设备详情加载与应用更新体验。" }, "a".repeat(64))
            test.runOnMainSync {
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                Preview.enabled = false
                val policyField = MainViewModel::class.java.getDeclaredField("_updatePolicy").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (policyField.get(vm) as MutableStateFlow<UpdatePolicy?>).value = policy
                val downloadField = MainViewModel::class.java.getDeclaredField("_updateDownload").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                download = downloadField.get(vm) as MutableStateFlow<MainViewModel.UpdateDownload>
                screen.setContent {
                    SmartLifeTheme {
                        AppUpdatePrompt(vm, policy, true, false, download.collectAsStateWithLifecycle().value,
                            onDismiss = {}, onExit = screen::finishAndRemoveTask, onRetry = {}, onError = {})
                    }
                }
            }
            stage = "click starts download with visible indeterminate progress"
            await { find(test, "立即更新") != null }
            click(test, "立即更新")
            await { calls.get() == 1 && find(test, "正在下载…") != null }
            check(find(test, "安装包下载") == null)
            stage = "determinate progress remains fixed while long notes scroll"
            // Percentage fixture exercises the UI without downloading or installing an APK.
            test.runOnMainSync { download.value = MainViewModel.UpdateDownload(busy = true, percent = 33) }
            await { find(test, "下载进度 33%") != null }
            val before = bounds(find(test, "下载进度 33%")!!)
            val scrolling = nodes(root(test)).first { it.isScrollable }
            check(scrolling.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(400)
            check(bounds(find(test, "下载进度 33%")!!) == before)
            val cancel = bounds(find(test, "取消下载")!!)
            check(before.bottom < cancel.top)
            check(nodes(root(test)).any { it.rangeInfo != null && it.isVisibleToUser })
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(test.targetContext.cacheDir, "update-progress.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            stage = "failure remains visible with retry"
            release.countDown()
            await { find(test, "重新下载") != null && nodes(root(test)).any { it.text?.contains("下载失败") == true } }
            stage = "retry starts once"
            click(test, "重新下载")
            await { calls.get() == 2 && find(test, "取消下载") != null }
            stage = "cancellation resets status"
            click(test, "取消下载")
            val jobField = MainViewModel::class.java.getDeclaredField("downloadJob").apply { isAccessible = true }
            await { (jobField.get(vm) as? kotlinx.coroutines.Job)?.isCancelled == true }
            releaseRetry.countDown()
            await { find(test, "点击「立即更新」开始下载") != null }
            check(calls.get() == 2)
            result.putString("stream", "PASS: click starts one download, indeterminate/determinate progress, fixed visible bounds above actions while long notes scroll, visible failure/retry and cancellation; synthetic percentage and offline transport\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            release.countDown()
            releaseRetry.countDown()
            test.runOnMainSync { activity?.finish() }
            test.waitForIdleSync()
            if (replaced) field.set(null, original)
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
    private fun find(test: Instrumentation, text: String) = nodes(root(test)).firstOrNull { it.text?.toString() == text }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun click(test: Instrumentation, text: String) {
        var target = find(test, text)
        while (target != null) {
            if (target.isClickable) {
                check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            target = target.parent
        }
        error("Action unavailable")
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(80)
        }
        error("UI did not reach expected state")
    }
}
