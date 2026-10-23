package com.smartlife798.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-start button lock must never outlive the session it is protecting.
 * Regression: a plain 20s timeout kept 「立即使用」 disabled after the user
 * ended the order (or the machine returned to standby) within the window.
 */
class StartLockTest {

    private fun locked(
        state: DeviceStateKind = DeviceStateKind.ONLINE,
        seenInUse: Boolean = false,
        pendingUntil: Long = 20_000L,
        now: Long = 10_000L,
        activeDeviceId: String? = "d1",
        deviceId: String = "d1",
    ): Boolean = StartCommandLock.isLocked(
        state = state,
        seenInUse = seenInUse,
        pendingUntil = pendingUntil,
        now = now,
        activeDeviceId = activeDeviceId,
        deviceId = deviceId,
    )

    @Test
    fun `locks a start that the server has not reflected yet`() {
        assertTrue(locked())
    }

    @Test
    fun `unlocks when the fallback window expires`() {
        assertFalse(locked(now = 20_000L))
        assertFalse(locked(now = 25_000L))
    }

    @Test
    fun `ending the usage unlocks immediately`() {
        // 结束使用（首页/其它客户端/设备本身）会清掉本机 active 记录，
        // 不能因为还在窗口内就让「立即使用」继续不可用。
        assertFalse(locked(activeDeviceId = null))
        assertFalse(locked(activeDeviceId = "another-device"))
    }

    @Test
    fun `seeing the device in use unlocks`() {
        // 设备已进入启动中/运行中：按钮由 STOP 接管；即使随后回到待机，
        // 也不能再用旧窗口锁住新的 START。
        assertFalse(locked(seenInUse = true))
    }

    @Test
    fun `non standby states are never locked by the start window`() {
        assertFalse(locked(state = DeviceStateKind.STARTING))
        assertFalse(locked(state = DeviceStateKind.RUNNING))
        assertFalse(locked(state = DeviceStateKind.OFFLINE))
        assertFalse(locked(state = DeviceStateKind.DISABLED))
        assertFalse(locked(state = DeviceStateKind.BLUETOOTH))
    }
}
