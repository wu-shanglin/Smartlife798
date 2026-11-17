package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCommandSignTest {
    private val token = "abcdefghijklmnop"
    private val uid = "owner-0123456789"
    private val serverTimeMs = 1_792_944_010_000L

    // Fixed vectors produced with the independent Rust-backed Python blake3 library.
    @Test
    fun `start digest form encodes arguments and sorts command keys`() {
        val query = DeviceCommands.startQuery("fixture-1", 91, "模式 A+B & = >")
        assertEquals(
            "07f5cc946073de9db69f558388cd93a6",
            DeviceCommandSign.sign(query, token, uid, "/api/v1/dev/start", serverTimeMs),
        )
    }

    @Test
    fun `end changes its digest at the next server time window`() {
        val query = DeviceCommands.endQuery("fixture-1")
        val endpoint = "/api/v1/dev/end"
        assertEquals(
            "5652e6e06130bded2b187ea47f16bae7",
            DeviceCommandSign.sign(query, token, uid, endpoint, 1_792_944_029_999L),
        )
        assertEquals(
            "8a75b673e4b108b12390d71256cf911a",
            DeviceCommandSign.sign(query, token, uid, endpoint, 1_792_944_030_000L),
        )
    }

    @Test
    fun `missing server time or session identity cannot produce a digest`() {
        val query = DeviceCommands.endQuery("fixture-1")
        val endpoint = "/api/v1/dev/end"
        assertNull(DeviceCommandSign.sign(query, token, uid, endpoint, 0))
        assertNull(DeviceCommandSign.sign(query, "short", uid, endpoint, serverTimeMs))
        assertNull(DeviceCommandSign.sign(query, token, "short", endpoint, serverTimeMs))
    }
}
