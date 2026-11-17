package com.smartlife798.app.core

import java.net.URLEncoder

/** Request digest for the original 3.1.10 start and end commands. */
internal object DeviceCommandSign {
    const val PROTOCOL = "3.1.10"
    const val START = "/api/v1/dev/start"
    const val END = "/api/v1/dev/end"

    fun sign(query: Map<String, String>, token: String, uid: String, endpoint: String, serverTimeMs: Long): String? {
        if (serverTimeMs <= 0 || token.length < 8 || token.any { it !in '!'..'~' } ||
            uid.length < 8 || uid.isBlank()) return null

        val bucketSeconds = (serverTimeMs / 30_000L) * 30L
        // OctUtil sorts the input by key. The start caller form-encodes nonblank args
        // before passing them to OctUtil. The wire query is encoded separately by OkHttp.
        val normalized = query.toSortedMap().entries.mapNotNull { (key, value) ->
            if (key != "args") "$key=$value"
            else value.takeUnless { it.isBlank() }?.let { "args=${URLEncoder.encode(it, "UTF-8")}" }
        }.joinToString("&")
        val payload = normalized + bucketSeconds + token.takeLast(8) + uid.takeLast(8)
        return OctSignature.sign(payload, token, endpoint, bucketSeconds)
    }
}
