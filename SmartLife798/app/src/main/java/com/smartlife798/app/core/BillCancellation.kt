package com.smartlife798.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Send close once. Only an authoritative same-bill status 9 proves cancellation. */
object BillCancellation {
    suspend fun execute(
        id: String,
        read: suspend () -> Res<BillDetail>,
        close: suspend () -> Res<Unit>,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Res<Unit> {
        if (id.isBlank()) return Res.Fail(-1, "订单编号为空")
        val before = read()
        if (before is Res.Fail) return before
        val bill = (before as Res.Ok).data?.bill
        if (bill?.id != id) return Res.Fail(-1, "账单响应不匹配，请刷新后重试")
        if (bill.status == BillStatus.CANCELLED) return Res.Ok(Unit)
        if (!bill.isUnpaid) return Res.Fail(-1, "当前订单不可取消，请刷新查看状态")
        val closed = close()
        if (closed is Res.Fail && closed.code != ErrorCodes.NETWORK) {
            return Res.Fail(closed.code, "取消未确认：${closed.msg}，请刷新订单核对")
        }
        var latest: Bill? = null
        return withTimeoutOrNull(15_000L) {
            repeat(4) { attempt ->
                if (attempt > 0) pause(1_000L)
                when (val response = read()) {
                    is Res.Fail -> {
                        latest = null
                        if (response.code == ErrorCodes.TOKEN_EXPIRED) return@withTimeoutOrNull response
                    }
                    is Res.Ok -> {
                        latest = response.data?.bill?.takeIf { it.id == id }
                        when (latest?.status) {
                            BillStatus.CANCELLED -> return@withTimeoutOrNull Res.Ok(Unit)
                            BillStatus.PAID -> return@withTimeoutOrNull Res.Fail(-1, "服务端显示订单已付款，不能取消；请勿重复支付")
                            BillStatus.CONFIRMING, BillStatus.SETTLING -> return@withTimeoutOrNull Res.Fail(-1, "订单正在确认或结算，取消未确认；请刷新核对，勿重复支付")
                        }
                    }
                }
            }
            val unpaid = latest?.takeIf { it.isUnpaid }
            if (unpaid != null) Res.Fail(-1, "取消未生效：服务端仍显示“${unpaid.statusLabel}”。关闭支付交易未能取消此账单，请联系服务方核实")
            else unconfirmed()
        } ?: unconfirmed()
    }

    private fun unconfirmed() = Res.Fail(-1, "取消结果待确认，请刷新订单核对；请勿重复支付")
}
