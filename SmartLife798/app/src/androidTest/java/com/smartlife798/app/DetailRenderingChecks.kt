package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.Gson
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.CardOrigin
import com.smartlife798.app.ui.CardSource
import com.smartlife798.app.ui.DeviceDetailScreen
import com.smartlife798.app.ui.SharedCardPage
import com.smartlife798.app.ui.SharedCardScene
import com.smartlife798.app.ui.components.DeviceCard
import com.smartlife798.app.ui.sharedCard
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** Observe the actual shared scope, not a navigation tick that may lag behind the morph. */
object DetailRenderingChecks {
    private data class Sample(val time: Long, val total: Long, val ui: Long)

    fun run(test: Instrumentation, measureOnly: Boolean = false, profile: Boolean = false) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = Api.client
        val originalToken = session.token
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sourceReady = CountDownLatch(1)
        val transitionActive = CountDownLatch(1)
        val transitionSettled = CountDownLatch(1)
        var shared: SharedTransitionScope? = null
        var expanded by mutableStateOf(false)
        val frameThread = HandlerThread("detail-frames").apply { start() }
        val frames = java.util.Collections.synchronizedList(mutableListOf<Sample>())
        var responseStartedAt = Long.MAX_VALUE
        var settledAt = Long.MAX_VALUE
        var tracing = false
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                frames.add(Sample(
                    metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                        metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + metrics.getMetric(FrameMetrics.DRAW_DURATION),
                ))
            }
        }
        try {
            check(Store.token.isNullOrBlank() && Store.active() == null && Store.pendingStart() == null)
            if (!measureOnly) check(android.provider.Settings.Global.getFloat(test.targetContext.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) >= 6f)
            val sample = Preview.devices.first()
            val fixture = Gson().toJson(Preview.detail(sample.id))
            clientField.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                val body = if (chain.request().url.encodedPath.endsWith("/dev/home/1")) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                    """{"code":0,"data":$fixture}"""
                } else """{"code":0,"data":[]}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }.build())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            val screen = activity
            test.runOnMainSync {
                val vm = ViewModelProvider(screen)[MainViewModel::class.java]
                val entryOwner = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
                }
                screen.setContent {
                    SmartLifeTheme {
                        Surface {
                            SharedTransitionLayout {
                                shared = this
                                val sharedScope = this
                                LaunchedEffect(Unit) {
                                    withFrameNanos { }
                                    sourceReady.countDown()
                                    snapshotFlow { sharedScope.isTransitionActive }.first { it }
                                    transitionActive.countDown()
                                    snapshotFlow { sharedScope.isTransitionActive }.first { !it }
                                    settledAt = System.nanoTime()
                                    transitionSettled.countDown()
                                    entryOwner.lifecycle.currentState = Lifecycle.State.RESUMED
                                }
                                AnimatedContent(expanded, label = "detail-live") { showDetail ->
                                    SharedCardScene(this@SharedTransitionLayout, this,
                                        if (showDetail) "detail-entry" else "source-entry") {
                                        if (showDetail) {
                                            SharedCardPage(CardSource("source-entry", CardOrigin.DEVICES), sample.id) {
                                                // Match a Navigation destination: reads may render at
                                                // STARTED, but device polling waits for RESUMED.
                                                CompositionLocalProvider(LocalLifecycleOwner provides entryOwner) {
                                                    DeviceDetailScreen(vm, sample.id, onBack = { expanded = false },
                                                        onOpenOrders = {})
                                                }
                                            }
                                        } else {
                                            Box(Modifier.fillMaxSize().padding(16.dp)) {
                                                DeviceCard(sample, true, false,
                                                    onClick = { expanded = true },
                                                    modifier = Modifier.sharedCard(CardOrigin.DEVICES, sample.id))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            check(sourceReady.await(10, TimeUnit.SECONDS))
            test.runOnMainSync {
                Preview.enabled = false
                session.token = "offline-render-fixture"
                screen.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
                expanded = true
            }
            stage = "request starts during actual shared transition"
            check(entered.await(5, TimeUnit.SECONDS))
            check(transitionActive.await(5, TimeUnit.SECONDS))
            if (profile) {
                android.os.Debug.startMethodTracingSampling(
                    java.io.File(test.targetContext.cacheDir, "detail-render.trace").absolutePath,
                    8 * 1024 * 1024, 1_000,
                )
                tracing = true
            }
            responseStartedAt = System.nanoTime()
            release.countDown()
            if (!measureOnly) {
                stage = "full response visible while shared scope is active"
                await { nodes(root(test)).any { it.text?.toString() == "当前模式" && it.isVisibleToUser } }
                test.runOnMainSync { check(shared?.isTransitionActive == true) }
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(test.targetContext.cacheDir, "detail-live.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            stage = "shared transition settles after response render"
            // Quiet measurements do not query accessibility, synchronously hop to
            // the UI thread or capture screenshots during the animation.
            check(transitionSettled.await(20, TimeUnit.SECONDS))
            if (tracing) {
                android.os.Debug.stopMethodTracing()
                tracing = false
            }
            Thread.sleep(150) // drain asynchronous window metric delivery, after the morph
            val snapshot = synchronized(frames) { frames.filter { it.time <= settledAt } }
            val responseFrames = snapshot.filter { it.time >= responseStartedAt }
            fun summary(values: List<Long>): String {
                if (values.isEmpty()) return "no samples"
                val ms = values.sorted().map { it / 1_000_000.0 }
                return "p95=${ms[((ms.size - 1) * 0.95).toInt()]}, max=${ms.last()}"
            }
            result.putString("stream", (if (measureOnly) "PASS: quiet frame capture, no UI inspection during morph; "
                else "PASS: full detail visible while SharedTransitionScope.isTransitionActive, then settled; ") +
                "entryFrames=${snapshot.size}, totalMs(${summary(snapshot.map { it.total })}), " +
                "animationLayoutDrawMs(${summary(snapshot.map { it.ui })}); " +
                "responseFrames=${responseFrames.size}, responseUiMs(${summary(responseFrames.map { it.ui })}); " +
                "intercepted transport only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            if (tracing) android.os.Debug.stopMethodTracing()
            release.countDown()
            test.runOnMainSync {
                activity?.window?.removeOnFrameMetricsAvailableListener(listener)
                activity?.finish()
            }
            frameThread.quitSafely()
            test.waitForIdleSync()
            clientField.set(null, originalClient)
            session.token = originalToken
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

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(40)
        }
        error("Expected UI state not reached")
    }
}
