package com.smartlife798.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcReadGateTest {
    @Test fun cardHeldOnAntennaResolvesOnceThenRemovalAllowsADeliberateRetap() {
        val gate = NfcReadGate()
        val session = gate.start()
        assertTrue(gate.claim(session))
        assertFalse(gate.claim(session))
        gate.removed(session)
        assertTrue(gate.claim(session))
    }

    @Test fun cardAlreadyGoneWhenIgnoreReturnsFalseMustNotLatchFutureTaps() {
        val gate = NfcReadGate()
        val session = gate.start()
        assertTrue(gate.claim(session))
        gate.removed(session) // ignore=false is equivalent to removal; no callback will follow.
        assertTrue(gate.claim(session))
    }

    @Test fun oldRemovalAndDiscoveryCannotReleaseOrClaimANewForegroundSession() {
        val gate = NfcReadGate()
        val old = gate.start()
        assertTrue(gate.claim(old))
        assertTrue(gate.stop())
        assertFalse(gate.isCurrent(old))
        val fresh = gate.start()
        assertTrue(gate.claim(fresh))
        gate.removed(old)
        assertFalse(gate.claim(old))
        assertFalse(gate.claim(fresh))
        gate.removed(fresh)
        assertTrue(gate.claim(fresh))
    }
}
