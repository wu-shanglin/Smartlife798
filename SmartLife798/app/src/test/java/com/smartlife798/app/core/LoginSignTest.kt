package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSignTest {
    @Test
    fun reproducesOriginalSmsSignaturesAcrossTheBucketBoundary() {
        assertEquals("ffd2ec0bdb0b09d19c65989bf9ab6608",
            LoginSign.sign("10000000000", "123456", 1_794_395_790_000))
        assertEquals("a9a58907570ff8101e74c9ba38a511f1",
            LoginSign.sign("10012345678", "001234", 1_794_395_819_999))
        assertEquals("daa007d403aee4542961d9c93b03be3a",
            LoginSign.sign("10012345678", "001234", 1_794_395_820_000))
    }

    @Test
    fun acceptsDecimalStringsAndRejectsAmbiguousCanonicalFields() {
        assertTrue(LoginSign.validCredentials("10012345678", "001234"))
        assertFalse(LoginSign.validCredentials("1001234567", "001234"))
        assertFalse(LoginSign.validCredentials("10012345678", "1&un=0"))
        assertFalse(LoginSign.validCredentials("10012345678", ""))
        assertFalse(LoginSign.validCredentials("１００１２３４５６７８", "001234"))
    }
}
