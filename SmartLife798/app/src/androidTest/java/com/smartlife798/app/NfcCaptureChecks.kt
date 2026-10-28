package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.os.Bundle
import android.os.Build
import android.os.Parcel
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.smartlife798.app.core.NfcCaptureLog
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import java.io.File
import android.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Offline synthetic NDEF checks. Never enables hardware polling or opens a captured URI. */
object NfcCaptureChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "isolated account-free guard"
        val context = test.targetContext
        val file = File(context.filesDir, NfcCaptureLog.FILE_NAME)
        val shared = File(context.cacheDir, "diagnostics/${NfcCaptureLog.FILE_NAME}")
        test.runOnMainSync { ServiceLocator.init(context) }
        val preferences = context.getSharedPreferences("smartlife798", 0)
        val priorEnabled = preferences.all["developerToolsEnabled"] as? Boolean
        val priorPreview = Preview.enabled
        var started = false
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null &&
                Store.active() == null && Store.pendingStart() == null && !file.exists() && !shared.exists())
            started = true
            Preview.enabled = false
            val message = NdefMessage(arrayOf(
                NdefRecord.createUri("alipay://nfc/app?id=123456789012345&t=1&token=fixture-secret"),
                NdefRecord.createApplicationRecord("local.fixture.nfc"),
                NdefRecord.createTextRecord("en", "private fixture text"),
            ))
            val tag = mockTag(message)
            runBlocking {
                stage = "disabled and inactive writes are blocked"
                Store.saveDeveloperToolsEnabled(false)
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { true }.contains("暂停"))
                check(!file.exists() && NfcCaptureLog.read(context).isEmpty() && NfcCaptureLog.shareFile(context) == null)
                Store.saveDeveloperToolsEnabled(true)
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { false }.contains("暂停"))
                check(!file.exists())

                stage = "active guard is rechecked before committing"
                val calls = AtomicInteger()
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { calls.incrementAndGet() == 1 }.contains("暂停"))
                check(!file.exists())

                stage = "oversized complete NDEF refuses storage without partial records"
                val oversized = NdefMessage(arrayOf(NdefRecord(
                    NdefRecord.TNF_MIME_MEDIA, "application/octet-stream".toByteArray(), byteArrayOf(), ByteArray(65_536),
                )))
                check(NfcCaptureLog.save(context, mockTag(oversized), "offline-fixture") { true }.contains("超过 64KiB"))
                check(!file.exists())

                stage = "synthetic cached NDEF preserves exact raw fields in local storage"
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { true }.contains("已采集 3"))
                val saved = NfcCaptureLog.read(context)
                val event = JsonParser.parseString(saved.trim()).asJsonObject
                check(event.get("format").asInt == 2 && !event.has("tagId"))
                check(event.getAsJsonArray("records").size() == 3)
                val route = event.getAsJsonArray("records")[0].asJsonObject.get("uri").asString
                check(route.contains("alipay://nfc/app?id=123456789012345&t=1"))
                check(saved.contains("fixture-secret") && saved.contains("private fixture text"))
                check(Base64.decode(event.get("ndefBase64").asString, Base64.NO_WRAP).contentEquals(message.toByteArray()))
                check(Base64.decode(event.getAsJsonArray("records")[0].asJsonObject.get("payloadBase64").asString,
                    Base64.NO_WRAP).contentEquals(message.records[0].payload))

                stage = "share uses only the dedicated private cache and FileProvider"
                val share = requireNotNull(NfcCaptureLog.shareFile(context))
                check(share.canonicalFile == shared.canonicalFile && share.readText() == saved)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", share)
                check(uri.scheme == "content" && uri.authority == "${context.packageName}.updates")
                check(uri.path.orEmpty().startsWith("/diagnostics/"))

                stage = "Preview cannot read share clear persist or change the installation switch"
                Preview.enabled = true
                Store.saveDeveloperToolsEnabled(false)
                check(Store.developerToolsEnabled())
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { true }.contains("暂停"))
                check(NfcCaptureLog.read(context).isEmpty() && NfcCaptureLog.shareFile(context) == null)
                NfcCaptureLog.clear(context)
                check(file.readText() == saved && shared.readText() == saved)
                Preview.enabled = false

                stage = "clear removes source and shared copy and size limit refuses further append"
                NfcCaptureLog.clear(context)
                check(!file.exists() && !shared.exists())
                file.writeText("x".repeat(131_072), Charsets.UTF_8)
                check(NfcCaptureLog.save(context, tag, "offline-fixture") { true }.contains("已满"))
                check(file.length() == 131_072L)
                NfcCaptureLog.clear(context)
                check(!file.exists())
            }
            result.putString("stream", "PASS: synthetic cached NDEF raw URI/text/payload and full message round-trip; disabled/Preview/inactive/late-active guards; no ambient card UID stored; arbitrary NDEF text retained by explicit owner request; 128KiB cap; clear and private FileProvider share; no NFC hardware polling, URI launches or business requests\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}; source=${frame?.fileName}:${frame?.lineNumber}\n")
        } finally {
            if (started) {
                Preview.enabled = false
                Store.saveDeveloperToolsEnabled(true)
                runBlocking { NfcCaptureLog.clear(context) }
                val editor = preferences.edit()
                if (priorEnabled == null) editor.remove("developerToolsEnabled") else editor.putBoolean("developerToolsEnabled", priorEnabled)
                check(editor.commit())
            }
            Preview.enabled = priorPreview
        }
        test.finish(code, result)
    }

    private fun mockTag(message: NdefMessage): Tag {
        val extra = Bundle().apply {
            putParcelable("ndefmsg", message)
            putInt("ndefmaxlength", 8192)
            putInt("ndefcardstate", 1)
            putInt("ndeftype", 1)
        }
        // Tag's public Parcelable contract, matching AOSP android-9/13 and android-14+.
        // The cookie was added in Android 14. A null tag service means a synthetic tag.
        // No hidden API access or real NFC binder service is involved.
        val parcel = Parcel.obtain()
        return try {
            val id = byteArrayOf(1, 2, 3, 4)
            parcel.writeInt(id.size)
            parcel.writeByteArray(id)
            parcel.writeInt(1)
            parcel.writeIntArray(intArrayOf(6)) // TagTechnology.NDEF
            parcel.writeTypedArray(arrayOf(extra), 0)
            parcel.writeInt(0) // service handle, unused for cached NDEF
            if (Build.VERSION.SDK_INT >= 34) parcel.writeLong(0L)
            parcel.writeInt(1) // isMock: no INfcTag binder
            parcel.setDataPosition(0)
            Tag.CREATOR.createFromParcel(parcel).also {
                check(parcel.dataAvail() == 0)
                check(it.id.contentEquals(id) && it.techList.contentEquals(arrayOf("android.nfc.tech.Ndef")))
            }
        } finally {
            parcel.recycle()
        }
    }
}
