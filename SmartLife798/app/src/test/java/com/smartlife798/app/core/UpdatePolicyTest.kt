package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
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
