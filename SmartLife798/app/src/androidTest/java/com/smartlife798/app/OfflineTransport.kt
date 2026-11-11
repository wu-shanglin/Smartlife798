package com.smartlife798.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.gson.Gson
import com.smartlife798.app.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/** Exists only in the separate test APK. Every request terminates here, including unknown routes. */
object OfflineTransport {
    class Router(private val showLoginCaptcha: Boolean = false) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (showLoginCaptcha && request.url.encodedPath == "/api/v1/captcha/") {
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("offline captcha").body(captchaImage().toResponseBody("image/png".toMediaType()))
                    .build()
            }
            val gson = Gson()
            val data = when (request.url.encodedPath) {
                "/api/v1/ui/app/master" -> gson.toJson(mapOf("account" to mapOf("id" to "preview-user", "name" to "离线夹具用户"), "favos" to FixtureData.filterFavorites()))
                "/api/v1/acc/wallet/owner" -> gson.toJson(mapOf("aw" to FixtureData.wallet()))
                "/api/v1/bill/lst-owner" -> gson.toJson(if (request.url.queryParameter("page") == "0") FixtureData.bills() else emptyList<Bill>())
                "/api/v1/ui/app/dev/home/1" -> gson.toJson(FixtureData.detail(request.url.queryParameter("did").orEmpty()))
                "/api/v1/prd/lst" -> gson.toJson(FixtureData.rechargeProducts())
                else -> null
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
                .body((if (data == null) """{"code":-1,"msg":"Offline route rejected"}""" else """{"code":0,"data":$data}""").toResponseBody()).build()
        }
    }
    fun install(originalVersionLive: Boolean = false, showLoginCaptcha: Boolean = false) {
        for (target in listOf(Api, AppUpdate, OriginalAppVersion, Avatar, UpdatePackage)) {
            if (originalVersionLive && target === OriginalAppVersion) continue
            val field = target.javaClass.getDeclaredField("client").apply { isAccessible = true }
            val original = field.get(null) as OkHttpClient
            field.set(null, original.newBuilder().addInterceptor(Router(showLoginCaptcha)).build())
        }
    }
}

internal fun captchaImage(): ByteArray {
    val bitmap = Bitmap.createBitmap(144, 48, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    Canvas(bitmap).drawText("TEST", 20f, 35f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f
    })
    val bytes = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    bitmap.recycle()
    return bytes.toByteArray()
}
