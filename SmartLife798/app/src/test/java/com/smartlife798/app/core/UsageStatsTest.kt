package com.smartlife798.app.core

import com.google.gson.JsonParser
import com.smartlife798.app.core.SingleAttemptTransportTest.Loopback
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsTest {
    @Test
    fun reportingDayChangesAtUtcEightMidnight() {
        val boundary = Instant.parse("2026-11-17T16:00:00Z").toEpochMilli()
        assertEquals("2026-11-17", UsageStats.reportingDay(boundary - 1))
        assertEquals("2026-11-18", UsageStats.reportingDay(boundary))
    }

    @Test
    fun sendsOnlyTheEventFieldsAndDoesNotRepeatFailedWrites() = runBlocking {
        val day = "2026-11-17"
        val installation = "8193160d-8063-4de9-a49b-1d65ff243cca"
        Loopback(204).use { server ->
            assertTrue(UsageStats.report(server.origin, day, installation, startAccepted = false))
            assertEquals("POST /api/usage/v1 HTTP/1.1", server.firstRequest.get())
            val active = JsonParser.parseString(server.firstBody.get()).asJsonObject
            assertEquals(setOf("kind", "day", "installation"), active.keySet())
            assertEquals("active", active["kind"].asString)
            assertEquals(day, active["day"].asString)
            assertEquals(installation, active["installation"].asString)
        }
        Loopback(503).use { server ->
            assertFalse(UsageStats.report(server.origin, day, installation, startAccepted = true))
            assertEquals(1, server.requests.get())
            val start = JsonParser.parseString(server.firstBody.get()).asJsonObject
            assertEquals(setOf("kind", "day", "installation", "eventId"), start.keySet())
            assertEquals("start_accepted", start["kind"].asString)
            assertEquals(4, UUID.fromString(start["eventId"].asString).version())
        }
    }
}
