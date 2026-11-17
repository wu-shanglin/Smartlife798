package com.smartlife798.app.core

import com.google.gson.JsonObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object UsageStats {
    private val transport by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun reportingDay(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }.format(Date(now))

    suspend fun report(origin: String, day: String, installation: String, startAccepted: Boolean): Boolean {
        val body = JsonObject().apply {
            addProperty("kind", if (startAccepted) "start_accepted" else "active")
            addProperty("day", day)
            addProperty("installation", installation)
            if (startAccepted) addProperty("eventId", UUID.randomUUID().toString())
        }
        val request = Request.Builder().url("$origin/api/usage/v1")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            Api.awaitResponse(Api.singleAttemptWriteCall(transport, request)) { it.code == 204 }
        } catch (_: IOException) {
            false
        }
    }
}
