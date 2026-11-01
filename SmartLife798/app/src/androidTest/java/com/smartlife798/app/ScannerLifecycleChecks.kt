package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateOf
import com.smartlife798.app.core.FixtureData
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.CameraPreview
import java.util.concurrent.TimeUnit

/** Requires CAMERA granted on a disposable account-free emulator; never resolves a QR. */
object ScannerLifecycleChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var provider: ProcessCameraProvider? = null
        val sibling = ImageCapture.Builder().build()
        try {
            test.runOnMainSync { ServiceLocator.init(test.targetContext) }
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null)
            check(Store.active() == null && Store.pendingStart() == null)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("offlineFixture", true)) as MainActivity
            stage = "camera provider"
            provider = ProcessCameraProvider.getInstance(test.targetContext).get(10, TimeUnit.SECONDS)
            val visible = mutableStateOf(false)
            test.runOnMainSync {
                activity!!.setContent { if (visible.value) CameraPreview(onQr = { error("unexpected QR fixture") }) }
                provider!!.bindToLifecycle(activity!!, CameraSelector.DEFAULT_BACK_CAMERA, sibling)
            }
            repeat(4) {
                stage = "mount/dispose scanner $it"
                test.runOnMainSync { visible.value = true }
                SystemClock.sleep(350)
                test.waitForIdleSync()
                test.runOnMainSync { check(provider!!.isBound(sibling)); visible.value = false }
                SystemClock.sleep(350)
                test.waitForIdleSync()
                test.runOnMainSync { check(provider!!.isBound(sibling)) }
            }
            result.putString("stream", "PASS: four camera mount/dispose cycles retain sibling use case; no QR resolution or business operations; emulator camera only\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            test.runOnMainSync {
                activity?.let { it.setContent { }; provider?.unbind(sibling); it.finish() }
            }

        }
        test.finish(code, result)
    }
}
