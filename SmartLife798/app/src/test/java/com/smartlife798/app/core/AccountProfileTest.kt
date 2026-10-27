package com.smartlife798.app.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AccountProfileTest {
    private val gson = Gson()

    @Test fun `patch changes one field without sending password or unrelated defaults`() {
        assertEquals("{\"name\":\"新昵称\"}", gson.toJson(ProfilePatch.nickname("  新昵称  ")))
        assertEquals("{\"useScore\":1}", gson.toJson(ProfilePatch.useScore(true)))
        assertEquals("{\"useScore\":0}", gson.toJson(ProfilePatch.useScore(false)))
    }

    @Test fun `blank and control character nicknames are rejected`() {
        for (name in listOf("", "   ", "昵称\n内容", "昵称\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { ProfilePatch.nickname(name) }
        }
        assertEquals("明月🌙", ProfilePatch.nickname("明月🌙").name)
    }

    @Test fun `balance is separate from account score preference and avatar passes through`() {
        val master = gson.fromJson("""{"account":{"id":"fixture","name":"昵称","useScore":1,"img":"https://example.com/avatar.png"},"pltTotalScore":"120.5"}""", Master::class.java)
        val profile = master.storedProfile("fixture-phone")!!
        assertNull(profile.totalScore)
        assertEquals(1, profile.useScore)
        assertEquals("https://example.com/avatar.png", profile.img)
        assertEquals("fixture-phone", profile.phone)
    }

    @Test fun `missing and invalid fields remain unknown instead of implying off or zero balance`() {
        assertNull(Master().storedProfile(null))
        val profile = Master(account = User(useScore = 2)).storedProfile(null)!!
        assertNull(profile.useScore)
        assertNull(profile.totalScore)
        assertEquals("", profile.name)
    }

    @Test fun `missing score preference defaults off as in original client`() {
        assertEquals(0, Master(account = User()).storedProfile(null)!!.useScore)
    }

    @Test fun `legacy cache is readable without showing the old preference as a balance`() {
        val old = gson.fromJson("""{"id":"fixture","name":"旧昵称","phone":"","score":1}""", StoredProfile::class.java)
        assertEquals("旧昵称", old.name)
        assertNull(old.totalScore)
        assertNull(old.useScore)
        assertNull(old.img)
    }
}
