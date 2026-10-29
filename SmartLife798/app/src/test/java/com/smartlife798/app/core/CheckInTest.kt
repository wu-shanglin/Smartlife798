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
    fun `legacy 319 MD5 fixture remains explicit and uses thirty second buckets`() {
        val expected = "7024e17b3e0c0ca9d12f02aa4d702ae3"
        assertEquals(expected, CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh",
            mondayMidnightChina, protocolVersion = "3.1.9"))
        assertEquals(expected, CheckIn.sign("daily-fixture", "12345678", "abcdefgh",
            mondayMidnightChina, 29_999, protocolVersion = "3.1.9"))
    }

    @Test
    fun `3110 signature matches independent Rust backed Python reference`() {
        val expected = "36ce4348b84310502e39e08598667b4a"
        assertEquals(expected, CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh",
            mondayMidnightChina, protocolVersion = "3.1.10"))
        assertEquals(expected, CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh",
            mondayMidnightChina, 29_999, protocolVersion = "3.1.10"))
        assertEquals(CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh", mondayMidnightChina + 30_000),
            CheckIn.sign("daily-fixture", "prefix-12345678", "prefix-abcdefgh", mondayMidnightChina, 30_000))
    }

    @Test
    fun `3110 arguments sort by key and preserve equal key order`() {
        assertEquals(CheckIn.sign("a=1&b=2", "12345678", "abcdefgh", mondayMidnightChina),
            CheckIn.sign("b=2&a=1", "12345678", "abcdefgh", mondayMidnightChina))
        org.junit.Assert.assertNotEquals(CheckIn.sign("a=2&a=1", "12345678", "abcdefgh", mondayMidnightChina),
            CheckIn.sign("a=1&a=2", "12345678", "abcdefgh", mondayMidnightChina))
    }

    @Test
    fun `unknown protocols fail before producing a write signature`() {
        assertTrue(CheckIn.supportsProtocol("3.1.9"))
        assertTrue(CheckIn.supportsProtocol("3.1.10"))
        for (version in listOf("3.1.11", "3.1.5", "", "invalid")) {
            assertEquals(false, CheckIn.supportsProtocol(version))
            val failure = try {
                CheckIn.sign("daily", "12345678", "abcdefgh", mondayMidnightChina, protocolVersion = version)
                null
            } catch (error: IllegalArgumentException) { error }
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test
    fun `keyed BLAKE3 matches independent block chunk and tree boundary vectors`() {
        // Rust-backed Python blake3 1.0.8; official test key and repeating 0..250 input.
        val key = "whats the Elvish word for friend".toByteArray(Charsets.UTF_8)
        val vectors = listOf(
            0 to "92b2b75604ed3c761f9d6f62392c8a9227ad0ea3f09573e783f1498a4ed60d26",
            1 to "6d7878dfff2f485635d39013278ae14f1454b8c0a3a2d34bc1ab38228a80c95b",
            63 to "bb1eb5d4afa793c1ebdd9fb08def6c36d10096986ae0cfe148cd101170ce37ae",
            64 to "ba8ced36f327700d213f120b1a207a3b8c04330528586f414d09f2f7d9ccb7e6",
            65 to "c0a4edefa2d2accb9277c371ac12fcdbb52988a86edc54f0716e1591b4326e72",
            1023 to "c951ecdf03288d0fcc96ee3413563d8a6d3589547f2c2fb36d9786470f1b9d6e",
            1024 to "75c46f6f3d9eb4f55ecaaee480db732e6c2105546f1e675003687c31719c7ba4",
            1025 to "357dc55de0c7e382c900fd6e320acc04146be01db6a8ce7210b7189bd664ea69",
            2048 to "879cf1fa2ea0e79126cb1063617a05b6ad9d0b696d0d757cf053439f60a99dd1",
            2049 to "9f29700902f7c86e514ddc4df1e3049f258b2472b6dd5267f61bf13983b78dd5",
            3072 to "044a0e7b172a312dc02a4c9a818c036ffa2776368d7f528268d2e6b5df191770",
            4096 to "befc660aea2f1718884cd8deb9902811d332f4fc4a38cf7c7300d597a081bfc0",
        )
        for ((length, expected) in vectors) {
            val actual = CheckInBlake3.keyedHash(key, ByteArray(length) { (it % 251).toByte() })
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("input length $length", expected, actual)
        }
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
