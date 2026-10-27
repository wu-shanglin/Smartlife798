package com.smartlife798.app.core

/** Null fields are omitted by Gson, so unrelated account/security settings are preserved. */
class ProfilePatch private constructor(val name: String? = null, val useScore: Int? = null) {
    companion object {
        fun nickname(value: String): ProfilePatch {
            val name = value.trim()
            require(name.isNotEmpty() && name.none { it.isISOControl() })
            return ProfilePatch(name = name)
        }

        fun useScore(enabled: Boolean): ProfilePatch = ProfilePatch(useScore = if (enabled) 1 else 0)
    }
}

/** useScore is a preference bit. The balance comes from mission-lst.validScore. */
fun Master.storedProfile(fallbackPhone: String?): StoredProfile? = account?.let {
    StoredProfile(
        id = it.id.orEmpty(),
        name = it.name.orEmpty(),
        phone = it.phone.orEmpty().ifBlank { fallbackPhone.orEmpty() },
        no = it.no,
        arrear = it.arrear,
        totalScore = null,
        useScore = (it.useScore ?: 0).takeIf { bit -> bit == 0 || bit == 1 },
        img = it.img,
    )
}
