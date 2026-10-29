package com.smartlife798.app.core

/** In-memory presentation seed only; never authorizes a device command or payment. */
fun deviceEntrySnapshot(
    id: String,
    favorites: List<Device>,
    recents: List<RecentDevice>,
    active: ActiveUsage?,
    live: Device?,
    resolved: Device? = null,
): Device? {
    if (id.isBlank()) return null
    favorites.firstOrNull { it.id == id }?.let { return it }
    resolved?.takeIf { it.id == id }?.let { return it }
    live?.takeIf { it.id == id }?.let { return it }
    recents.firstOrNull { it.id == id }?.let {
        return Device(id = id, name = it.name, bm = DeviceModel(dtype = it.dtype))
    }
    active?.takeIf { it.deviceId == id }?.let {
        return Device(id = id, name = it.name, bm = DeviceModel(dtype = it.dtype))
    }
    return null
}
