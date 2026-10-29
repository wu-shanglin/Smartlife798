package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test

class DevicePageOwnerTest {
    @Test fun `outgoing page cannot restart polling owned by the new detail`() {
        val state = DevicePageOwner()
        val outgoing = Any()
        val incoming = Any()
        state.enter(outgoing, "device-a")
        state.enter(incoming, "device-b")
        assertFalse(state.leave(outgoing))
        assertEquals("device-b", state.deviceId)
        assertTrue(state.leave(incoming))
        assertNull(state.deviceId)
    }

    @Test fun `same device pages and a cleared account also reject stale disposal`() {
        val state = DevicePageOwner()
        val outgoing = Any()
        val incoming = Any()
        state.enter(outgoing, "device-a")
        state.enter(incoming, "device-a")
        assertFalse(state.leave(outgoing))
        assertEquals("device-a", state.deviceId)
        state.clear()
        assertFalse(state.leave(incoming))
        assertNull(state.deviceId)
    }
}
