package com.smartlife798.app.core

import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Only fields required for daily check-in; absent data stays unknown. */
data class Missions(
    val accScoreRsp: ScoreAccount? = null,
    val dailyRSP: DailyReward? = null,
)

data class ScoreAccount(
    val validScore: String? = null,
    val daily: DailyCheckIn? = null,
)

data class DailyCheckIn(val ltime: Long? = null, val week: Int? = null)

data class DailyReward(val adId: String? = null, val score: Int? = null)

/** Normal check-in only: no retroactive, advertising or fabricated reward fields. */
data class ScoreParams(
    val adId: String = "",
    val weekday: Int = 0,
    val addScoreType: Int = 1,
    val addScore: Int = 0,
)

/**
 * Independent protocol implementation based on the original 3.1.5 client.
 * Uses this application's own identity and the signed-in user's session. No
 * original native library, certificate impersonation, or real-account test.
 */
object CheckIn {
    private const val SIGN_SALT = "aslkdvcniu34h9tgufh278wv2"
    private val chinaZone: TimeZone get() = TimeZone.getTimeZone("Asia/Shanghai")

    private fun calendar(serverTimeMs: Long): Calendar {
        require(serverTimeMs > 0) { "Missing server time" }
        return Calendar.getInstance(chinaZone, Locale.ROOT).apply { timeInMillis = serverTimeMs }
    }

    /** Monday = 1, Sunday = 7; use the same server snapshot as the mission. */
    fun weekday(serverTimeMs: Long): Int = (calendar(serverTimeMs).get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    fun dayKey(serverTimeMs: Long): String = calendar(serverTimeMs).let {
        String.format(Locale.ROOT, "%04d-%02d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }

    /** Strict by default for confirmation; original absent-daily fallback is opt-in for preparation. */
    fun isSignedIn(missions: Missions, weekday: Int, allowMissingDaily: Boolean = false): Boolean? {
        if (weekday !in 1..7) return null
        val account = missions.accScoreRsp ?: return null
        val supplied = account.daily?.week
        val week = (supplied ?: if (allowMissingDaily) 0 else null)?.takeIf { it in 0..127 } ?: return null
        return week and (1 shl (weekday - 1)) != 0
    }

    /** Return a command only for an explicitly unsigned day and valid server reward. */
    fun prepare(missions: Missions, weekday: Int, allowMissingDaily: Boolean = false): ScoreParams? {
        if (isSignedIn(missions, weekday, allowMissingDaily) != false) return null
        val reward = missions.dailyRSP ?: return null
        val adId = reward.adId?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
        val score = reward.score?.takeIf { it >= 0 } ?: return null
        return ScoreParams(adId = adId, weekday = weekday, addScore = score)
    }

    /**
     * Original native payload: adId + ten-second bucket (seconds) + token/uid
     * suffixes + salt, then lowercase MD5. The server clock plus monotonic elapsed
     * time avoids a local wall-clock change between status query and submission.
     * Callers must not log the input, output, or URL carrying this signature.
     */
    fun sign(
        adId: String,
        token: String,
        uid: String,
        serverTimeMs: Long,
        elapsedSinceSnapshotMs: Long = 0,
    ): String {
        require(adId.isNotBlank() && adId.none(Char::isISOControl)) { "Missing mission identity" }
        require(token.length >= 8 && uid.length >= 8) { "Missing session identity" }
        require(serverTimeMs > 0 && elapsedSinceSnapshotMs >= 0 && elapsedSinceSnapshotMs <= Long.MAX_VALUE - serverTimeMs) {
            "Invalid server clock"
        }
        val bucketSeconds = ((serverTimeMs + elapsedSinceSnapshotMs) / 10_000L) * 10L
        val input = adId + bucketSeconds + token.takeLast(8) + uid.takeLast(8) + SIGN_SALT
        return MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}
