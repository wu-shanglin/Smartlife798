package com.smartlife798.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BillCancellationTest {
    private fun snapshot(status: Int, id: String = "bill"): Res<BillDetail> = Res.Ok(BillDetail(Bill(id = id, status = status)))

    @Test fun `readback can settle late but close is never repeated`() = runBlocking {
        val states = ArrayDeque(listOf(snapshot(1), snapshot(1), snapshot(4), snapshot(9)))
        var writes = 0
        var waits = 0
        assertTrue(BillCancellation.execute("bill", { states.removeFirst() }, { writes++; Res.Ok(Unit) }, { waits++ }) is Res.Ok)
        assertEquals(1, writes)
        assertEquals(2, waits)
    }

    @Test fun `a successful close that leaves bill unpaid is explicitly ineffective`() = runBlocking {
        var writes = 0
        val result = BillCancellation.execute("bill", { snapshot(1) }, { writes++; Res.Ok(Unit) }, {}) as Res.Fail
        assertTrue(result.msg.contains("取消未生效"))
        assertEquals(1, writes)
    }

    @Test fun `already cancelled is idempotent and paid or wrong bills cannot be closed`() = runBlocking {
        var writes = 0
        val close: suspend () -> Res<Unit> = { writes++; Res.Ok(Unit) }
        assertTrue(BillCancellation.execute("bill", { snapshot(9) }, close, {}) is Res.Ok)
        for (state in listOf(snapshot(3), snapshot(2), snapshot(5), snapshot(1, "other"), Res.Ok(null))) {
            assertTrue(BillCancellation.execute("bill", { state }, close, {}) is Res.Fail)
        }
        assertEquals(0, writes)
    }

    @Test fun `network acknowledgement loss is resolved only by authoritative readback`() = runBlocking {
        val states = ArrayDeque(listOf(snapshot(1), snapshot(9)))
        assertTrue(BillCancellation.execute("bill", { states.removeFirst() }, { Res.Fail(ErrorCodes.NETWORK, "offline") }, {}) is Res.Ok)
    }

    @Test fun `paid race and mismatched final snapshots never imply cancellation`() = runBlocking {
        val paid = ArrayDeque(listOf(snapshot(1), snapshot(3)))
        assertTrue((BillCancellation.execute("bill", { paid.removeFirst() }, { Res.Ok(Unit) }, {}) as Res.Fail).msg.contains("已付款"))
        var reads = 0
        val foreign = BillCancellation.execute("bill", { if (reads++ == 0) snapshot(1) else snapshot(9, "other") }, { Res.Ok(Unit) }, {})
        assertTrue((foreign as Res.Fail).msg.contains("待确认"))
    }

    @Test fun `caller cancellation propagates without another close`() = runBlocking {
        var reads = 0
        var writes = 0
        try {
            BillCancellation.execute("bill", { if (reads++ == 0) snapshot(1) else throw CancellationException() },
                { writes++; Res.Ok(Unit) }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, writes)
    }
}
