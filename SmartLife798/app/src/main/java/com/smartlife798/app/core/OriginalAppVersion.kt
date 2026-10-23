package com.smartlife798.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Numeric release ordering shared by protocol discovery and release-note filtering. */
class ReleaseVersion private constructor(private val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        parts.indices.forEach { index ->
            val order = parts[index].compareTo(other.parts[index])
            if (order != 0) return order
        }
        return 0
    }

    companion object {
        fun parse(value: String): ReleaseVersion {
            require(value.matches(Regex("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")))
            return ReleaseVersion(value.split('.').map(String::toInt))
        }
    }
}

/** Distribution metadata is version discovery, not proof of business-protocol compatibility. */
object OriginalAppVersion {
    const val SOURCE = "https://app.mi.com/details?id=com.cloudora.android"
    private val _current = MutableStateFlow(Api.CLIENT_VERSION)
    val current = _current.asStateFlow()
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    fun select(candidate: String?, fallback: String = Api.CLIENT_VERSION): String = try {
        if (candidate != null && ReleaseVersion.parse(candidate) >= ReleaseVersion.parse(fallback)) candidate else fallback
    } catch (_: IllegalArgumentException) {
        fallback
    }

    fun restore(cached: String?) {
        _current.value = select(cached)
    }

    /** Called on the VM's main dispatcher after its cancellation/preview guard. */
    fun accept(candidate: String) {
        check(!Preview.enabled)
        _current.value = select(candidate, _current.value)
        Store.originalAppVersion = _current.value
    }

    fun parseListing(html: String): String {
        require(html.length <= 262_144)
        val text = html.replace(Regex("<!--.*?-->|<script\\b.*?</script>|<style\\b.*?</style>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        require(Regex("包名\\s+com\\.cloudora\\.android(?=\\s|$)").containsMatchIn(text))
        require(Regex("开发者\\s+长沙云多啦信息技术有限公司(?=\\s|$)").containsMatchIn(text))
        val version = Regex("版本号\\s+(\\S+)").findAll(text).toList().single().groupValues[1]
        ReleaseVersion.parse(version)
        return version
    }

    /** Public HTTPS only: never receives the business token and never downloads/executes the original APK. */
    suspend fun fetch(): String = parseListing(client.fetchPublicText(SOURCE, 262_144))
}

/** Cancellation (including entering preview) also cancels the in-flight HTTP call/body read. */
internal suspend fun OkHttpClient.fetchPublicText(url: String, limit: Int): String {
    check(!Preview.enabled)
    return suspendCancellableCoroutine { continuation ->
        val call = newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("Metadata unavailable")
                        val body = it.body ?: throw IOException("Empty metadata")
                        body.byteStream().use { input -> input.readBytesLimited(limit) }.toString(Charsets.UTF_8)
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

/** Bounds unknown-length HTTP bodies before allocation. */
internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    if (output.size() > limit) throw IOException("Response too large")
    return output.toByteArray()
}
