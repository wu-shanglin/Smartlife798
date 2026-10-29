package com.smartlife798.app.core

import android.content.Context
import android.nfc.NdefRecord
import android.nfc.Tag
import android.util.Base64
import android.nfc.tech.Ndef
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit foreground local diagnostics. Does not open links, issue APDUs, write tags, or call business APIs. */
object NfcCaptureLog {
    const val FILE_NAME = "nfc-diagnostics.jsonl"
    private val writes = Mutex()

    suspend fun save(context: Context, tag: Tag, version: String, active: () -> Boolean): String =
        withContext(Dispatchers.IO) {
            if (Preview.enabled || !Store.developerToolsEnabled() || !active()) return@withContext "采集已暂停"
            val ndef = Ndef.get(tag)
            // Reader mode without SKIP_NDEF_CHECK supplies this snapshot; no additional card command.
            val message = ndef?.cachedNdefMessage
            val encoded = message?.toByteArray()
            if (encoded != null && encoded.size > 65_536) return@withContext "NDEF 记录超过 64KiB，未保存"
            val allRecords = message?.records.orEmpty()
            val records = allRecords.take(16)
            val event = linkedMapOf<String, Any?>(
                "format" to 2,
                "captureMode" to "rawNdef",
                "ndefBase64" to encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                "totalRecords" to allRecords.size,
                "capturedAt" to System.currentTimeMillis(),
                "version" to version,
                "technologies" to tag.techList.orEmpty().toList(),
                "hasNdefTechnology" to (ndef != null),
                "records" to records.map(::record),
            )
            writes.withLock {
                currentCoroutineContext().ensureActive()
                if (Preview.enabled || !Store.developerToolsEnabled() || !active()) return@withLock "采集已暂停"
                val file = File(context.filesDir, FILE_NAME)
                val line = Gson().toJson(event) + "\n"
                if (file.length() + line.toByteArray(Charsets.UTF_8).size > 131_072) {
                    return@withLock "记录已满，请分享或清空后再继续"
                }
                file.appendText(line, Charsets.UTF_8)
                "已采集 ${records.size} 条 NDEF 记录"
            }
        }

    suspend fun read(context: Context): String = withContext(Dispatchers.IO) {
        if (Preview.enabled || !Store.developerToolsEnabled()) return@withContext ""
        writes.withLock {
            currentCoroutineContext().ensureActive()
            if (Preview.enabled || !Store.developerToolsEnabled()) return@withLock ""
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() <= 131_072) file.readText(Charsets.UTF_8) else ""
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        if (!Preview.enabled && Store.developerToolsEnabled()) writes.withLock {
            currentCoroutineContext().ensureActive()
            if (Preview.enabled || !Store.developerToolsEnabled()) return@withLock
            for (file in listOf(File(context.filesDir, FILE_NAME), File(context.cacheDir, "diagnostics/nfc-diagnostics.jsonl"))) {
                if (file.exists() && !file.delete()) throw java.io.IOException("Diagnostic file deletion failed")
            }
        }
        Unit
    }

    suspend fun shareFile(context: Context): File? = withContext(Dispatchers.IO) {
        if (Preview.enabled || !Store.developerToolsEnabled()) return@withContext null
        writes.withLock {
            currentCoroutineContext().ensureActive()
            if (Preview.enabled || !Store.developerToolsEnabled()) return@withLock null
            val source = File(context.filesDir, FILE_NAME)
            if (!source.exists() || source.length() == 0L || source.length() > 131_072) return@withLock null
            val folder = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            File(folder, FILE_NAME).also { source.copyTo(it, overwrite = true) }
        }
    }

    private fun record(value: NdefRecord): Map<String, Any?> {
        val payload = value.payload ?: byteArrayOf()
        val type = value.type?.toString(Charsets.US_ASCII).orEmpty()
        val result = linkedMapOf<String, Any?>(
            "tnf" to value.tnf.toInt(),
            "type" to if (value.tnf == NdefRecord.TNF_ABSOLUTE_URI) "absolute-uri"
                else type.takeIf { it.matches(Regex("[A-Za-z0-9.:_+/-]{0,128}")) }.orEmpty(),
            "payloadBytes" to payload.size,
            "idBase64" to Base64.encodeToString(value.id ?: byteArrayOf(), Base64.NO_WRAP),
            "typeBase64" to Base64.encodeToString(value.type ?: byteArrayOf(), Base64.NO_WRAP),
        )
        if (payload.size > 8192) return result.apply { put("detailsOmitted", "large payload; exact bytes are in ndefBase64") }
        result["payloadBase64"] = Base64.encodeToString(payload, Base64.NO_WRAP)
        if (value.tnf == NdefRecord.TNF_EXTERNAL_TYPE && type == "android.com:pkg") {
            val name = payload.toString(Charsets.UTF_8)
            result["associatedPackage"] = name.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) && it.length < 200 }
        } else {
            val uri = value.toUri()
            if (uri != null) result["uri"] = uri.toString()
            else if (value.tnf == NdefRecord.TNF_WELL_KNOWN && value.type.contentEquals(NdefRecord.RTD_TEXT) && payload.isNotEmpty()) {
                val status = payload[0].toInt() and 255
                val start = 1 + (status and 63)
                if (start <= payload.size) {
                    val charset = if (status and 128 == 0) Charsets.UTF_8 else Charsets.UTF_16
                    result["text"] = payload.copyOfRange(start, payload.size).toString(charset)
                }
            }
        }
        return result
    }
}
