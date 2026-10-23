package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingTest {

    @Test
    fun `wallet channels are the server-settled ones`() {
        assertTrue(PayType.isWallet(PayType.MERCHANT_WALLET))
        assertTrue(PayType.isWallet(PayType.BALANCE))
        assertTrue(PayType.isWallet(PayType.ONE_CARD))
        assertFalse(PayType.isWallet(PayType.ALIPAY))
        assertFalse(PayType.isWallet(PayType.WECHAT))
    }

    @Test
    fun `alipay wallets and wechat are completable in this client`() {
        assertTrue(PayType.isSupportedHere(PayType.ALIPAY))
        assertTrue(PayType.isSupportedHere(PayType.BALANCE))
        assertTrue(PayType.isSupportedHere(PayType.WECHAT))
        assertFalse(PayType.isSupportedHere(PayType.UNIONPAY))
        assertFalse(PayType.isSupportedHere(PayType.CMB))
        assertFalse(PayType.isSupportedHere(PayType.ABC))
    }

    @Test
    fun `channel labels fall back to the server name then a generic label`() {
        assertEquals("支付宝", PayType.label(PayType.ALIPAY))
        assertEquals("账户余额", PayType.label(PayType.BALANCE))
        assertEquals("校园卡", PayType.label(777, "校园卡"))
        assertEquals("支付方式 777", PayType.label(777))
        // A blank server name must not produce an empty chip.
        assertEquals("支付方式 777", PayType.label(777, "   "))
    }

    @Test
    fun `unpaid covers statuses that still need money`() {
        assertTrue(BillStatus.isUnpaid(BillStatus.UNPAID))
        assertTrue(BillStatus.isUnpaid(BillStatus.PAY_FAILED))
        assertFalse(BillStatus.isUnpaid(BillStatus.CONFIRMING))
        assertFalse(BillStatus.isUnpaid(BillStatus.PAID))
        assertFalse(BillStatus.isUnpaid(BillStatus.SETTLING))
        assertFalse(BillStatus.isUnpaid(BillStatus.CANCELLED))
    }

    @Test
    fun `settled bills no longer need a payment action`() {
        assertTrue(BillStatus.isSettled(BillStatus.PAID))
        assertTrue(BillStatus.isSettled(BillStatus.CANCELLED))
        assertFalse(BillStatus.isSettled(BillStatus.UNPAID))
        assertFalse(BillStatus.isSettled(BillStatus.PAY_FAILED))
        assertFalse(BillStatus.isSettled(BillStatus.SETTLING))
    }

    @Test
    fun `status labels cover the observed range`() {
        assertEquals("未付款", BillStatus.label(1))
        assertEquals("待确认", BillStatus.label(2))
        assertEquals("已付款", BillStatus.label(3))
        assertEquals("付款失败", BillStatus.label(4))
        assertEquals("核算中", BillStatus.label(5))
        assertEquals("已取消", BillStatus.label(9))
        assertEquals("状态 77", BillStatus.label(77))
    }

    @Test
    fun `bill extensions agree with the status helpers`() {
        val unpaid = Bill(id = "b1", status = BillStatus.UNPAID)
        val paid = Bill(id = "b2", status = BillStatus.PAID)
        assertTrue(unpaid.isUnpaid)
        assertFalse(paid.isUnpaid)
        assertEquals("已付款", paid.statusLabel)
    }

    @Test
    fun `ui page one maps to api page zero`() {
        // This single conversion is the v1.2.0 "empty consumption history" fix.
        assertEquals(0, Paging.toApiPage(1))
        assertEquals(1, Paging.toApiPage(2))
        assertEquals(9, Paging.toApiPage(10))
    }

    @Test
    fun `out of range ui pages are clamped, never negative`() {
        assertEquals(0, Paging.toApiPage(0))
        assertEquals(0, Paging.toApiPage(-5))
    }
}
