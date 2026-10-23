package com.smartlife798.app.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers for the UI layer.
 *
 * [SimpleDateFormat] is mutable and not thread-safe, and these helpers are also
 * reachable from background polling, so each pattern keeps one instance per
 * thread instead of one shared instance.
 */
object Fmt {
    private val dateTimeFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    private val timeOnlyFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
    private val dateOnlyFmt = ThreadLocal.withInitial { SimpleDateFormat("MM-dd", Locale.CHINA) }

    fun dateTime(ts: Long): String = if (ts <= 0) "-" else dateTimeFmt.get()!!.format(Date(ts))

    fun clock(ts: Long): String = if (ts <= 0) "--:--:--" else timeOnlyFmt.get()!!.format(Date(ts))

    /** HH:mm — the form used inline in subtitles. */
    fun hhmm(ts: Long): String = if (ts <= 0) "--:--" else clock(ts).take(5)

    fun shortDate(ts: Long): String = if (ts <= 0) "-" else dateOnlyFmt.get()!!.format(Date(ts))

    fun money(v: Double?): String {
        val d = v ?: 0.0
        return String.format(Locale.CHINA, "¥ %.2f", d)
    }

    fun amount(v: Double?): String {
        val d = v ?: 0.0
        return String.format(Locale.CHINA, "%.2f", d)
    }

    /** 00:12:35 */
    fun duration(fromMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        var secs = ((nowMillis - fromMillis) / 1000).coerceAtLeast(0)
        val h = secs / 3600
        secs %= 3600
        val m = secs / 60
        val s = secs % 60
        return String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, s)
    }

    fun greeting(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hourOfDay) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..18 -> "下午好"
        else -> "晚上好"
    }

    fun phoneMasked(phone: String?): String {
        if (phone.isNullOrBlank()) return "未绑定手机号"
        return if (phone.length == 11) phone.substring(0, 3) + "****" + phone.substring(7) else phone
    }
}
