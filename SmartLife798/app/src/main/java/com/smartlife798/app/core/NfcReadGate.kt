package com.smartlife798.app.core

/** One discovery per physical presence. Old pause/removal callbacks cannot unlock a new session. */
internal class NfcReadGate {
    private var generation = 0L
    private var active = false
    private var handling = false

    @Synchronized fun start(): Long {
        generation += 1
        active = true
        handling = false
        return generation
    }

    @Synchronized fun stop(): Boolean {
        val wasActive = active
        generation += 1
        active = false
        handling = false
        return wasActive
    }

    @Synchronized fun isCurrent(session: Long): Boolean = active && session == generation

    @Synchronized fun claim(session: Long): Boolean {
        if (!active || session != generation || handling) return false
        handling = true
        return true
    }

    @Synchronized fun removed(session: Long) {
        if (active && session == generation) handling = false
    }
}
