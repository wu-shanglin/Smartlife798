package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceEntryTest {
    private fun seed(
        id: String = "target",
        favorites: List<Device> = emptyList(),
        recents: List<RecentDevice> = emptyList(),
        active: ActiveUsage? = null,
        live: Device? = null,
    ) = deviceEntrySnapshot(id, favorites, recents, active, live)

    @Test
    fun `favourite supplies the existing full identity without rebuilding a model`() {
        val device = Device(id = "target", name = "known", bm = DeviceModel(dtype = 7))
        assertSame(device, seed(favorites = listOf(device)))
        assertEquals(7, seed(favorites = listOf(device))?.dtype)
    }

    @Test
    fun `other device live state cannot replace the clicked identity`() {
        val device = Device(id = "target", name = "clicked")
        assertSame(device, seed(favorites = listOf(device), live = Device(id = "other")))
        assertNull(seed(live = Device(id = "other")))
    }

    @Test
    fun `live identity is available when the device is not favourited`() {
        val device = Device(id = "target", name = "live")
        assertSame(device, seed(live = device))
    }

    @Test
    fun `recent-only source preserves name and type without inventing server state`() {
        val recent = RecentDevice("target", "recent", 7, 1L)
        val device = seed(recents = listOf(recent))!!
        assertEquals("recent", device.name)
        assertEquals(7, device.dtype)
        assertNull(device.gene)
        assertNull(device.ep)
    }

    @Test
    fun `active-only source matches id and does not infer running or billing facts`() {
        val active = ActiveUsage("target", "active", 7, 1L, 21)
        val device = seed(active = active)!!
        assertEquals("active", device.name)
        assertEquals(7, device.dtype)
        assertNull(device.gene)
        assertNull(seed(id = "other", active = active))
    }

    @Test
    fun `unknown and cleared-account inputs supply no cached identity`() {
        assertNull(seed())
        assertNull(seed(id = "", favorites = listOf(Device(id = ""))))
        assertNull(seed(favorites = listOf(Device(id = "other"))))
    }
}
