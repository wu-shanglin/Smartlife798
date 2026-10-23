package com.smartlife798.app.core

/**
 * Query parameters for the device command endpoints.
 *
 * `rcp`（回执）是这里唯一不能想当然的参数：传 `true` 时服务端会把设备的原始帧塞进
 * `data` 返回，而**机器不会执行命令**（2026-10-21 用真实设备复现：`gene.status` 一直是
 * `99`，换成 `rcp=false` 后 2 秒内变成 `1`）。原客户端的 `l4.a.C1347a` 默认就是
 * `false`，之前的实现照 `T0(..., Boolean.TRUE, ...)` 把 `upgrade` 误读成了 `rcp`。
 */
object DeviceCommands {
    /** `GET /dev/start?did=&upgrade=&ptype=&args=&rcp=&cnt=` */
    fun startQuery(
        did: String,
        payType: Int,
        args: String,
        receipt: Boolean = false,
        count: Int = 1,
        upgrade: Boolean = true,
    ): Map<String, String> = mapOf(
        "did" to did,
        "upgrade" to upgrade.toString(),
        "ptype" to payType.toString(),
        "args" to args,
        "rcp" to receipt.toString(),
        "cnt" to count.toString(),
    )

    /** `GET /dev/end?did=&rcp=` */
    fun endQuery(did: String, receipt: Boolean = false): Map<String, String> =
        mapOf("did" to did, "rcp" to receipt.toString())
}
