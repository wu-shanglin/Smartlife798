package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test

class DeviceAccessTest {
    private fun detail(status: Int, owner: String? = null) = DeviceDetail(
        device = Device(id = "a", status = 1, bm = DeviceModel(dtype = DeviceType.WATER_METER), gene = Gene(status = status)),
        user = owner?.let { User(id = it) },
    )

    @Test fun foreignOwnerBlocksEvenStaleStandbyAndLocalBookmark() {
        for (status in listOf(99, 1, 10, 30)) {
            val access = detail(status, "other").access("me", "a")
            assertNull(access.action)
            assertTrue(access.notice!!.contains("他人"))
        }
    }

    @Test fun ownSessionCanEndButUnknownRunningCannotBeAdopted() {
        assertEquals(PrimaryAction.STOP, detail(10, "me").access("me", null).action)
        assertEquals(PrimaryAction.STOP, detail(10).access("me", "a").action)
        assertNull(detail(10).access("me", "b").action)
        assertNull(detail(10).access("me", null).action)
        assertNull(detail(10, "me").access(null, "a").action)
    }

    @Test fun freeStandbyAndMissingData() {
        assertEquals(PrimaryAction.START, detail(99).access("me", null).action)
        assertEquals(PrimaryAction.START, detail(99, "").access("me", null).action)
        assertNull(DeviceDetail().access("me", "a").action)
    }

    @Test fun observableMachineMustHaveConfirmedStandbyBeforeStarting() {
        assertNull(detail(0).access("me", null).action)
        assertNull(detail(99).copy(device = detail(99).device?.copy(gene = null)).access("me", null).action)
        assertEquals(PrimaryAction.DISABLED, detail(98).access("me", null).action)
    }
}
