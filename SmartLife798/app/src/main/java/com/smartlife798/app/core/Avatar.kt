package com.smartlife798.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Public avatar URLs are separate from the authenticated business transport. */
object Avatar {
    internal const val MAX_BYTES = 2 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** The original avatar loader passes account.img directly; no base URL is added. */
    internal fun url(image: String?): HttpUrl? {
        val value = image?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
        return value.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }
    }

    suspend fun load(image: String): Bitmap? = withContext(Dispatchers.IO) {
        if (Preview.enabled) return@withContext null
        val url = url(image) ?: return@withContext null
        try {
            val bytes = fetch(client, url) ?: return@withContext null
            ensureActive()
            if (Preview.enabled) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = sampleSize(bounds.outWidth, bounds.outHeight) ?: return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
            ensureActive()
            if (Preview.enabled) null else bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Decode bounds first, reject pathological headers, then allocate at most a small avatar. */
    internal fun sampleSize(width: Int, height: Int): Int? {
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 33_554_432L) return null
        var sample = 1
        while (maxOf(width, height) / sample > 256) sample *= 2
        return sample
    }

    internal suspend fun fetch(transport: OkHttpClient, url: HttpUrl): ByteArray? {
        if (Preview.enabled) return null
        return suspendCancellableCoroutine { continuation ->
            val call = transport.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful || Preview.enabled) return@use null
                            val body = it.body ?: return@use null
                            if (body.contentType()?.type != "image" || body.contentLength() > MAX_BYTES) return@use null
                            body.byteStream().use { input -> input.readBytesLimited(MAX_BYTES) }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
