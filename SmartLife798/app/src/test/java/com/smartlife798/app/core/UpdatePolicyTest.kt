package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class UpdatePolicyTest {
    @Test fun explicitReleaseArtifactAndLegacyDefaultAreRestricted() {
        assertEquals("SmartLife798-1.7.0-debug.apk", UpdatePolicy.parse(json()).apkName)
        val release = json().dropLast(1) + ",\"apkName\":\"SmartLife798-1.7.0-release.apk\"}"
        assertEquals("SmartLife798-1.7.0-release.apk", UpdatePolicy.parse(release).apkName)
        assertNull(UpdatePolicy.cached(release.replace("SmartLife798-1.7.0-release.apk", "../evil.apk")))
    }

    @Test fun pollingRepeatsAndStopsWhenCancelled() = kotlinx.coroutines.runBlocking {
        val third = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            AppUpdate.poll(intervalMillis = 5) {
                calls += 1
                if (calls == 3) third.complete(Unit)
            }
        }
        kotlinx.coroutines.withTimeout(2_000) { third.await() }
        job.cancel()
        job.join()
        val stoppedAt = calls
        kotlinx.coroutines.delay(20)
        assertEquals(stoppedAt, calls)
        assertTrue(calls >= 3)
    }

    @Test fun sourceFallbackOrderFailureAndCancellation() = kotlinx.coroutines.runBlocking {
        assertEquals(UpdateSource.GITHUB, UpdateSource.fromName("unknown"))
        val attempts = mutableListOf<UpdateSource>()
        val result = AppUpdate.fetchWith(UpdateSource.GITHUB) { source ->
            attempts += source
            if (source == UpdateSource.GITHUB) throw java.io.IOException()
            "valid"
        }
        assertEquals(listOf(UpdateSource.GITHUB, UpdateSource.GH_PROXY), attempts)
        assertEquals(UpdateSource.GH_PROXY, result.source)
        assertEquals(listOf(UpdateSource.GH_PROXY, UpdateSource.GITHUB), UpdateSource.GH_PROXY.attempts())
        attempts.clear()
        try {
            AppUpdate.fetchWith(UpdateSource.GITHUB) { attempts += it; throw kotlinx.coroutines.CancellationException() }
            fail("cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf(UpdateSource.GITHUB), attempts)
        try { AppUpdate.fetchWith(UpdateSource.GITHUB) { throw java.io.IOException() }; fail("failure swallowed") }
        catch (_: java.io.IOException) { }
        assertEquals("https://gh-proxy.org/${UpdatePolicy.REPOSITORY}/releases/latest/download/update.json",
            UpdateSource.GH_PROXY.url("${UpdatePolicy.REPOSITORY}/releases/latest/download/update.json"))
        try { UpdateSource.GH_PROXY.url("https://i.ilife798.com/api"); fail("business URL accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun cacheIdentitySeparatesVersionsAndArtifactBytes() {
        val policy = UpdatePolicy(54, "1.18.2", 37, "", "a".repeat(64))
        assertNotEquals(policy.cacheApkName, policy.copy(versionCode = 55, versionName = "1.18.3").cacheApkName)
        assertNotEquals(policy.cacheApkName, policy.copy(sha256 = "b".repeat(64)).cacheApkName)
        assertEquals(policy.cacheApkName, policy.copy(notes = "说明改变").cacheApkName)
    }

    @Test fun cacheIdentityRejectsUnsafePathFields() {
        val policy = UpdatePolicy(54, "1.18.2", 37, "", "a".repeat(64))
        for (invalid in listOf(policy.copy(versionName = "../1.18.2"),
            policy.copy(sha256 = "../other"), policy.copy(versionCode = -1))) {
            try { invalid.cacheApkName; fail("Unsafe cache identity accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun temporaryHistoryFailureKeepsMatchingCachedNotesButUsesFreshMinimum() {
        val fresh = json(minimum = "1")
        val cached = json(minimum = "24").dropLast(1) +
            """, "releases":[{"versionName":"1.7.0","notes":"历史"}]}"""
        val policy = UpdatePolicy.parse(AppUpdate.retainHistory(fresh, cached))
        assertEquals("1.7.0\n历史", policy.notesSince("1.6.0"))
        assertFalse(policy.required(23))
        assertEquals(fresh, AppUpdate.retainHistory(fresh, cached.replace("a".repeat(64), "b".repeat(64))))
    }

    @Test fun historyAssetMustMatchTargetAndCannotChangeUpdatePolicy() {
        val base = json(minimum = "24")
        val history = """{"versionName":"1.7.0","versionCode":999,"releases":[{"versionName":"1.7.0","notes":"目标说明"}]}"""
        val merged = UpdatePolicy.parse(AppUpdate.mergeHistory(base, history))
        assertEquals(24, merged.versionCode)
        assertTrue(merged.required(23))
        assertEquals("1.7.0\n目标说明", merged.notesSince("1.6.0"))
        listOf(history.replace("\"versionName\":\"1.7.0\",\"versionCode\"", "\"versionName\":\"1.7.1\",\"versionCode\""), "{}")
            .forEach {
                try { AppUpdate.mergeHistory(base, it); fail("Accepted wrong history") }
                catch (_: RuntimeException) { }
            }
    }

    @Test fun skippedVersionsAreFilteredAndSortedNumerically() {
        val history = """, "releases":[
            {"versionName":"1.7.8","notes":"八"},
            {"versionName":"1.7.10","notes":"十"},
            {"versionName":"1.7.7","notes":"已安装"},
            {"versionName":"1.7.11","notes":"未来"},
            {"versionName":"1.7.9","notes":"九"}]"""
        val policy = UpdatePolicy.parse(json(name = "1.7.10").dropLast(1) + history + "}")
        assertEquals("1.7.10\n十\n\n1.7.9\n九\n\n1.7.8\n八", policy.notesSince("1.7.7"))
    }

    @Test fun missingOrMalformedHistoryKeepsLegacyNotesAndMandatoryPolicy() {
        val base = json(minimum = "24")
        val histories = listOf("", "null", "{}", "[]", "[null]",
            """[{"versionName":"1.7.0","notes":""}]""",
            """[{"versionName":"1.6.0","notes":"没有最新版"}]""",
            """[{"versionName":"1.7.0","notes":"a"},{"versionName":"1.7.0","notes":"b"}]""")
        histories.forEach { history ->
            val policy = UpdatePolicy.parse(if (history.isEmpty()) base else base.dropLast(1) + ",\"releases\":$history}")
            assertTrue(policy.required(23))
            assertEquals("修复与改进", policy.notesSince("1.6.0"))
        }
    }

    private fun json(code: String = "24", minimum: String = "1", name: String = "1.7.0") =
        """{"versionCode":$code,"versionName":"$name","minSupportedVersionCode":$minimum,"notes":"修复与改进","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""

    @Test fun normalUpdateCanBeDeferred() {
        val policy = UpdatePolicy.parse(json())
        assertTrue(policy.available(22))
        assertFalse(policy.required(22))
        assertEquals("https://github.com/wu-shanglin/Smartlife798/releases/tag/v1.7.0", policy.releaseUrl)
    }

    @Test fun minimumOnlyBlocksAffectedVersions() {
        val policy = UpdatePolicy.parse(json(minimum = "23"))
        assertTrue(policy.required(22))
        assertFalse(policy.required(23))
        assertTrue(policy.available(23))
        assertFalse(policy.available(24))
        assertFalse(policy.required(24))
        assertFalse(policy.available(25))
    }

    @Test fun versionCodeDeterminesOrder() {
        assertTrue(UpdatePolicy.parse(json(name = "1.6.9")).available(23))
        assertFalse(UpdatePolicy.parse(json(code = "20", name = "9.0.0")).available(23))
    }

    @Test fun invalidMetadataCannotBlockUsers() {
        val invalid = listOf(
            "null", "[]", "{}", "<html>404</html>",
            json(code = "0"), json(code = "-1"), json(code = "2147483648"),
            json(code = "24.5"), json(code = "24.0"), json(code = "\"24\""),
            json(minimum = "25"), json(minimum = "0"), json(minimum = "null"),
            json(name = "1.7.0-beta"), json(name = "../evil"), json().replace("a".repeat(64), "bad"), json(name = "01.7.0"),
            json().replace("\"修复与改进\"", "null"),
            json().replace("修复与改进", "a".repeat(4001)),
        )
        invalid.forEach { assertNull(it.take(80), UpdatePolicy.cached(it)) }
        assertNull(UpdatePolicy.cached(null))
    }

    @Test fun cachedPolicyKeepsMandatoryDecisionOffline() {
        val policy = UpdatePolicy.cached(json(minimum = "24"))
        assertNotNull(policy)
        assertTrue(policy!!.required(23))
        assertFalse(policy.required(24))
    }
}
