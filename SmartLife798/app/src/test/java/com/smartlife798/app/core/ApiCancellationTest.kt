package com.smartlife798.app.core

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual loopback sockets prove cancellation before headers and while consuming a partial body. */
class ApiCancellationTest {
    @Test fun cancellationClosesTheCallWithoutDecodingOrFollowingUp() = runBlocking {
        for (partialBody in listOf(false, true)) {
            HoldingResponse(partialBody).use { server ->
                val exchanges = AtomicInteger()
                val decoded = AtomicInteger()
                val failed = CountDownLatch(1)
                val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                    override fun requestHeadersStart(call: Call) { exchanges.incrementAndGet() }
                    override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
                }).build()
                val call = Api.singleAttemptWriteCall(client, Request.Builder().url(server.url)
                    .post("{}".toRequestBody()).build())
                try {
                    val job = launch {
                        Api.awaitCall(call) { _, _ -> decoded.incrementAndGet() }
                    }
                    assertTrue(withContext(Dispatchers.IO) { server.entered.await(5, TimeUnit.SECONDS) })
                    job.cancelAndJoin()
                    assertTrue(call.isCanceled())
                    assertTrue(withContext(Dispatchers.IO) { failed.await(5, TimeUnit.SECONDS) })
                    assertEquals(0, decoded.get())
                    assertEquals(1, exchanges.get())
                    assertEquals(1, server.requests.get())
                } finally {
                    server.release.countDown()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    withContext(Dispatchers.IO) { client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS) }
                }
            }
        }
    }

    private class HoldingResponse(private val partialBody: Boolean) : Closeable {
        private val listener = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            soTimeout = 5_000
        }
        val url = "http://127.0.0.1:${listener.localPort}/write"
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(input.readLine()?.startsWith("POST /write ") == true)
                    var length = 0
                    while (true) {
                        val header = input.readLine() ?: error("Incomplete request")
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { check(input.read() >= 0) }
                    requests.incrementAndGet()
                    if (partialBody) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.set(error)
            }
        }, "api-cancellation-loopback").apply { isDaemon = true; start() }

        override fun close() {
            release.countDown()
            listener.close()
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
            check(!worker.isAlive)
        }
    }
}
