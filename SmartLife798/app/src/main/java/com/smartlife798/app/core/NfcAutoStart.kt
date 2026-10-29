package com.smartlife798.app.core

/** Volatile, one-use authority from this process's card event. Never restored from navigation args. */
internal class NfcAutoStart {
    private var pending: Pair<String, String>? = null

    fun issue(event: String, device: String) { pending = event to device }
    fun clear() { pending = null }
    fun claim(event: String, device: String): Boolean {
        if (pending != (event to device)) return false
        pending = null
        return true
    }
}
