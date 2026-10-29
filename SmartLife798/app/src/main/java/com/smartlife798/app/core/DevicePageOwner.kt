package com.smartlife798.app.core

/** Incoming and outgoing detail pages coexist during a transition. Only the current owner can leave. */
internal class DevicePageOwner {
    private var page: Pair<Any, String>? = null
    val deviceId: String? @Synchronized get() = page?.second

    @Synchronized fun enter(owner: Any, deviceId: String) { page = owner to deviceId }
    @Synchronized fun leave(owner: Any): Boolean {
        if (page?.first !== owner) return false
        page = null
        return true
    }
    @Synchronized fun clear() { page = null }
}
