package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentFlowTest {

    @Test
    fun `channel labels match the server vocabulary`() {
        assertEquals("微信支付", PaymentFlow.channelLabel(11, null))
        assertEquals("支付宝", PaymentFlow.channelLabel(21, null))
        assertEquals("云闪付", PaymentFlow.channelLabel(41, null))
        assertEquals("招商银行", PaymentFlow.channelLabel(51, null))
        assertEquals("农业银行", PaymentFlow.channelLabel(52, null))
        assertEquals("商家钱包", PaymentFlow.channelLabel(91, null))
        assertEquals("账户余额", PaymentFlow.channelLabel(92, null))
        assertEquals("一卡通", PaymentFlow.channelLabel(93, null))
    }

    @Test
    fun `unknown channels prefer the server name`() {
        assertEquals("校园卡", PaymentFlow.channelLabel(777, "校园卡"))
        assertEquals("支付方式 777", PaymentFlow.channelLabel(777, null))
    }

    @Test
    fun `wallet detection is shared with PayType`() {
        (0..200).forEach { type ->
            assertEquals(PayType.isWallet(type), PaymentFlow.isWalletChannel(type))
        }
        assertTrue(PaymentFlow.isWalletChannel(92))
        assertFalse(PaymentFlow.isWalletChannel(21))
    }

    @Test
    fun `payment outcome variants carry their message`() {
        assertEquals("ok", (PaymentFlow.Outcome.Paid("ok") as PaymentFlow.Outcome.Paid).message)
        assertEquals("no", (PaymentFlow.Outcome.Failed("no") as PaymentFlow.Outcome.Failed).message)
        assertEquals(
            "later",
            (PaymentFlow.Outcome.Unavailable("later") as PaymentFlow.Outcome.Unavailable).message,
        )
    }
    @org.junit.Test
    fun `sdk receipts and untrusted status never reach messages or diagnostics`() {
        val receipt = "fixture-private-signed-receipt"
        val raw = mapOf("resultStatus" to receipt, "memo" to receipt, "result" to receipt)
        val unknown = AlipaySdk.fromRaw(raw)
        org.junit.Assert.assertEquals("unknown", unknown.status)
        org.junit.Assert.assertFalse(unknown.success)
        org.junit.Assert.assertFalse(unknown.toString().contains(receipt))
        for (status in listOf("9000", "8000", "4000", "5000", "6001", "6002", "6004")) {
            val parsed = AlipaySdk.fromRaw(raw + ("resultStatus" to status))
            org.junit.Assert.assertEquals(status == "9000", parsed.success)
            org.junit.Assert.assertFalse(parsed.toString().contains(receipt))
        }
    }
}
