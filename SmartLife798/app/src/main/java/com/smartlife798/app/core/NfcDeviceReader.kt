package com.smartlife798.app.core

import android.app.Activity
import android.app.KeyguardManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Handler
import android.os.Looper

/** Normal foreground entry. Diagnostic reader mode is owned exclusively by its child page. */
class NfcDeviceReader(private val activity: Activity, private val onUris: (List<String>) -> Unit) {
    private val gate = NfcReadGate()
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        if (Preview.enabled || !session.isLoggedIn || adapter?.isEnabled != true) return
        if (activity.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) return
        try {
            val current = gate.start()
            adapter.enableReaderMode(activity, { tag -> discovered(tag, current) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        } catch (_: RuntimeException) {
            gate.stop()
        }
    }

    private fun discovered(tag: Tag, current: Long) {
        if (Preview.enabled || !session.isLoggedIn || !gate.claim(current)) return
        val uris = try {
            val message = Ndef.get(tag)?.cachedNdefMessage
            if (message == null || message.byteArrayLength > 65_536 || message.records.size > 16) emptyList()
            else message.records.mapNotNull { it.toUri()?.toString() }
        } catch (_: RuntimeException) {
            emptyList()
        }
        if (gate.isCurrent(current) && !Preview.enabled && session.isLoggedIn) onUris(uris)
        // Suppress repeated discovery while a card remains on the antenna. A
        // later physical removal permits a new deliberate tap, including retry.
        try {
            val registered = adapter?.ignore(tag, 500, { gate.removed(current) }, handler) == true
            if (!registered) gate.removed(current) // Already absent; no removal callback will arrive.
        } catch (_: RuntimeException) {
            // Stay latched until pause; do not retry the same card automatically.
        }
    }

    fun stop() {
        if (gate.stop()) {
            try { adapter?.disableReaderMode(activity) } catch (_: RuntimeException) { }
        }
    }
}
