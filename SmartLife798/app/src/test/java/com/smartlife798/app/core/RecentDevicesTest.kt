package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecentDevicesTest {
    private fun bill(id: String, time: Long, name: String = id, dtype: Int = 7) =
        Bill(ctime = time, dev = Device(id = id, name = name, bm = DeviceModel(dtype = dtype)))

    @Test fun `new scan remains ahead of older cloud history including the same device`() {
        val local = listOf(RecentDevice("scanned", "local", 7, 300L, 0, true),
            RecentDevice("used", "local used", 7, 200L, 5))
        val merged = mergeRecentDevices(local, listOf(bill("used", 100L, "server used"), bill("older", 50L)))
        assertEquals(listOf("scanned", "used", "older"), merged.map { it.id })
        assertEquals(200L, merged[1].lastUsedAt)
        assertEquals(5, merged[1].uses)
        assertEquals("server used", merged[1].name)
    }

    @Test fun `newest nonblank server identity wins regardless of bill order`() {
        val merged = mergeRecentDevices(emptyList(), listOf(bill("a", 100L, "旧名称", 1),
            bill("a", 300L, "新名称", 7), bill("a", 200L, "中间名称", 8)))
        assertEquals(RecentDevice("a", "新名称", 7, 300L, 3, false), merged.single())
    }

    @Test fun `partial identities fall back without losing local scan time or inventing counts`() {
        val local = listOf(RecentDevice("a", "local", 7, 500L, 1, true))
        val bills = listOf(bill("a", 200L, "", 0), bill("a", 100L, "", 0), Bill(), bill("", 600L))
        val row = mergeRecentDevices(local, bills).single()
        assertEquals(RecentDevice("a", "local", 7, 500L, 2, false), row)
        assertFalse(row.scannedOnly)
        assertEquals(listOf(row), mergeRecentDevices(listOf(row), bills))
    }

    @Test fun `limit keeps newest local entries before old cloud rows`() {
        val local = (1..60).map { RecentDevice("local-$it", "local", 7, it + 100L) }
        val merged = mergeRecentDevices(local, listOf(bill("old-cloud", 1L)))
        assertEquals(60, merged.size)
        assertEquals("local-60", merged.first().id)
        assertEquals("local-1", merged.last().id)
        assertEquals(merged, mergeRecentDevices(merged, emptyList()))
    }
}
