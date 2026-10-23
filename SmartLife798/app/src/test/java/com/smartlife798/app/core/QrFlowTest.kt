package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scanning a dispenser QR must end in the device page, not in a "not a device
 * code" rejection.
 *
 * Vendor codes (`https://i.hnkzy.com/q/1/<serial>`) are resolved server-side and
 * may come back without `qr.type`; the payload `dev` is what matters then.
 */
class QrFlowTest {

    private fun device(id: String = "861089059737339") = Device(
        id = id,
        name = "purifier",
        bm = DeviceModel(dtype = DeviceType.WATER_PURIFIER),
        gene = Gene(status = DeviceType.Status.LOCKED),
    )

    @Test
    fun `a resolved device is accepted when the server omits the qr type`() {
        val outcome = resolveQr(device(), serverType = null, localType = 4)
        assertTrue(outcome is QrResolution.Resolved)
        assertEquals("861089059737339", (outcome as QrResolution.Resolved).device.id)
    }

    @Test
    fun `server device and door lock types are accepted`() {
        assertTrue(resolveQr(device(), serverType = QR_TYPE_DEVICE, localType = 4) is QrResolution.Resolved)
        assertTrue(resolveQr(device(), serverType = QR_TYPE_DOOR_LOCK, localType = 4) is QrResolution.Resolved)
    }

    @Test
    fun `a declared non device type is not turned into a device`() {
        // The server said "recharge card" (1): a `dev` payload must not override it.
        val outcome = resolveQr(device(), serverType = 1, localType = 4)
        assertTrue(outcome is QrResolution.OtherType)
        assertEquals(1, (outcome as QrResolution.OtherType).type)
    }

    @Test
    fun `device and door lock codes without a device payload are reported as missing`() {
        assertTrue(resolveQr(null, serverType = QR_TYPE_DEVICE, localType = 4) is QrResolution.NoDevice)
        assertTrue(resolveQr(null, serverType = QR_TYPE_DOOR_LOCK, localType = 3) is QrResolution.NoDevice)
        // A blank id is not usable either.
        assertTrue(resolveQr(device(id = "  "), serverType = QR_TYPE_DEVICE, localType = 4) is QrResolution.NoDevice)
    }

    @Test
    fun `a promo code without a device falls back to the local type`() {
        val outcome = resolveQr(null, serverType = null, localType = 4)
        assertTrue(outcome is QrResolution.OtherType)
        assertEquals(4, (outcome as QrResolution.OtherType).type)
    }

    @Test
    fun `ending another device must not clear the running bookmark`() {
        assertTrue(activeBelongsTo("A", "A"))
        assertFalse(activeBelongsTo("A", "B"))
        assertFalse(activeBelongsTo(null, "B"))
    }

    @Test
    fun `a second device is refused while another session owns the bookmark`() {
        assertTrue(blockedByOtherSession("A", "B"))
        assertFalse(blockedByOtherSession("A", "A"))
        assertFalse(blockedByOtherSession(null, "B"))
    }
}
