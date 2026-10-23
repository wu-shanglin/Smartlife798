package com.smartlife798.app.core

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class UpdatePolicy(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val notes: String,
    val sha256: String,
) {
    val releaseUrl: String get() = "$REPOSITORY/releases/tag/v$versionName"
    val apkName: String get() = "SmartLife798-$versionName-debug.apk"
    val apkUrl: String get() = "$REPOSITORY/releases/download/v$versionName/$apkName"
    fun available(currentCode: Int) = versionCode > currentCode
    fun required(currentCode: Int) = available(currentCode) && currentCode < minSupportedVersionCode

    companion object {
        const val REPOSITORY = "https://github.com/wu-shanglin/Smartlife798"

        /** Strict parsing: malformed metadata must never lock users out. */
        fun parse(json: String): UpdatePolicy {
            val root = JsonParser.parseString(json).asJsonObject
            fun positiveInt(key: String): Int {
                val value = root.get(key)?.asJsonPrimitive ?: error("Missing update field")
                require(value.isNumber && value.toString().matches(Regex("[1-9][0-9]*")))
                return value.asString.toInt()
            }
            val code = positiveInt("versionCode")
            val minimum = positiveInt("minSupportedVersionCode")
            val name = root.get("versionName")?.asJsonPrimitive?.also { require(it.isString) }
                ?.asString ?: error("Missing version name")
            require(name.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")))
            require(minimum <= code)
            val notes = root.get("notes")?.asJsonPrimitive?.also { require(it.isString) }?.asString.orEmpty()
            require(notes.length <= 4000)
            val hash = root.get("sha256")?.asJsonPrimitive?.also { require(it.isString) }
                ?.asString ?: error("Missing APK hash")
            require(hash.matches(Regex("[0-9a-f]{64}")))
            return UpdatePolicy(code, name, minimum, notes, hash)
        }

        fun cached(json: String?): UpdatePolicy? = try {
            json?.let(::parse)
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Dedicated public client: no business interceptors, cookies, credentials or retries. */
object AppUpdate {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun fetch(): String {
        val request = Request.Builder()
            .url("${UpdatePolicy.REPOSITORY}/releases/latest/download/update.json")
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update unavailable")
            val body = response.body ?: throw IOException("Empty update")
            // Cap bytes before allocation, including responses with unknown Content-Length.
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() <= 16_384) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 16_385 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 16_384) throw IOException("Update too large")
            bytes.toString(Charsets.UTF_8).also { UpdatePolicy.parse(it) }
        }
    }
}
