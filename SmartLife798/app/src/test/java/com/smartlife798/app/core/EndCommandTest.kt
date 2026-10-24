package com.smartlife798.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EndCommandTest {
    private val running = Device(id = "water", name = "饮水机", status = 1,
        bm = DeviceModel(dtype = DeviceType.WATER_PURIFIER), gene = Gene(status = 10))
    private fun status(gene: Int) = DeviceStatus(id = running.id, status = 1, gene = Gene(status = gene))

    @Test fun `accepted end stays pending through old states then returns standby`() = runBlocking {
        val command = EndCommand()
        var sent = 0
        var reads = 0
        var pauses = 0
        val observed = mutableListOf<Device>()
        val result = command.execute(running, send = { sent++; Res.Ok(Unit) }, read = {
            assertTrue(command.isBusy)
            Res.Ok(status(listOf(10, 1, 99)[reads++]))
        }, observed = { observed += it }, pause = { pauses++ })
        assertEquals(1, sent)
        assertEquals(3, reads)
        assertEquals(2, pauses)
        assertEquals(listOf(10, 1, 99), observed.map { it.geneStatus })
        val confirmed = (result as Res.Ok).data!!
        assertEquals(PrimaryAction.START, confirmed.primaryAction())
        assertEquals(running.name, confirmed.name)
        assertEquals(running.bm, confirmed.bm)
        assertFalse(command.isBusy)
    }

    @Test fun `immediate standby unlocks without an artificial delay`() = runBlocking {
        val result = EndCommand().execute(running, { Res.Ok(Unit) }, { Res.Ok(status(99)) }, {},
            pause = { error("Must not delay after confirmed standby") })
        assertTrue(result is Res.Ok)
    }

    @Test fun `rejected end never polls or retries`() = runBlocking {
        val failure = Res.Fail(ErrorCodes.DEVICE_RUNNING, "请按结算")
        val result = EndCommand().execute(running, { failure }, { error("No status read") }, {})
        assertEquals(failure, result)
    }

    @Test fun `missing foreign offline fault and running states cannot report success`() = runBlocking {
        for (snapshot in listOf(null, status(99).copy(id = "other"), status(10),
            status(99).copy(status = 0), status(98), DeviceStatus(id = running.id))) {
            var sent = 0
            val result = EndCommand().execute(running, { sent++; Res.Ok(Unit) }, { Res.Ok(snapshot) }, {}, {})
            assertEquals(ErrorCodes.END_UNCONFIRMED, (result as Res.Fail).code)
            assertEquals(1, sent)
        }
        // The input may be stale standby; a response missing gene is still no confirmation.
        val stale = running.copy(gene = Gene(status = 99))
        val result = EndCommand().execute(stale, { Res.Ok(Unit) },
            { Res.Ok(DeviceStatus(id = running.id, status = 1)) }, {}, {})
        assertEquals(ErrorCodes.END_UNCONFIRMED, (result as Res.Fail).code)
    }

    @Test fun `status network failures are bounded without resending the command`() = runBlocking {
        var sent = 0
        var reads = 0
        val result = EndCommand().execute(running, { sent++; Res.Ok(Unit) }, {
            reads++; Res.Fail(ErrorCodes.NETWORK, "offline")
        }, {}, {})
        assertEquals(ErrorCodes.END_UNCONFIRMED, (result as Res.Fail).code)
        assertEquals(EndCommand.ATTEMPTS, reads)
        assertEquals(1, sent)
    }

    @Test fun `expired session terminates confirmation`() = runBlocking {
        var reads = 0
        val result = EndCommand().execute(running, { Res.Ok(Unit) }, {
            reads++; Res.Fail(ErrorCodes.TOKEN_EXPIRED, "expired")
        }, {}, {})
        assertEquals(ErrorCodes.TOKEN_EXPIRED, (result as Res.Fail).code)
        assertEquals(1, reads)
    }

    @Test fun `duplicate entry is rejected and cancellation releases the command`() = runBlocking {
        val command = EndCommand()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sent = 0
        val job = launch {
            command.execute(running, { sent++; entered.complete(Unit); release.await(); Res.Ok(Unit) },
                { Res.Ok(status(99)) }, {})
        }
        entered.await()
        val duplicate = async {
            command.execute(running, { sent++; Res.Ok(Unit) }, { Res.Ok(status(99)) }, {})
        }.await()
        assertTrue(duplicate is Res.Fail)
        assertEquals(1, sent)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(command.isBusy)
        assertTrue(command.execute(running, { Res.Ok(Unit) }, { Res.Ok(status(99)) }, {}) is Res.Ok)
    }

    @Test fun `cancelling accepted end during confirmation never reports success or resends`() = runBlocking {
        val command = EndCommand()
        val reading = CompletableDeferred<Unit>()
        val blockedRead = CompletableDeferred<Res<DeviceStatus>>()
        var sent = 0
        var completed = false
        val job = launch {
            command.execute(running, { sent++; Res.Ok(Unit) }, {
                reading.complete(Unit)
                blockedRead.await()
            }, {})
            completed = true
        }
        reading.await()
        job.cancelAndJoin()
        assertEquals(1, sent)
        assertFalse(completed)
        assertFalse(command.isBusy)
    }
}
