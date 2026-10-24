package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dev/start` only queues a command; the machine has to leave standby before the
 * client may call it running. Regression: an accepted start for a dispenser that
 * never ran was recorded as 「使用中」, then silently cleared twelve seconds later.
 */
class StartWatchTest {

    private fun device(
        status: Int = 1,
        geneStatus: Int? = 99,
        dtype: Int = DeviceType.WATER_PURIFIER,
    ): Device = Device(
        id = "d1",
        name = "饮水机",
        status = status,
        bm = DeviceModel(dtype = dtype),
        gene = Gene(status = geneStatus ?: 0),
    ).let { if (geneStatus == null) it.copy(gene = null) else it }

    @Test
    fun `an idle water machine is only standby`() {
        assertEquals(StartSignal.STANDBY, StartWatch.classify(device(geneStatus = 99)))
    }

    @Test
    fun `every in-use water state confirms the start`() {
        // 1 开机 / 30 准备中 / 10 运行中 / 20 冲洗中 都表示服务端已把设备拉起来。
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 1)))
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 30)))
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 10)))
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 20)))
    }

    @Test
    fun `offline and disabled machines never confirm a start`() {
        assertEquals(StartSignal.ABNORMAL, StartWatch.classify(device(status = 0)))
        assertEquals(StartSignal.ABNORMAL, StartWatch.classify(device(geneStatus = 98)))
    }

    @Test
    fun `a missing snapshot or unknown state stays unresolved`() {
        assertEquals(StartSignal.UNKNOWN, StartWatch.classify(null))
        assertEquals(StartSignal.UNKNOWN, StartWatch.classify(device(geneStatus = null)))
        // 在线但既不是待机也不是在用（例如 gene 状态缺失）不能被当成已启动。
        assertEquals(
            StartSignal.UNKNOWN,
            StartWatch.classify(device(dtype = DeviceType.VENDING_MACHINE, geneStatus = 0)),
        )
    }

    @Test
    fun `the advertised window matches the number of reads`() {
        // 1 s first delay + 5 × 2 s spacing.
        assertEquals(11, StartWatch.ceilingSeconds)
    }

    @Test
    fun `only families whose start moves the status are confirmed by polling`() {
        // 水控 / 淋浴 / 饮水机、洗衣机 / 烘干机 / 洗鞋机、吹风机、充电桩都会离开 99。
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.WATER_METER)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.WATER_SHOWER)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.WATER_PURIFIER)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.WASHING_MACHINE)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.DRY_MACHINE)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.SHOES_MACHINE)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.HAIR_DRIER)))
        assertTrue(StartWatch.isObservable(device(dtype = DeviceType.CHARGING_MACHINE)))
        // 消费机 / 售货机 / 门禁可能在 99 下就能用，不能用状态否决启动。
        assertFalse(StartWatch.isObservable(device(dtype = DeviceType.POS_MACHINE)))
        assertFalse(StartWatch.isObservable(device(dtype = DeviceType.VENDING_MACHINE)))
        assertFalse(StartWatch.isObservable(device(dtype = DeviceType.ACCESS_CONTROL)))
    }

    @Test
    fun `a recorded session survives only while the server reports it running`() {
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 10)))
        assertEquals(StartSignal.STARTED, StartWatch.classify(device(geneStatus = 30)))
        assertEquals(StartSignal.STANDBY, StartWatch.classify(device(geneStatus = 99)))
        assertEquals(StartSignal.ABNORMAL, StartWatch.classify(device(status = 0)))
        assertEquals(StartSignal.ABNORMAL, StartWatch.classify(device(geneStatus = 98)))
    }

    @Test
    fun `a partial status snapshot keeps the dtype of the known device`() {
        // `dev/status` sends a DeviceStatus (no `bm`): without merging,
        // `gene.status = 1` reads as 「在线」 (dtype 0) and a running machine
        // looks idle.
        val known = device(geneStatus = 99, dtype = DeviceType.WATER_PURIFIER)
        val partial = DeviceStatus(
            id = "d1",
            status = 1,
            gene = Gene(status = DeviceType.Status.ON),
        )
        val merged = known.mergeStatus(partial)
        assertEquals(DeviceType.WATER_PURIFIER, merged.dtype)
        assertEquals(DeviceType.Status.ON, merged.geneStatus)
        assertEquals(DeviceStateKind.STARTING, merged.stateLabel().kind)
        assertTrue(StartWatch.classify(merged) == StartSignal.STARTED)
    }

    @Test
    fun `merging tolerates missing or incomplete snapshots`() {
        val known = device(geneStatus = 99)
        assertEquals(known, known.mergeStatus(null))
        // A snapshot without `gene` must not erase the state we already know.
        val noGene = known.mergeStatus(DeviceStatus(id = "d1", status = 1))
        assertEquals(DeviceType.Status.LOCKED, noGene.geneStatus)
        // Missing `status` must not be read as offline (0) either.
        assertEquals(1, known.copy(status = 1).mergeStatus(DeviceStatus(id = "d1")).status)
    }

    @Test
    fun `a running device round trips through statusOnly`() {
        val known = device(geneStatus = 30, dtype = DeviceType.WATER_PURIFIER)
        val merged = known.mergeStatus(known.statusOnly())
        assertEquals(known, merged)
        assertEquals(DeviceType.WATER_PURIFIER, merged.dtype)
    }
    @Test
    fun `foreign and unidentified snapshots never alter the known device`() {
        val known = device(geneStatus = 10)
        for (id in listOf("other", "", null)) {
            val foreign = DeviceStatus(id = id, status = 1, gene = Gene(status = 99))
            assertEquals(known, known.mergeStatus(foreign))
            assertEquals(StartSignal.UNKNOWN, StartWatch.classify(known, foreign))
        }
    }

    @Test
    fun `incomplete state cannot confirm or erase a usage from cached fields`() {
        val known = device(geneStatus = 99)
        for (partial in listOf(null, DeviceStatus(id = "d1"),
            DeviceStatus(id = "d1", status = 1), DeviceStatus(id = "d1", gene = Gene(status = 10)))) {
            assertEquals(StartSignal.UNKNOWN, StartWatch.classify(known, partial))
        }
        assertEquals(StartSignal.STARTED, StartWatch.classify(known,
            DeviceStatus(id = "d1", status = 1, gene = Gene(status = 10))))
    }
}
