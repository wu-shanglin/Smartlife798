package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OriginalAppVersionTest {
    @Test fun cancellingMetadataRefreshCancelsTheHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("metadata".toResponseBody()).build()
        }.build()
        try {
            val job = launch { client.fetchPublicText("https://example.invalid/metadata", 1024) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun listing(version: String = "3.1.10", pkg: String = "com.cloudora.android") = """
        <div>版本号</div><div>$version</div>
        <div>开发者</div><div>长沙云多啦信息技术有限公司</div>
        <div>包名</div><div><div>$pkg</div></div><div>应用权限</div>
    """.trimIndent()

    @Test fun listingReadsVersionForTheOriginalPackageAndDeveloper() {
        assertEquals("3.1.10", OriginalAppVersion.parseListing(listing()))
        assertEquals("3.1.10", OriginalAppVersion.parseListing("<script>版本号 9.9.9</script>" + listing()))
    }

    @Test fun invalidOrAmbiguousDistributionDataIsRejected() {
        listOf(listing("3.1.10-beta"), listing("03.1.10"), listing("999999999.0.0"),
            listing(pkg = "other.app"), listing(pkg = "com.cloudora.android.fake"),
            listing().replace("长沙云多啦信息技术有限公司", "其他开发者"),
            listing() + "<div>版本号</div><div>3.1.11</div>", "<html>维护中</html>", "a".repeat(262_145))
            .forEach { html ->
                try { OriginalAppVersion.parseListing(html); fail("Accepted invalid listing") }
                catch (_: IllegalArgumentException) { }
                catch (_: NoSuchElementException) { }
            }
    }

    @Test fun cachedAndDiscoveredVersionsNeverDowngradeTheBundledFallback() {
        assertEquals("3.1.10", OriginalAppVersion.select("3.1.10", "3.1.9"))
        assertEquals("3.1.10", OriginalAppVersion.select("3.1.9", "3.1.10"))
        listOf(null, "garbage", "999999.1.1", "1.7.6").forEach {
            assertEquals("3.1.9", OriginalAppVersion.select(it, "3.1.9"))
        }
    }

    @Test fun responseLimitsIncludeUnknownLengthAndExactBoundary() {
        assertEquals(5, ByteArrayInputStream(ByteArray(5)).readBytesLimited(5).size)
        try { ByteArrayInputStream(ByteArray(6)).readBytesLimited(5); fail("Accepted oversized body") }
        catch (_: IOException) { }
    }

    @Test fun previewRejectsPublicFetchBeforeAnyNetwork() = runBlocking {
        Preview.enabled = true
        try {
            try { OriginalAppVersion.fetch(); fail("Preview fetched original version") }
            catch (_: IllegalStateException) { }
            try { AppUpdate.fetch(); fail("Preview fetched update metadata") }
            catch (_: IllegalStateException) { }
        } finally { Preview.enabled = false }
    }
}
