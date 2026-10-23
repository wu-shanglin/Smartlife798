package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device status -> user-visible state -> primary button, as decoded from the APK. */
class DeviceStateTest {

    private fun device(
        status: Int = 1,
        ntype: Int = 1,
        dtype: Int = DeviceType.WATER_METER,
        geneStatus: Int = DeviceType.Status.ON,
    ) = Device(
        id = "d1",
        name = "test",
        status = status,
        ntype = ntype,
        bm = DeviceModel(dtype = dtype),
        gene = Gene(status = geneStatus),
    )

    @Test
    fun `offline devices are reported as offline`() {
        val state = device(status = 0).stateLabel()
        assertEquals(DeviceStateKind.OFFLINE, state.kind)
        assertEquals("离线", state.label)
    }

    @Test
    fun `bluetooth transports are separated from plain offline`() {
        assertEquals(DeviceStateKind.BLUETOOTH, device(status = 0, ntype = 3).stateLabel().kind)
        assertEquals(DeviceStateKind.BLUETOOTH, device(status = 0, ntype = 10).stateLabel().kind)
        assertEquals(DeviceStateKind.OFFLINE, device(status = 0, ntype = 4).stateLabel().kind)
    }

    @Test
    fun `locked and force locked map to online and disabled`() {
        assertEquals(DeviceStateKind.ONLINE, device(geneStatus = DeviceType.Status.LOCKED).stateLabel().kind)
        assertEquals("在线", device(geneStatus = DeviceType.Status.LOCKED).stateLabel().label)

        assertEquals(DeviceStateKind.DISABLED, device(geneStatus = DeviceType.Status.FORCE_LOCKED).stateLabel().kind)
        assertEquals("禁用", device(geneStatus = DeviceType.Status.FORCE_LOCKED).stateLabel().label)
    }

    @Test
    fun `working and flushing are both running`() {
        assertEquals(DeviceStateKind.RUNNING, device(geneStatus = DeviceType.Status.WORK).stateLabel().kind)
        assertEquals("运行中", device(geneStatus = DeviceType.Status.WORK).stateLabel().label)
        assertEquals("冲洗中", device(geneStatus = DeviceType.Status.FLUSH).stateLabel().label)
    }

    @Test
    fun `prepare keeps every device in an in-use state that offers stop`() {
        val washer = device(dtype = DeviceType.WASHING_MACHINE, geneStatus = DeviceType.Status.PREPARE)
        assertEquals(DeviceStateKind.STARTING, washer.stateLabel().kind)
        assertEquals("启动中", washer.stateLabel().label)
        assertEquals(PrimaryAction.STOP, washer.primaryAction())

        val meter = device(dtype = DeviceType.WATER_METER, geneStatus = DeviceType.Status.PREPARE)
        assertEquals(DeviceStateKind.STARTING, meter.stateLabel().kind)
        assertEquals("准备中", meter.stateLabel().label)
        assertEquals(PrimaryAction.STOP, meter.primaryAction())

        val shower = device(dtype = DeviceType.WATER_SHOWER, geneStatus = DeviceType.Status.ON)
        assertEquals(DeviceStateKind.STARTING, shower.stateLabel().kind)
        assertEquals(PrimaryAction.STOP, shower.primaryAction())
    }

    @Test
    fun `only standby devices offer a new start`() {
        val standby = device(geneStatus = DeviceType.Status.LOCKED)
        assertEquals(DeviceStateKind.ONLINE, standby.stateLabel().kind)
        assertEquals(PrimaryAction.START, standby.primaryAction())

        // A water machine that still reports ON/PREPARE after a start must not
        // accept a second dev/start (the server answers 设备准备中，请稍后再试).
        for (status in listOf(DeviceType.Status.ON, DeviceType.Status.PREPARE, DeviceType.Status.WORK)) {
            assertEquals(
                "gene.status=$status",
                PrimaryAction.STOP,
                device(dtype = DeviceType.WATER_PURIFIER, geneStatus = status).primaryAction(),
            )
        }
    }

    @Test
    fun `fault and offline states are abnormal and never polled`() {
        assertTrue(device(status = 0).isAbnormal)
        assertTrue(device(geneStatus = DeviceType.Status.ERR).isAbnormal)
        assertTrue(device(geneStatus = DeviceType.Status.FORCE_LOCKED).isAbnormal)
        assertTrue(device(status = 0, ntype = 3).isAbnormal)
        assertFalse(device(geneStatus = DeviceType.Status.LOCKED).isAbnormal)
        assertFalse(device(geneStatus = DeviceType.Status.PREPARE).isAbnormal)

        assertTrue(device(geneStatus = DeviceType.Status.PREPARE).isInUse)
        assertTrue(device(geneStatus = DeviceType.Status.WORK).isInUse)
        assertTrue(device(geneStatus = DeviceType.Status.FLUSH).isInUse)
        assertFalse(device(geneStatus = DeviceType.Status.LOCKED).isInUse)
        assertFalse(device(geneStatus = DeviceType.Status.ERR).isInUse)
    }

    @Test
    fun `errors are disabled with the fault label`() {
        val state = device(geneStatus = DeviceType.Status.ERR).stateLabel()
        assertEquals(DeviceStateKind.DISABLED, state.kind)
        assertEquals("故障", state.label)
    }

    @Test
    fun `primary action follows the state table`() {
        assertEquals(PrimaryAction.OFFLINE, device(status = 0).primaryAction())
        assertEquals(PrimaryAction.LINKING, device(status = 0, ntype = 3).primaryAction())
        assertEquals(
            PrimaryAction.DISABLED,
            device(geneStatus = DeviceType.Status.FORCE_LOCKED).primaryAction(),
        )
        assertEquals(PrimaryAction.STOP, device(geneStatus = DeviceType.Status.WORK).primaryAction())
        assertEquals(PrimaryAction.STOP, device(geneStatus = DeviceType.Status.FLUSH).primaryAction())
        assertEquals(PrimaryAction.START, device(geneStatus = DeviceType.Status.LOCKED).primaryAction())
    }

    @Test
    fun `device type helpers stay in sync with the catalogue`() {
        assertTrue(DeviceType.isWaterLike(DeviceType.WATER_METER))
        assertTrue(DeviceType.isWaterLike(DeviceType.WATER_SHOWER))
        assertFalse(DeviceType.isWaterLike(DeviceType.WASHING_MACHINE))
        assertTrue(DeviceType.isWashingLike(DeviceType.DRY_MACHINE))
        assertTrue(DeviceType.isWashingLike(DeviceType.SHOES_MACHINE))
        assertFalse(DeviceType.isGenericDetail(DeviceType.ACCESS_CONTROL))
        assertTrue(DeviceType.isGenericDetail(DeviceType.WATER_METER))
        assertEquals("智能设备", DeviceType.name(-1))
        assertEquals("洗衣机", DeviceType.name(DeviceType.WASHING_MACHINE))
        // Partial payloads (dtype 0) must be able to render without 「智能设备」.
        assertNull(DeviceType.nameOrNull(0))
        assertNull(DeviceType.nameOrNull(-1))
        assertEquals("洗衣机", DeviceType.nameOrNull(DeviceType.WASHING_MACHINE))
    }

    @Test
    fun `shelf labels are stable`() {
        assertEquals(listOf("收藏", "常用", "最近"), DevicesShelf.entries.map { it.label })
    }
}
