package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR parser mirrors the original client byte for byte; a regression here
 * silently sends the wrong id to `qr/use`, so every branch is pinned.
 */
class QrParserTest {

    private fun parse(raw: String?) = QrParser.parse(raw)

    @Test
    fun `digits are treated as a printed card number`() {
        val parsed = parse("861089059670662")
        assertEquals(1, parsed.type)
        assertEquals("861089059670662", parsed.id)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val parsed = parse("  861089059670662 \n")
        assertEquals(1, parsed.type)
        assertEquals("861089059670662", parsed.id)
    }

    @Test
    fun `device qr link keeps the id query parameter`() {
        val parsed = parse("https://i.ilife798.com/q/1/b?id=861089059670662")
        assertEquals(2, parsed.type)
        assertEquals("861089059670662", parsed.id)
    }

    @Test
    fun `shower qr link keeps the id query parameter`() {
        val parsed = parse("https://i.ilife798.com/q/1/s?id=abc123")
        assertEquals(3, parsed.type)
        assertEquals("abc123", parsed.id)
    }

    @Test
    fun `billing vip and wallet links are separated by path`() {
        assertEquals(5, parse("https://i.ilife798.com/q/1/billing?id=BL1").type)
        assertEquals("BL1", parse("https://i.ilife798.com/q/1/billing?id=BL1").id)

        assertEquals(6, parse("https://i.ilife798.com/q/1/vip?id=V1").type)
        assertEquals(7, parse("https://i.ilife798.com/q/1/wallet?id=W1").type)
    }

    @Test
    fun `door lock link keeps the path after the q prefix`() {
        val parsed = parse("https://i.ilife798.com/q/1/bm?/AA-BB-01")
        assertEquals(8, parsed.type)
        assertEquals("bm?/AA-BB-01", parsed.id)
    }

    @Test
    fun `unknown but structured links fall back to the generic type`() {
        val parsed = parse("https://i.ilife798.com/q/1/coupon")
        assertEquals(4, parsed.type)
        assertEquals("coupon", parsed.id)
    }

    @Test
    fun `vendor device links keep the serial as the id`() {
        // Actual payload of a 康之泉 dispenser panel:
        // https://i.hnkzy.com/q/1/861089059737339
        val parsed = parse("https://i.hnkzy.com/q/1/861089059737339")
        assertEquals(4, parsed.type)
        assertEquals("861089059737339", parsed.id)
    }

    @Test
    fun `trailing slash query and fragment do not corrupt the id`() {
        assertEquals("861089059737339", parse("https://i.hnkzy.com/q/1/861089059737339/").id)
        assertEquals("861089059737339", parse("https://i.hnkzy.com/q/1/861089059737339?from=wx").id)
        assertEquals("861089059737339", parse("https://i.hnkzy.com/q/1/861089059737339#top").id)
        assertEquals("861089059670662", parse("https://i.ilife798.com/q/1/b?id=861089059670662&from=qr").id)
    }

    @Test
    fun `an upper case scheme is still a link`() {
        val parsed = parse("HTTPS://i.hnkzy.com/q/1/861089059737339")
        assertEquals(4, parsed.type)
        assertEquals("861089059737339", parsed.id)
    }

    @Test
    fun `garbage and blank input stay unresolved`() {
        assertEquals(-1, parse("hello world").type)
        assertEquals(-1, parse("").type)
        assertEquals(-1, parse(null).type)
        assertTrue(parse(null).id.isBlank())
    }

    @Test
    fun `qr type names cover every documented type`() {
        assertEquals("充值卡", QrParser.qrTypeName(1))
        assertEquals("IC 卡", QrParser.qrTypeName(2))
        assertEquals("设备", QrParser.qrTypeName(3))
        assertEquals("消费券", QrParser.qrTypeName(4))
        assertEquals("云袋", QrParser.qrTypeName(5))
        assertEquals("洗衣洗鞋", QrParser.qrTypeName(6))
        assertEquals("账单 / VIP", QrParser.qrTypeName(7))
        assertEquals("门锁", QrParser.qrTypeName(8))
        assertEquals("未知类型", QrParser.qrTypeName(42))
    }
}
