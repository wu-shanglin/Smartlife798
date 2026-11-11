package com.smartlife798.app.core

/** Anonymous SMS login in the verified original protocol. */
internal object LoginSign {
    const val PROTOCOL = "3.1.10"
    const val ENDPOINT = "/api/v1/acc/login"

    fun validCredentials(phone: String, smsCode: String): Boolean =
        phone.length == 11 && phone.all { it in '0'..'9' } &&
            smsCode.isNotEmpty() && smsCode.all { it in '0'..'9' }

    fun sign(phone: String, smsCode: String, timeMs: Long): String {
        val bucketSeconds = (timeMs / 30_000L) * 30L
        return OctSignature.sign("authCode=$smsCode&un=$phone", phone, ENDPOINT, bucketSeconds)
    }
}
