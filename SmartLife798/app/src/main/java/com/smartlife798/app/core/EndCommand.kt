package com.smartlife798.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** One end command, followed only by status reads until standby is observed. */
class EndCommand {
    private val mutex = Mutex()
    val isBusy: Boolean get() = mutex.isLocked

    suspend fun execute(
        device: Device,
        send: suspend () -> Res<Unit>,
        read: suspend () -> Res<DeviceStatus>,
        observed: (Device) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Res<Device> {
        if (!mutex.tryLock()) return Res.Fail(-1, "正在结束使用，请勿重复操作")
        try {
            val accepted = send()
            if (accepted is Res.Fail) return accepted
            repeat(ATTEMPTS) { attempt ->
                if (attempt > 0) pause(INTERVAL_MS)
                when (val result = read()) {
                    is Res.Ok -> {
                        // Empty / foreign snapshots cannot confirm this command.
                        val status = result.data?.takeIf { it.id == device.id } ?: return@repeat
                        val snapshot = device.mergeStatus(status)
                        observed(snapshot)
                        if (status.gene?.status == DeviceType.Status.LOCKED && snapshot.isStandby) {
                            return Res.Ok(snapshot)
                        }
                        if (snapshot.isAbnormal) return unconfirmed()
                    }
                    is Res.Fail -> if (result.code == ErrorCodes.TOKEN_EXPIRED) return result
                }
            }
            return unconfirmed()
        } finally {
            mutex.unlock()
        }
    }

    private fun unconfirmed() = Res.Fail(
        ErrorCodes.END_UNCONFIRMED,
        "结束请求已受理，但尚未确认设备恢复待机。请刷新设备状态，账单以服务端结算为准。",
    )

    companion object {
        const val ATTEMPTS = 6
        const val INTERVAL_MS = 2_000L
    }
}
