package com.smartlife798.app.core

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground reader only. No background intent filters or tag commands. */
class NfcDiagnosticReader(private val activity: Activity, private val onTag: (Tag) -> Unit) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val active = AtomicBoolean(false)
    private var lastTag = byteArrayOf()
    private var lastAt = 0L

    fun start(): String {
        if (Preview.enabled || !Store.developerToolsEnabled()) return "采集未启用"
        val nfc = adapter ?: return "当前设备没有 NFC"
        if (!nfc.isEnabled) return "请先打开系统 NFC，再点击开始采集"
        return try {
            active.set(true)
            nfc.enableReaderMode(activity, { tag ->
                if (active.get() && !Preview.enabled && Store.developerToolsEnabled()) {
                    val now = SystemClock.elapsedRealtime()
                    val id = tag.id ?: byteArrayOf()
                    if (!id.contentEquals(lastTag) || now - lastAt >= 1500L) {
                        lastTag = id.clone()
                        lastAt = now
                        onTag(tag)
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
            "已就绪，请保持本页在前台并碰设备卡片"
        } catch (_: RuntimeException) {
            active.set(false)
            "NFC 暂时不可用，请稍后重新开始"
        }
    }

    fun isActive(): Boolean = active.get()

    fun stop() {
        if (active.getAndSet(false)) {
            try { adapter?.disableReaderMode(activity) } catch (_: RuntimeException) { /* No raw framework errors. */ }
        }
    }
}
