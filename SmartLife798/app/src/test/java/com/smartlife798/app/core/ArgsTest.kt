package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** `dev/start` and `dev/mode/start` must receive exactly the original JSON shape. */
class ArgsTest {

    @Test
    fun `mode without goods has no ext array`() {
        assertEquals("{\"mode\":13}", Args.modeWithGoods(13, emptyList()))
    }

    @Test
    fun `mode with goods serializes every shelf slot`() {
        val items = listOf(
            ShelfItem(pos = 0, out = 1),
            ShelfItem(pos = 2, out = 3),
        )
        assertEquals(
            "{\"mode\":13,\"ext\":[{\"pos\":0,\"out\":1},{\"pos\":2,\"out\":3}]}",
            Args.modeWithGoods(13, items),
        )
    }

    @Test
    fun `missing mode means an empty args payload`() {
        assertEquals("", Args.posAndMode(0, null))
    }

    @Test
    fun `pos and mode encode the channel index`() {
        assertEquals("{\"pos\":2,\"mode\":13}", Args.posAndMode(2, 13))
        assertEquals("{\"pos\":0,\"mode\":0}", Args.posAndMode(0, 0))
    }

    @Test
    fun `touch payload is constant`() {
        assertEquals("{\"touch\":1}", Args.touch())
    }

    @Test
    fun `water machines send an empty args payload`() {
        // Hot-water machines expose no selectable parts; a leftover gene.mode
        // must not leak into dev/start.
        val shower = Device(id = "d1", bm = DeviceModel(dtype = DeviceType.WATER_SHOWER))
        assertEquals("", Args.forDevice(shower, 5))
        assertEquals("", Args.forDevice(shower, null))
    }

    @Test
    fun `mode args require a selectable part`() {
        val washer = Device(
            id = "d2",
            bm = DeviceModel(
                dtype = DeviceType.WASHING_MACHINE,
                parts = listOf(DevicePart(mode = 13, rate = 2.0)),
            ),
        )
        assertEquals("{\"mode\":13}", Args.forDevice(washer, 13))
        assertEquals("", Args.forDevice(washer, 0))
        assertEquals("", Args.forDevice(washer, null))

        // A residue mode from the server that the machine does not expose must
        // not leak into dev/start either.
        val other = washer.copy(
            bm = DeviceModel(
                dtype = DeviceType.WASHING_MACHINE,
                parts = listOf(DevicePart(mode = 11, rate = 2.0)),
            ),
        )
        assertEquals("", Args.forDevice(other, 13))
    }

    @Test
    fun `mode args include the goods shelf`() {
        val washer = Device(
            id = "d3",
            bm = DeviceModel(
                dtype = DeviceType.WASHING_MACHINE,
                parts = listOf(DevicePart(mode = 13, rate = 2.0)),
            ),
            gs = GoodsShelf(listOf(ShelfItem(pos = 0, out = 1))),
        )
        assertEquals(
            "{\"mode\":13,\"ext\":[{\"pos\":0,\"out\":1}]}",
            Args.forDevice(washer, 13),
        )
    }
}
