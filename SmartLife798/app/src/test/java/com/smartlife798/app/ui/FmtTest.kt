package com.smartlife798.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {

    @Test
    fun `duration is zero padded and never negative`() {
        assertEquals("00:00:00", Fmt.duration(1_000L, 1_000L))
        assertEquals("00:00:01", Fmt.duration(0L, 1_000L))
        assertEquals("01:01:01", Fmt.duration(0L, 3_661_000L))
        assertEquals("10:00:00", Fmt.duration(0L, 36_000_000L))
        // Clock skew must not render a negative duration.
        assertEquals("00:00:00", Fmt.duration(5_000L, 1_000L))
    }

    @Test
    fun `money always has two decimals and a currency prefix`() {
        assertEquals("¥ 0.00", Fmt.money(null))
        assertEquals("¥ 0.00", Fmt.money(0.0))
        assertEquals("¥ 1.50", Fmt.money(1.5))
        assertEquals("¥ 0.05", Fmt.money(0.05))
    }

    @Test
    fun `amount has no currency prefix`() {
        assertEquals("0.00", Fmt.amount(null))
        assertEquals("12.34", Fmt.amount(12.34))
    }

    @Test
    fun `greeting follows the clock`() {
        assertEquals("早上好", Fmt.greeting(5))
        assertEquals("早上好", Fmt.greeting(10))
        assertEquals("中午好", Fmt.greeting(11))
        assertEquals("中午好", Fmt.greeting(13))
        assertEquals("下午好", Fmt.greeting(14))
        assertEquals("下午好", Fmt.greeting(18))
        assertEquals("晚上好", Fmt.greeting(19))
        assertEquals("晚上好", Fmt.greeting(3))
    }

    @Test
    fun `phone numbers are masked in the middle`() {
        assertEquals("138****0000", Fmt.phoneMasked("13800000000"))
        assertEquals("未绑定手机号", Fmt.phoneMasked(null))
        assertEquals("未绑定手机号", Fmt.phoneMasked("   "))
        // Anything that is not an 11-digit mainland number is shown verbatim.
        assertEquals("12345", Fmt.phoneMasked("12345"))
    }

    @Test
    fun `unknown timestamps render placeholders instead of 1970`() {
        assertEquals("-", Fmt.dateTime(0L))
        assertEquals("-", Fmt.shortDate(0L))
        assertEquals("--:--:--", Fmt.clock(0L))
        assertEquals("--:--", Fmt.hhmm(0L))
    }

    @Test
    fun `formatted timestamps have the expected shape`() {
        val ts = 1_700_000_000_000L
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(Fmt.dateTime(ts)))
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").matches(Fmt.clock(ts)))
        assertTrue(Regex("""\d{2}:\d{2}""").matches(Fmt.hhmm(ts)))
        assertTrue(Regex("""\d{2}-\d{2}""").matches(Fmt.shortDate(ts)))
    }

    @Test
    fun `formatting is safe from several threads at once`() {
        // Shared SimpleDateFormat instances used to throw or emit garbled text.
        val ts = 1_700_000_000_000L
        val expected = Fmt.dateTime(ts)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map {
            Thread {
                runCatching {
                    repeat(200) { assertEquals(expected, Fmt.dateTime(ts)) }
                }.onFailure { errors.add(it) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(emptyList<Throwable>(), errors.toList())
    }
}
