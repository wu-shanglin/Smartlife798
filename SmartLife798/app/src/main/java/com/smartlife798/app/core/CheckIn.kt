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
    private const val OCT_KEY_PREFIX = "Ys3yx=j7"
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
                val middle = (token.length - 8) / 2
                val path = "/api/v1/acc/score/score-send".trim().lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]"), "").padStart(8, '0').takeLast(8)
                val key = OCT_KEY_PREFIX + bucket.padStart(8, '0').takeLast(8) +
                    token.substring(middle, middle + 8) + path
                // Original native s returns only the first 16 bytes of the keyed BLAKE3 output.
                CheckInBlake3.keyedHash(key.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8))
                    .copyOf(16).hex()
            }
            else -> throw IllegalArgumentException("Unsupported check-in protocol")
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

/** Independent BLAKE3 keyed-hash implementation; no original native code or identity checks. */
internal object CheckInBlake3 {
    private val iv = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    private val permutation = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8
    private const val KEYED_HASH = 16

    private data class Output(val cv: IntArray, val block: IntArray, val counter: Long,
        val blockLength: Int, val flags: Int) {
        fun chaining(): IntArray = compress(cv, block, counter, blockLength, flags).copyOf(8)
        fun root(): ByteArray {
            val words = compress(cv, block, 0, blockLength, flags or ROOT)
            return ByteArray(32) { index -> (words[index / 4] ushr (8 * (index % 4))).toByte() }
        }
    }

    fun keyedHash(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "BLAKE3 requires a 32-byte key" }
        val keyWords = words(key, 0, key.size).copyOf(8)
        val chunks = maxOf(1, (input.size.toLong() + 1023L).div(1024L).toInt())
        fun subtree(first: Int, count: Int): Output {
            if (count == 1) {
                val offset = first * 1024
                val length = minOf(1024, input.size - offset)
                val blocks = maxOf(1, (length + 63) / 64)
                var cv = keyWords
                for (index in 0 until blocks) {
                    val blockLength = minOf(64, length - index * 64)
                    val flags = KEYED_HASH or (if (index == 0) CHUNK_START else 0) or
                        (if (index == blocks - 1) CHUNK_END else 0)
                    val output = Output(cv, words(input, offset + index * 64, blockLength),
                        first.toLong(), blockLength, flags)
                    if (index == blocks - 1) return output
                    cv = output.chaining()
                }
                error("Missing BLAKE3 chunk")
            }
            val leftCount = Integer.highestOneBit(count - 1)
            val left = subtree(first, leftCount).chaining()
            val right = subtree(first + leftCount, count - leftCount).chaining()
            return Output(keyWords, left + right, 0, 64, KEYED_HASH or PARENT)
        }
        return subtree(0, chunks).root()
    }

    private fun words(bytes: ByteArray, offset: Int, length: Int): IntArray {
        val result = IntArray(16)
        for (index in 0 until length) result[index / 4] = result[index / 4] or
            ((bytes[offset + index].toInt() and 255) shl (8 * (index % 4)))
        return result
    }

    private fun compress(cv: IntArray, block: IntArray, counter: Long, length: Int, flags: Int): IntArray {
        val state = cv + intArrayOf(iv[0], iv[1], iv[2], iv[3], counter.toInt(),
            (counter ushr 32).toInt(), length, flags)
        var message = block
        fun g(a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            state[a] += state[b] + x
            state[d] = Integer.rotateRight(state[d] xor state[a], 16)
            state[c] += state[d]
            state[b] = Integer.rotateRight(state[b] xor state[c], 12)
            state[a] += state[b] + y
            state[d] = Integer.rotateRight(state[d] xor state[a], 8)
            state[c] += state[d]
            state[b] = Integer.rotateRight(state[b] xor state[c], 7)
        }
        repeat(7) {
            g(0, 4, 8, 12, message[0], message[1])
            g(1, 5, 9, 13, message[2], message[3])
            g(2, 6, 10, 14, message[4], message[5])
            g(3, 7, 11, 15, message[6], message[7])
            g(0, 5, 10, 15, message[8], message[9])
            g(1, 6, 11, 12, message[10], message[11])
            g(2, 7, 8, 13, message[12], message[13])
            g(3, 4, 9, 14, message[14], message[15])
            message = IntArray(16) { message[permutation[it]] }
        }
        return IntArray(16) { index -> if (index < 8) state[index] xor state[index + 8]
            else state[index] xor cv[index - 8] }
    }
}
