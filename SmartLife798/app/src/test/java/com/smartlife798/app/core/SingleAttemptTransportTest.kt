package com.smartlife798.app.core

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real loopback HTTP exchanges exercise RetryAndFollowUpInterceptor, not application stubs. */
class SingleAttemptTransportTest {
    @Test fun writeReturnsOriginal503AndRedirectFailuresWithoutSendingAgain() {
        for (status in listOf(503, 307, 308)) {
            Loopback(status).use { server ->
                val base = OkHttpClient()
                try {
                    val call = Api.singleAttemptWriteCall(base, request(server.url))
                    assertTrue(call.request().body!!.isOneShot())
                    call.execute().use { response ->
                        assertEquals("HTTP $status must remain visible to Api.request as a failure", status, response.code)
                        assertFalse(response.isSuccessful)
                    }
                    assertEquals("HTTP $status must not replay a side effect", 1, server.requests.get())
                    assertEquals("POST /write HTTP/1.1", server.firstRequest.get())
                    assertEquals("{\"weekday\":1}", server.firstBody.get())
                    server.assertHealthy()
                } finally {
                    base.connectionPool.evictAll()
                    base.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test fun fixtureExposesFollowUpsThatConnectionRetrySwitchDoesNotDisable() {
        for (status in listOf(503, 307, 308)) {
            Loopback(status).use { server ->
                // This is the previous production policy: connection retries disabled,
                // repeatable JSON body, and the default redirect behavior.
                val oldPolicy = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
                try {
                    oldPolicy.newCall(request(server.url)).execute().use { assertEquals(200, it.code) }
                    assertEquals("Fixture must actually exercise HTTP $status follow-up", 2, server.requests.get())
                    server.assertHealthy()
                } finally {
                    oldPolicy.connectionPool.evictAll()
                    oldPolicy.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    private fun request(url: String) = Request.Builder().url(url)
        .post("{\"weekday\":1}".toRequestBody()).build()

    private class Loopback(private val firstStatus: Int) : Closeable {
        private val listener = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            soTimeout = 3_000
        }
        val url = "http://127.0.0.1:${listener.localPort}/write"
        val requests = AtomicInteger()
        val firstRequest = AtomicReference<String>()
        val firstBody = AtomicReference<String>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestLine = input.readLine() ?: error("Missing HTTP request")
                        var contentLength = 0
                        while (true) {
                            val header = input.readLine() ?: error("Incomplete HTTP headers")
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = header.substringAfter(':').trim().toInt()
                            }
                        }
                        val body = CharArray(contentLength)
                        var offset = 0
                        while (offset < contentLength) {
                            val count = input.read(body, offset, contentLength - offset)
                            check(count > 0)
                            offset += count
                        }
                        val ordinal = requests.incrementAndGet()
                        if (ordinal == 1) {
                            firstRequest.set(requestLine)
                            firstBody.set(String(body))
                        }
                        val status = if (ordinal == 1) firstStatus else 200
                        val instructions = when (status) {
                            503 -> "Retry-After: 0\r\n"
                            307, 308 -> "Location: $url\r\n"
                            else -> ""
                        }
                        val response = "HTTP/1.1 $status fixture\r\n" + instructions +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(response.toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            } catch (error: SocketException) {
                if (!listener.isClosed) failure.set(error)
            } catch (error: SocketTimeoutException) {
                if (!listener.isClosed) failure.set(error)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "single-attempt-loopback").apply { isDaemon = true; start() }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        }

        override fun close() {
            listener.close()
            worker.join(3_000)
            assertHealthy()
            check(!worker.isAlive)
        }
    }
}
