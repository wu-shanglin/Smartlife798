package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test

class ConfirmedCheckInTest {
    private val server = 1793016000000L // 2026-10-26 20:00 Shanghai
    private val local = 1800000000000L

    @Test fun restartDuringSameServerDayUsesConfirmation() {
        assertTrue(ConfirmedCheckIn(server, local).isCurrent(local + 60_000))
    }

    @Test fun midnightAndClockRollbackInvalidateConfirmation() {
        val cache = ConfirmedCheckIn(server, local)
        assertFalse(cache.isCurrent(local + 4 * 60 * 60_000))
        assertFalse(cache.isCurrent(local - 1))
        assertFalse(cache.isCurrent(local + 86_400_000))
        assertFalse(ConfirmedCheckIn().isCurrent(local))
    }
}
