package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayLoginTest {
    @Test fun acceptsOnlySuccessfulSdkAuthorizationWithAnUnambiguousCode() {
        fun result(status: String, fields: String) =
            AlipayLogin.parseResult(mapOf("resultStatus" to status, "result" to fields))

        assertEquals(Res.Ok("fixture-01%2B"), result("9000", "auth_code=fixture-01%2B&result_code=200"))
        assertTrue(result("6001", "result_code=200&auth_code=fixture") is Res.Fail)
        assertTrue(result("9000", "result_code=100&auth_code=fixture") is Res.Fail)
        assertTrue(result("9000", "result_code=200&auth_code=") is Res.Fail)
        assertTrue(result("9000", "result_code=200&auth_code=one&auth_code=two") is Res.Fail)
        assertTrue(result("9000", "result_code=200&auth_code=bad\ncode") is Res.Fail)
    }
}
