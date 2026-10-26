package com.smartlife798.app.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test

class AvatarTest {
    @Test fun acceptsOnlyDirectHttpsUrlsWithoutCredentials() {
        assertEquals("https://images.example/avatar.jpg", Avatar.url(" https://images.example/avatar.jpg ").toString())
        listOf(null, "", "/api/v1/fs/dl?q=key", "avatar-key", "http://images.example/a",
            "file:///avatar.jpg", "data:image/png;base64,a", "https://user:password@images.example/a",
            "https://images.example/a#fragment").forEach { assertNull(it, Avatar.url(it)) }
    }

    @Test fun pathologicalDimensionsAreRejectedAndNormalImagesAreSampled() {
        assertNull(Avatar.sampleSize(0, 100))
        assertNull(Avatar.sampleSize(-1, 100))
        assertNull(Avatar.sampleSize(8193, 1))
        assertNull(Avatar.sampleSize(8192, 8192))
        assertEquals(1, Avatar.sampleSize(128, 128))
        assertEquals(4, Avatar.sampleSize(1024, 512))
        assertEquals(32, Avatar.sampleSize(8192, 1024))
    }

    @Test fun publicRequestHasNoBusinessAuthorizationAndPreviewDoesNotSend() = runBlocking {
        val requests = AtomicInteger()
        val client = fixtureClient { chain ->
            requests.incrementAndGet()
            assertNull(chain.request().header("Authorization"))
            response(chain.request(), "avatar".toByteArray(), "image/png")
        }
        val previous = Preview.enabled
        try {
            Preview.enabled = false
            assertArrayEquals("avatar".toByteArray(), Avatar.fetch(client, Avatar.url("https://images.example/a")!!))
            Preview.enabled = true
            assertNull(Avatar.fetch(client, Avatar.url("https://images.example/a")!!))
            assertEquals(1, requests.get())
        } finally {
            Preview.enabled = previous
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun nonImageAndOversizedBodiesAreRejected() = runBlocking {
        val previous = Preview.enabled
        Preview.enabled = false
        val url = Avatar.url("https://images.example/a")!!
        val htmlClient = fixtureClient { response(it.request(), "<html>".toByteArray(), "text/html") }
        val largeClient = fixtureClient { response(it.request(), ByteArray(Avatar.MAX_BYTES + 1), "image/png") }
        try {
            assertNull(Avatar.fetch(htmlClient, url))
            assertNull(Avatar.fetch(largeClient, url))
        } finally {
            Preview.enabled = previous
            htmlClient.dispatcher.executorService.shutdown()
            largeClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun cancellationCancelsTheUnderlyingCall() = runBlocking {
        val previous = Preview.enabled
        Preview.enabled = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val client = fixtureClient { chain ->
            call.set(chain.call())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("offline cancellation fixture")
        }
        try {
            val job = launch { Avatar.fetch(client, Avatar.url("https://images.example/a")!!) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
            Preview.enabled = previous
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun fixtureClient(interceptor: okhttp3.Interceptor) =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(request: okhttp3.Request, bytes: ByteArray, type: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody(type.toMediaType())).build()
}
