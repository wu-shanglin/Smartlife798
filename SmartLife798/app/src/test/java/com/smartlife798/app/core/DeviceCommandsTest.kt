package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression pin for the start command: `rcp` must be `false`.
 *
 * With `rcp=true` the service answers with the device frame in `data` and the
 * machine does not run (observed on a real dispenser: `gene.status` stayed 99).
 * The official client passes `false` by default.
 */
class DeviceCommandsTest {

    @Test
    fun `start asks for no receipt so the machine executes`() {
        val q = DeviceCommands.startQuery("861089059737339", 91, "")
        assertEquals("861089059737339", q["did"])
        assertEquals("91", q["ptype"])
        assertEquals("", q["args"])
        assertEquals("false", q["rcp"])
        assertEquals("1", q["cnt"])
        assertEquals("true", q["upgrade"])
    }

    @Test
    fun `end also defaults to no receipt`() {
        val q = DeviceCommands.endQuery("d1")
        assertEquals("d1", q["did"])
        assertEquals("false", q["rcp"])
    }

    @Test
    fun `receipt stays available for diagnostics`() {
        assertEquals("true", DeviceCommands.startQuery("d1", 21, "", receipt = true)["rcp"])
        assertEquals("true", DeviceCommands.endQuery("d1", receipt = true)["rcp"])
    }
}
