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
    fun `dynamic original version applies atomically to both headers and original update query`() {
        OriginalAppVersion.restore("3.1.10")
        try {
            val client = Api.client.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("3.1.10", request.header("VersionCode"))
                assertEquals("Android_ilife798_3.1.10", request.header("user-agent"))
                assertEquals("3.1.10", request.url.queryParameter("version"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("{}".toResponseBody()).build()
            }.build()
            client.newCall(Request.Builder().url(Api.BASE_URL + "/api/v1/sys/app/upt?version=3.1.5").build()).execute().close()
            assertEquals("3.1.10", Api.appUpdateQuery()["version"])
        } finally { OriginalAppVersion.restore(null) }
    }

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
            assertEquals("3.1.10", request.header("VersionCode"))
            assertEquals("Android_ilife798_3.1.10", request.header("user-agent"))
            assertEquals("1,1", request.header("ApplicationType"))
        }
    }

    @Test
    fun `signed request snapshot pins both headers while discovery advances`() {
        OriginalAppVersion.restore("3.1.10")
        try {
            var observed = false
            val fixture = Api.client.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("3.1.9", request.header("VersionCode"))
                assertEquals("Android_ilife798_3.1.9", request.header("user-agent"))
                observed = true
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("{}".toResponseBody()).build()
            }.build()
            // Inspect the same immutable request metadata used by checkIn; do not mutate Api.client.
            @Suppress("UNCHECKED_CAST")
            val snapshotType = Class.forName("com.smartlife798.app.core.Api\$RequestSession") as Class<Any>
            val constructor = snapshotType.getDeclaredConstructor(String::class.java, String::class.java)
                .apply { isAccessible = true }
            val snapshot = constructor.newInstance(null, "3.1.9")
            fixture.newCall(Request.Builder().url(Api.BASE_URL + "/api/v1/acc/score/score-send")
                .tag(snapshotType, snapshot).build()).execute().close()
            org.junit.Assert.assertTrue(observed)
        } finally { OriginalAppVersion.restore(null) }
    }

    @Test
    fun `update channel identifies original android app and protocol version`() {
        assertEquals(
            mapOf("appid" to "com.cloudora.android", "version" to "3.1.10", "type" to "1"),
            Api.appUpdateQuery(),
        )
    }
}
