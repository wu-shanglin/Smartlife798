package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.UpdatePackage
import com.smartlife798.app.core.UpdatePolicy
import java.io.File

/** Platform checks without accounts, network, payment or installation side effects. */
class UpdateInstallInstrumentation : Instrumentation() {
    private var expectedHash = ""
    private var mode = ""

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedHash = arguments?.getString("sha256").orEmpty()
        mode = arguments?.getString("mode").orEmpty()
        start()
    }

    override fun onStart() {
        if (mode == "nfc-auto-reject") {
            NfcNavigationChecks.run(this, automatic = true, rejectStart = true)
            return
        }
        if (mode == "nfc-auto-start") {
            NfcNavigationChecks.run(this, automatic = true)
            return
        }
        if (mode == "nfc-navigation") {
            NfcNavigationChecks.run(this)
            return
        }
        if (mode == "nfc-entry") {
            NfcEntryChecks.run(this)
            return
        }
        if (mode == "reads-benchmark" || mode == "reads-performance") {
            ReadPerformanceChecks.run(this, verify = mode == "reads-performance")
            return
        }
        if (mode == "nfc-tools") {
            NfcCaptureChecks.run(this)
            return
        }
        if (mode == "profile-refresh") {
            ProfileRefreshChecks.run(this)
            return
        }
        if (mode == "check-in-ui") {
            CheckInUiChecks.run(this)
            return
        }
        if (mode == "expressive") {
            ExpressiveMotionChecks.run(this)
            return
        }
        if (mode == "check-in") {
            CheckInFlowChecks.run(this)
            return
        }
        if (mode == "avatar") {
            AvatarRenderingChecks.run(this)
            return
        }
        if (mode == "profile") {
            ProfileFlowChecks.run(this)
            return
        }
        if (mode == "bill-sheets") {
            BillSheetChecks.run(this)
            return
        }
        if (mode == "update-progress") {
            UpdateProgressChecks.run(this)
            return
        }
        if (mode == "detail-live" || mode == "detail-frames" || mode == "detail-profile") {
            DetailRenderingChecks.run(this, measureOnly = mode != "detail-live", profile = mode == "detail-profile")
            return
        }
        if (mode == "detail-load") {
            DetailLoadingChecks.run(this)
            return
        }
        if (mode == "start") {
            StartFlowChecks.run(this)
            return
        }
        if (mode == "end") {
            EndFlowChecks.run(this)
            return
        }
        if (mode == "ux" || mode == "settings") {
            UxFlowChecks.run(this, settingsOnly = mode == "settings")
            return
        }
        if (mode.isNotEmpty()) {
            UpdateFlowChecks.run(this, mode)
            return
        }
        val result = Bundle()
        var stage = "valid APK"
        try {
            val apk = File(targetContext.cacheDir, "updates/fixture.apk")
            val policy = UpdatePolicy(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, 1, "", expectedHash)
            UpdatePackage.verify(targetContext, apk, policy)
            val partial = File(targetContext.cacheDir, "updates/fixture.part")
            try {
                apk.copyTo(partial, overwrite = true)
                UpdatePackage.verify(targetContext, partial, policy)
            } finally {
                partial.delete()
            }
            stage = "mismatch rejection"
            fun rejects(candidate: UpdatePolicy) {
                var rejected = false
                try {
                    UpdatePackage.verify(targetContext, apk, candidate)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                check(rejected)
            }
            rejects(policy.copy(sha256 = "0".repeat(64)))
            rejects(policy.copy(versionCode = policy.versionCode + 1))
            rejects(policy.copy(versionName = "999.0.0"))
            stage = "FileProvider"
            val intent = UpdatePackage.installIntent(targetContext, apk)
            check(intent.data?.scheme == "content")
            check(intent.data?.authority == "${targetContext.packageName}.updates")
            check(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            Preview.enabled = true
            stage = "preview"
            var previewRejected = false
            try {
                UpdatePackage.installIntent(targetContext, apk)
            } catch (_: IllegalStateException) {
                previewRejected = true
            } finally {
                Preview.enabled = false
            }
            check(previewRejected)
            result.putString("stream", "PASS: APK hash/package/version/signature, mismatches, FileProvider URI and preview install isolation\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            val reason = (error as? com.smartlife798.app.core.UpdateVerificationException)?.reason?.name.orEmpty()
            result.putString("stream", "FAIL at $stage: " + error.javaClass.simpleName + " $reason\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
