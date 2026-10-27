package com.smartlife798.app.core

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CheckInTest {
    private val gson = Gson()
    private val mondayMidnightChina = 1_792_944_000_000L // 2026-10-26 00:00 +08

    @Test
    fun `weekday and day use server time in China across midnight and week rollover`() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(1, CheckIn.weekday(mondayMidnightChina))
            assertEquals("2026-10-26", CheckIn.dayKey(mondayMidnightChina))
            assertEquals(7, CheckIn.weekday(mondayMidnightChina - 1))
            assertEquals("2026-10-25", CheckIn.dayKey(mondayMidnightChina - 1))
            assertEquals(7, CheckIn.weekday(mondayMidnightChina + 6 * 86_400_000L))
            assertEquals(1, CheckIn.weekday(mondayMidnightChina + 7 * 86_400_000L))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `bitmap maps Monday low bit and Sunday high bit`() {
        val missions = Missions(ScoreAccount(daily = DailyCheckIn(week = 65)))
        assertTrue(CheckIn.isSignedIn(missions, 1) == true)
        assertTrue(CheckIn.isSignedIn(missions, 7) == true)
        for (day in 2..6) assertEquals(false, CheckIn.isSignedIn(missions, day))
        assertNull(CheckIn.isSignedIn(missions, 0))
        assertNull(CheckIn.isSignedIn(missions, 8))
        assertNull(CheckIn.isSignedIn(Missions(ScoreAccount(daily = DailyCheckIn(week = 128))), 1))
    }

    @Test
    fun `Gson absent or null status and reward never invent a checkin`() {
        for (json in listOf("{}", "{\"accScoreRsp\":null}", "{\"accScoreRsp\":{}}", "{\"accScoreRsp\":{\"daily\":{}}}")) {
            val missions = gson.fromJson(json, Missions::class.java)
            assertNull(CheckIn.isSignedIn(missions, 1))
            assertNull(CheckIn.prepare(missions, 1))
        }
        val base = Missions(ScoreAccount(daily = DailyCheckIn(week = 0)))
        assertNull(CheckIn.prepare(base, 1))
        assertNull(CheckIn.prepare(base.copy(dailyRSP = DailyReward("mission", null)), 1))
        assertNull(CheckIn.prepare(base.copy(dailyRSP = DailyReward("mission", -1)), 1))
        assertNull(CheckIn.prepare(base.copy(dailyRSP = DailyReward(" ", 10)), 1))
    }

    @Test
    fun `request uses exact server reward and normal checkin fields only`() {
        val missions = gson.fromJson(
            """{"accScoreRsp":{"validScore":"42.5","daily":{"week":0,"ltime":1792944000000}},"dailyRSP":{"adId":"daily-fixture","score":7}}""",
            Missions::class.java,
        )
        assertEquals("42.5", missions.accScoreRsp?.validScore)
        assertEquals(
            """{"adId":"daily-fixture","weekday":1,"addScoreType":1,"addScore":7}""",
            gson.toJson(CheckIn.prepare(missions, 1)),
        )
        assertNull(CheckIn.prepare(missions.copy(accScoreRsp = ScoreAccount(daily = DailyCheckIn(week = 1))), 1))
    }

    @Test
    fun `original absent daily fallback can prepare without inventing rewards or confirming success`() {
        for (account in listOf(null, ScoreAccount(), ScoreAccount(daily = DailyCheckIn()))) {
            val missions = Missions(account, DailyReward("manual-fixture", 5))
            assertNull(CheckIn.prepare(missions, 1))
            assertEquals(false, CheckIn.isSignedIn(missions, 1, allowUnsignedDefault = true))
            assertEquals(ScoreParams("manual-fixture", 1, addScore = 5),
                CheckIn.prepare(missions, 1, allowUnsignedDefault = true))
        }
        for (missions in listOf(
            Missions(ScoreAccount()),
            Missions(ScoreAccount(), DailyReward("manual-fixture", -1)),
            Missions(ScoreAccount(daily = DailyCheckIn(week = 128)), DailyReward("manual-fixture", 5)),
            Missions(ScoreAccount(daily = DailyCheckIn(week = 1)), DailyReward("manual-fixture", 5)),
        )) assertNull(CheckIn.prepare(missions, 1, allowUnsignedDefault = true))
    }

    @Test
    fun `available points defaults to zero before first reward and never uses platform totals`() {
        assertEquals("0", Missions().pointsBalance())
        assertEquals("0", Missions(ScoreAccount()).pointsBalance())
        assertEquals("150", Missions(ScoreAccount(validScore = "150")).pointsBalance())
    }

    @Test
    fun `signature matches independent hashlib fixture and changes only at bucket boundary`() {
        // Independently computed from the static native concatenation using Python hashlib.
        val expected = "7024e17b3e0c0ca9d12f02aa4d702ae3"
        assertEquals(expected, CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh", mondayMidnightChina))
        assertEquals(expected, CheckIn.sign("daily-fixture", "12345678", "abcdefgh", mondayMidnightChina, 29_999))
        assertEquals(
            CheckIn.sign("daily-fixture", "12345678", "abcdefgh", mondayMidnightChina + 30_000),
            CheckIn.sign("daily-fixture", "12345678", "abcdefgh", mondayMidnightChina, 30_000),
        )
    }

    @Test
    fun `current native sorts ampersand arguments before hashing`() {
        assertEquals(CheckIn.sign("a=1&b=2", "12345678", "abcdefgh", mondayMidnightChina),
            CheckIn.sign("b=2&a=1", "12345678", "abcdefgh", mondayMidnightChina))
    }

    @Test
    fun `invalid credentials and missing or overflowing server clock are rejected`() {
        for (operation in listOf<() -> Any>(
            { CheckIn.sign("daily", "short", "abcdefgh", mondayMidnightChina) },
            { CheckIn.sign("daily", "12345678", "short", mondayMidnightChina) },
            { CheckIn.sign("daily", "12345678", "abcdefgh", 0) },
            { CheckIn.sign("daily", "12345678", "abcdefgh", mondayMidnightChina, -1) },
            { CheckIn.sign("daily", "12345678", "abcdefgh", Long.MAX_VALUE, 1) },
        )) {
            val failure = try { operation(); null } catch (e: IllegalArgumentException) { e }
            assertTrue(failure is IllegalArgumentException)
        }
    }
}
