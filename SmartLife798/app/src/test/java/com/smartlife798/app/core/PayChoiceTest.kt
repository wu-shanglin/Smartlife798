package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Default channel rules: balance first, Alipay (免密/协议) when it cannot cover. */
class PayChoiceTest {

    private fun channel(type: Int) = PayChannel(type = type)

    @Test
    fun `bill uses the wallet when it covers the amount`() {
        val chosen = PayChoice.forBill(
            channels = listOf(channel(PayType.ALIPAY), channel(PayType.MERCHANT_WALLET)),
            balance = 10.0,
            amount = 5.0,
        )
        assertEquals(PayType.MERCHANT_WALLET, chosen?.type)
    }

    @Test
    fun `bill falls back to alipay when the balance is short`() {
        val chosen = PayChoice.forBill(
            channels = listOf(channel(PayType.MERCHANT_WALLET), channel(PayType.ALIPAY)),
            balance = 0.0,
            amount = 5.0,
        )
        assertEquals(PayType.ALIPAY, chosen?.type)
    }

    @Test
    fun `bill keeps the wallet when alipay is not offered`() {
        val chosen = PayChoice.forBill(listOf(channel(PayType.MERCHANT_WALLET)), balance = 0.0, amount = 5.0)
        assertEquals(PayType.MERCHANT_WALLET, chosen?.type)
    }

    @Test
    fun `bill with no channels selects nothing`() {
        assertNull(PayChoice.forBill(emptyList(), balance = 10.0, amount = 5.0))
    }

    @Test
    fun `device start prefers the wallet with balance and alipay otherwise`() {
        val items = listOf(PayType.MERCHANT_WALLET, PayType.ALIPAY)
        assertEquals(PayType.MERCHANT_WALLET, PayChoice.devicePayType(items, balance = 8.0))
        assertEquals(PayType.ALIPAY, PayChoice.devicePayType(items, balance = 0.0))
    }

    @Test
    fun `device start without alipay still uses the wallet`() {
        assertEquals(
            PayType.MERCHANT_WALLET,
            PayChoice.devicePayType(listOf(PayType.MERCHANT_WALLET), balance = 0.0),
        )
        assertNull(PayChoice.devicePayType(emptyList(), balance = 8.0))
    }
}
