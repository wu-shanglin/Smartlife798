package com.smartlife798.app.core

/** Merge a bounded bill window into the local index without undoing newer local activity. */
fun mergeRecentDevices(local: List<RecentDevice>, bills: List<Bill>): List<RecentDevice> {
    val cloud = LinkedHashMap<String, RecentDevice>()
    bills.sortedByDescending { it.ctime }.forEach { bill ->
        val device = bill.dev?.takeIf { it.id.isNotBlank() } ?: return@forEach
        val latest = cloud[device.id]
        cloud[device.id] = RecentDevice(
            id = device.id,
            name = latest?.name?.takeIf { it.isNotBlank() } ?: device.name,
            dtype = latest?.dtype?.takeIf { it != 0 } ?: device.dtype,
            lastUsedAt = latest?.lastUsedAt ?: bill.ctime,
            uses = (latest?.uses ?: 0) + 1,
            scannedOnly = false,
        )
    }
    val localById = local.associateBy { it.id }
    val merged = cloud.values.map { row ->
        val previous = localById[row.id]
        row.copy(
            name = row.name.ifBlank { previous?.name ?: "未命名设备" },
            dtype = row.dtype.takeIf { it != 0 } ?: previous?.dtype ?: 0,
            lastUsedAt = maxOf(row.lastUsedAt, previous?.lastUsedAt ?: 0L),
            // Local starts may already appear in these bills; adding counts would double-count them.
            uses = maxOf(row.uses, previous?.uses ?: 0),
        )
    } + local.filter { it.id !in cloud }
    return merged.sortedByDescending { it.lastUsedAt }.take(60)
}
