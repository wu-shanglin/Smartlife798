package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Store
import com.smartlife798.app.ui.CardOrigin
import com.smartlife798.app.ui.CardSource
import com.smartlife798.app.ui.SharedCardPage
import com.smartlife798.app.ui.SharedCardScene
import com.smartlife798.app.ui.sharedCard

/** Render the real scene in the gap between navigation deactivation and shared animation. */
object CardHandoffChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
        var stage = "handoff"
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            test.uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val originalScale = shell("settings get global animator_duration_scale")
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank())
            val active = mutableStateOf(true)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val screen = activity
            test.runOnMainSync {
                screen.setContent {
                    SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Magenta)) {
                        val shared = this
                        AnimatedVisibility(visible = true) {
                            SharedCardScene(shared, this, "handoff", active = active.value) {
                                SharedCardPage(null) {
                                    Box(Modifier.fillMaxSize().background(Color(0xFF4DB5F2)))
                                }
                            }
                        }
                    }
                }
            }
            test.waitForIdleSync()
            fun pixel(): Int {
                val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
                return try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) }
                finally { bitmap.recycle() }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (pixel() != android.graphics.Color.rgb(77, 181, 242) &&
                android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            val before = pixel()
            check(before == android.graphics.Color.rgb(77, 181, 242)) { "Before pixel=$before" }
            test.runOnMainSync { active.value = false }
            test.waitForIdleSync()
            Thread.sleep(100)
            check(pixel() == before) { "Scene disappeared before shared overlay handoff" }
            stage = "device content blends into the source card"
            shell("settings put global animator_duration_scale 4")
            val detail = mutableStateOf(false)
            val showSource = mutableStateOf(true)
            val cardSource = CardSource("source", CardOrigin.DEVICES)
            test.runOnMainSync {
                screen.setContent {
                    SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Black)) {
                        val shared = this
                        AnimatedContent(
                            targetState = detail.value,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            label = "device return pixels",
                        ) { expanded ->
                            SharedCardScene(shared, this, if (expanded) "detail" else "source", active = expanded == detail.value,
                                clipWhenInactive = expanded) {
                                if (expanded) {
                                    SharedCardPage(cardSource, "device") {
                                        Box(Modifier.fillMaxSize().background(Color.Red))
                                    }
                                } else if (showSource.value) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Box(Modifier.sharedCard(CardOrigin.DEVICES, "device").size(140.dp, 72.dp).background(Color.Green))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun awaitPixel(expected: Int) {
                val end = SystemClock.uptimeMillis() + 6000
                while (pixel() != expected) {
                    check(SystemClock.uptimeMillis() < end) { "Expected center pixel ${Integer.toHexString(expected)}" }
                    SystemClock.sleep(30)
                }
            }
            fun sampleReturn(): List<Int> {
                test.runOnMainSync { detail.value = false }
                val end = SystemClock.uptimeMillis() + 1600
                return buildList {
                    while (SystemClock.uptimeMillis() < end) {
                        add(pixel())
                        SystemClock.sleep(16)
                    }
                }
            }
            awaitPixel(android.graphics.Color.GREEN)
            test.runOnMainSync { detail.value = true }
            awaitPixel(android.graphics.Color.RED)
            val matched = sampleReturn()
            awaitPixel(android.graphics.Color.GREEN)
            check(matched.count { android.graphics.Color.red(it) in 30..225 && android.graphics.Color.green(it) in 30..225 } >= 2) {
                "Detail stayed opaque until the card replaced it"
            }
            check(matched.none { it == android.graphics.Color.BLACK }) { "Shared return exposed a gap at the card center" }
            stage = "missing card uses a continuous full-page fade"
            test.runOnMainSync { detail.value = true }
            awaitPixel(android.graphics.Color.RED)
            test.runOnMainSync { showSource.value = false }
            val unmatched = sampleReturn()
            awaitPixel(android.graphics.Color.BLACK)
            check(unmatched.count { android.graphics.Color.red(it) in 30..225 } >= 2) { "Unmatched page disappeared without fading" }
            result.putString("stream", "PASS: handoff retains pixels, matched device page blends into its card, and missing card fades out. Offline only.\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL $stage: ${error.message} at ${error.stackTrace.firstOrNull()}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
            shell(if (originalScale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $originalScale")
        }
        test.finish(code, result)
    }
}
