package com.smartlife798.app.core

/**
 * Payment channel types observed in `payItems` (`ui/app/dev/home/1`) and
 * `bill/pay/channels`. Kept in one place so the detail screen, the pay sheet and
 * [PaymentFlow] cannot drift apart.
 */
object PayType {
    const val WECHAT = 11
    const val ALIPAY = 21
    const val BESTPAY = 31
    const val UNIONPAY = 41
    const val CMB = 51
    const val ABC = 52
    const val MERCHANT_WALLET = 91
    const val BALANCE = 92
    const val ONE_CARD = 93

    private val names = mapOf(
        WECHAT to "微信支付",
        ALIPAY to "支付宝",
        BESTPAY to "翼支付",
        UNIONPAY to "云闪付",
        CMB to "招商银行",
        ABC to "农业银行",
        MERCHANT_WALLET to "商家钱包",
        BALANCE to "账户余额",
        ONE_CARD to "一卡通",
    )

    /** Canonical display names, for surfaces that render every channel at once. */
    val known: Map<Int, String> get() = names

    /** Server-side wallet channels: settled by the backend, no third-party SDK. */
    fun isWallet(type: Int): Boolean =
        type == MERCHANT_WALLET || type == BALANCE || type == ONE_CARD

    /** Channels this client can actually complete end to end. */
    fun isSupportedHere(type: Int): Boolean = isWallet(type) || type == ALIPAY || type == WECHAT

    fun label(type: Int, fallback: String? = null): String =
        names[type] ?: fallback?.takeIf { it.isNotBlank() } ?: "支付方式 $type"
}

/**
 * Which channel to preselect, per the product rule:
 * balance first, Alipay (免密/协议支付) when the balance cannot cover it.
 */
object PayChoice {
    /** Bill payment: wallet when it covers [amount], else Alipay, else wallet/first. */
    fun forBill(channels: List<PayChannel>, balance: Double, amount: Double): PayChannel? {
        val wallet = channels.firstOrNull { PayType.isWallet(it.type) }
        val alipay = channels.firstOrNull { it.type == PayType.ALIPAY }
        return when {
            wallet != null && balance + 1e-9 >= amount -> wallet
            alipay != null -> alipay
            wallet != null -> wallet
            else -> channels.firstOrNull()
        }
    }

    /**
     * `dev/start` ptype: the wallet channel when the account has balance,
     * otherwise Alipay. With no balance and no Alipay, fall back to what the
     * device offers (the server still decides whether it can be charged).
     */
    fun devicePayType(payItems: List<Int>, balance: Double): Int? {
        val wallet = payItems.firstOrNull { PayType.isWallet(it) }
        val alipay = payItems.firstOrNull { it == PayType.ALIPAY }
        return when {
            wallet != null && balance > 0.0 -> wallet
            alipay != null -> alipay
            wallet != null -> wallet
            else -> payItems.firstOrNull()
        }
    }
}

/** `bill.status` values observed on the production service. */
object BillStatus {
    const val UNPAID = 1
    const val CONFIRMING = 2
    const val PAID = 3
    const val PAY_FAILED = 4
    const val SETTLING = 5
    const val CANCELLED = 9

    private val names = mapOf(
        UNPAID to "未付款",
        CONFIRMING to "待确认",
        PAID to "已付款",
        PAY_FAILED to "付款失败",
        SETTLING to "核算中",
        CANCELLED to "已取消",
    )

    fun label(status: Int): String = names[status] ?: "状态 $status"

    /** A bill the user still has to pay for (also what the bottom badge counts). */
    fun isUnpaid(status: Int): Boolean = status == UNPAID || status == PAY_FAILED

    /** Bills that no longer need a payment action. */
    fun isSettled(status: Int): Boolean = status == PAID || status == CANCELLED
}

val Bill.isUnpaid: Boolean get() = BillStatus.isUnpaid(status)

val Bill.statusLabel: String get() = BillStatus.label(status)

/** Pagination bookkeeping shared by the repository layer and its tests. */
object Paging {
    const val PAGE_SIZE = 20

    /**
     * The UI counts pages from 1, the production endpoint counts from 0.
     * Passing the UI number straight through made page 1 read as page 2 and
     * returned an empty list — the "no consumption records" symptom of v1.1.0.
     */
    fun toApiPage(uiPage: Int): Int = (uiPage - 1).coerceAtLeast(0)
}
