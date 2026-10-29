package com.smartlife798.app.core

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Only a live read may authorize automatic use. A web link/boolean extra cannot do so. */
class NfcIntentInput private constructor(val uris: List<String>, private val verifiedToken: String?) {
    fun allowsAutoStart(): Boolean = !Preview.enabled && verifiedToken != null && verifiedToken == session.token

    companion object {
        @Suppress("DEPRECATION")
        suspend fun read(intent: Intent): NfcIntentInput? = withContext(Dispatchers.IO) {
            if (Preview.enabled || intent.action !in setOf(NfcAdapter.ACTION_NDEF_DISCOVERED, Intent.ACTION_VIEW))
                return@withContext null
            val uri = intent.dataString ?: return@withContext null
            val route = NfcRoutes.parse(uri)
            if (route == NfcRoute.Unsupported) return@withContext NfcIntentInput(listOf(uri), null)
            val token = session.token
            val tag = try {
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                else intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            } catch (_: RuntimeException) { null }
            var verified = false
            if (tag != null && token != null && !Preview.enabled) {
                try {
                    Ndef.get(tag)?.use { ndef ->
                        // A fabricated Parcelable or a stale replay cannot pass a live NFC service read.
                        ndef.connect()
                        val message = ndef.ndefMessage
                        if (message != null && message.byteArrayLength <= 65_536 && message.records.size <= 16) {
                            verified = NfcRoutes.parseAll(message.records.mapNotNull { it.toUri()?.toString() }) == route
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: IOException) { /* Card removed: detail can still open, automatic use stays off. */ }
                catch (_: RuntimeException) { /* No raw framework details. */ }
            }
            if (Preview.enabled) null else NfcIntentInput(listOf(uri), token?.takeIf { verified && it == session.token })
        }
    }
}
