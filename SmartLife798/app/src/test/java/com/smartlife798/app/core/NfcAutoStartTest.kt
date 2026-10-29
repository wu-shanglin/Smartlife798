package com.smartlife798.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcAutoStartTest {
    @Test fun onePhysicalEventCanAuthorizeOnlyOneStartForItsResolvedDevice() {
        val gate = NfcAutoStart()
        gate.issue("tap-a", "device-a")
        assertFalse(gate.claim("tap-a", "device-b"))
        assertTrue(gate.claim("tap-a", "device-a"))
        assertFalse(gate.claim("tap-a", "device-a"))
    }

    @Test fun backgroundOrAccountResetAndNewProcessCannotReplayAnOldRoute() {
        val gate = NfcAutoStart()
        gate.issue("tap-a", "device-a")
        gate.clear()
        assertFalse(gate.claim("tap-a", "device-a"))
        assertFalse(NfcAutoStart().claim("tap-a", "device-a"))
    }

    @Test fun aLaterDeliberateTapSupersedesOlderPendingAuthority() {
        val gate = NfcAutoStart()
        gate.issue("tap-a", "device-a")
        gate.issue("tap-b", "device-a")
        assertFalse(gate.claim("tap-a", "device-a"))
        assertTrue(gate.claim("tap-b", "device-a"))
    }
}
