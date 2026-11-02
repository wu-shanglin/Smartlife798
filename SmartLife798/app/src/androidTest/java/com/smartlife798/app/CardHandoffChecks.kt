package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smartlife798.app.core.Store
import com.smartlife798.app.ui.SharedCardPage
import com.smartlife798.app.ui.SharedCardScene

/** Render the real scene in the gap between navigation deactivation and shared animation. */
object CardHandoffChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
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
            result.putString("stream", "PASS: inactive full-screen scene preserves actual rendered pixels before shared animation starts; local hit clip remains enabled. Offline only.\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName} at ${error.stackTrace.firstOrNull()}\n")
        } finally {
            test.runOnMainSync { activity?.finish() }
        }
        test.finish(code, result)
    }
}
