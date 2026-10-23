package com.smartlife798.app.core

import com.smartlife798.app.BuildConfig
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiProfileTest {
    @Test
    fun `wire headers use verified original version instead of release or stale request headers`() {
        val requests = mutableListOf<Request>()
        // A terminal application interceptor captures the actual production headers without IO.
        val client = Api.client.newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        for (path in listOf("/api/v1/dev/start", "/api/v1/captcha/", "/api/v1/sys/app/upt")) {
            val request = Request.Builder().url(Api.BASE_URL + path)
                .header("VersionCode", BuildConfig.VERSION_NAME)
                .header("user-agent", "Android_ilife798_3.1.5").build()
            client.newCall(request).execute().close()
        }
        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertEquals("3.1.9", request.header("VersionCode"))
            assertEquals("Android_ilife798_3.1.9", request.header("user-agent"))
            assertEquals("1,1", request.header("ApplicationType"))
        }
    }

    @Test
    fun `update channel identifies original android app and protocol version`() {
        assertEquals(
            mapOf("appid" to "com.cloudora.android", "version" to "3.1.9", "type" to "1"),
            Api.appUpdateQuery(),
        )
    }
}
