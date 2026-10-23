package com.smartlife798.app.core

import android.util.Log
import com.smartlife798.app.BuildConfig

/**
 * Structured, logcat-only evidence for flows that cannot be asserted offline:
 * real Alipay return codes and device start/end billing responses.
 *
 * Deliberately never logs tokens, signed order strings or personal data, and is a
 * no-op in release builds so production logcat never carries business detail.
 *
 * Collect evidence with a debug build:
 *   adb logcat -s SmartLife798/Evidence
 */
object Diag {
    const val TAG = "SmartLife798/Evidence"

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val line = buildString {
            append(name)
            fields.forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(value ?: "null")
            }
        }
        Log.i(TAG, line)
    }
}
