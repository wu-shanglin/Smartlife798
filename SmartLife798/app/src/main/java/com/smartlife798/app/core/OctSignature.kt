package com.smartlife798.app.core

import java.util.Locale

/** Shared digest format for the verified 3.1.10 protocol. */
internal object OctSignature {
    private const val KEY_PREFIX = "Ys3yx=j7"

    fun sign(payload: String, keySource: String, endpoint: String, bucketSeconds: Long): String {
        val middleStart = (keySource.length - 8) / 2
        val middle = keySource.substring(middleStart, middleStart + 8)
        val path = endpoint.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "").padStart(8, '0').takeLast(8)
        val bucket = bucketSeconds.toString().padStart(8, '0').takeLast(8)
        val key = KEY_PREFIX + bucket + middle + path
        return KeyedBlake3.keyedHash(key.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

/** Independent BLAKE3 keyed-hash implementation. */
internal object KeyedBlake3 {
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
