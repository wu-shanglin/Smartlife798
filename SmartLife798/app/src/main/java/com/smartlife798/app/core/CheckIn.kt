package com.smartlife798.app.core

import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Confirmed server snapshot; used only to suppress automatic work for the remaining server day. */
data class ConfirmedCheckIn(val serverTime: Long = 0L, val recordedAt: Long = 0L) {
    fun isCurrent(now: Long): Boolean {
        val elapsed = now - recordedAt
        return serverTime > 0L && recordedAt > 0L && elapsed in 0 until 86_400_000L &&
            CheckIn.dayKey(serverTime) == CheckIn.dayKey(serverTime + elapsed)
    }
}

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

/** Original HomeFragment/SignInFragment use zero until the score account is created. */
fun Missions.pointsBalance(): String = accScoreRsp?.validScore ?: "0"

/** Normal check-in only: no retroactive, advertising or fabricated reward fields. */
data class ScoreParams(
    val adId: String = "",
    val weekday: Int = 0,
    val addScoreType: Int = 1,
    val addScore: Int = 0,
)

/**
 * Independent protocol implementation based on the recovered original 3.1.9 client.
 * Uses this application's own identity and the signed-in user's session. No
 * original native library or certificate impersonation. Real-service evidence is tracked separately.
 */
object CheckIn {
    private const val SIGN_SALT = "AbCdEfGhIjKlMnOpQrStUvWxYz12345"
    fun supportsProtocol(version: String): Boolean = version == "3.1.9" || version == "3.1.10"

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

    /** Strict by default for confirmation; original absent-score-account/daily fallback is opt-in for preparation. */
    fun isSignedIn(missions: Missions, weekday: Int, allowUnsignedDefault: Boolean = false): Boolean? {
        if (weekday !in 1..7) return null
        val supplied = missions.accScoreRsp?.daily?.week
        val week = (supplied ?: if (allowUnsignedDefault) 0 else null)?.takeIf { it in 0..127 } ?: return null
        return week and (1 shl (weekday - 1)) != 0
    }

    /** Return a command only for an explicitly unsigned day and valid server reward. */
    fun prepare(missions: Missions, weekday: Int, allowUnsignedDefault: Boolean = false): ScoreParams? {
        if (isSignedIn(missions, weekday, allowUnsignedDefault) != false) return null
        val reward = missions.dailyRSP ?: return null
        val adId = reward.adId?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
        val score = reward.score?.takeIf { it >= 0 } ?: return null
        return ScoreParams(adId = adId, weekday = weekday, addScore = score)
    }

    /**
     * Protocol 3.1.9 uses MD5 with its recovered salt; 3.1.10 uses the OctUtil keyed BLAKE3
     * input and returns its first 16 bytes in lowercase hex. Select only a verified protocol version. The server clock plus monotonic elapsed
     * time avoids a local wall-clock change between status query and submission.
     * Callers must not log the input, output, or URL carrying this signature.
     */
    fun sign(
        adId: String,
        token: String,
        uid: String,
        serverTimeMs: Long,
        elapsedSinceSnapshotMs: Long = 0,
        protocolVersion: String = Api.CLIENT_VERSION,
    ): String {
        require(adId.isNotBlank() && adId.none(Char::isISOControl)) { "Missing mission identity" }
        require(token.length >= 8 && uid.length >= 8) { "Missing session identity" }
        require(serverTimeMs > 0 && elapsedSinceSnapshotMs >= 0 && elapsedSinceSnapshotMs <= Long.MAX_VALUE - serverTimeMs) {
            "Invalid server clock"
        }
        val bucketSeconds = ((serverTimeMs + elapsedSinceSnapshotMs) / 30_000L) * 30L
        return when (protocolVersion) {
            "3.1.9" -> {
                val input = adId.split('&').sorted().joinToString("&") + bucketSeconds +
                    token.takeLast(8) + uid.takeLast(8) + SIGN_SALT
                MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).hex()
            }
            "3.1.10" -> {
                // OctUtil sorts by the argument key (before '='); equal keys retain their order.
                val normalized = adId.split('&').sortedBy { it.substringBefore('=') }.joinToString("&")
                val bucket = bucketSeconds.toString()
                val payload = normalized + bucket + token.takeLast(8) + uid.takeLast(8)
                OctSignature.sign(payload, token, "/api/v1/acc/score/score-send", bucketSeconds)
            }
            else -> throw IllegalArgumentException("Unsupported check-in protocol")
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}
