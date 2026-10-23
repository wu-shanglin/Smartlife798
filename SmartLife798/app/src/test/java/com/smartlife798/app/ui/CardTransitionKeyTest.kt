package com.smartlife798.app.ui

import org.junit.Assert.*
import org.junit.Test

class CardTransitionKeyTest {
    @Test fun `same device in different sections cannot share the wrong card`() {
        val favorite = CardSource("home-entry", CardOrigin.HOME_FAVORITE).key("device-1")
        val recent = CardSource("home-entry", CardOrigin.HOME_RECENT).key("device-1")
        assertNotEquals(favorite, recent)
        assertNotEquals(favorite, CardSource("devices-entry", CardOrigin.DEVICES).key("device-1"))
        assertNotEquals(favorite, CardSource("home-entry", CardOrigin.HOME_FAVORITE).key("device-2"))
    }

    @Test fun `restored destination matches the exact source entry and card`() {
        for (origin in CardOrigin.entries) {
            val source = CardSource("entry-1", origin)
            val restored = CardSource.decode(source.entryId, source.origin.name, origin.destination)
            assertEquals(source.key("device-1"), restored?.key("device-1"))
            assertNotEquals(source.key("device-1"), CardSource("entry-2", origin).key("device-1"))
        }
    }

    @Test fun `bad or unrelated route origins fall back without inventing a match`() {
        assertNull(CardSource.decode(null, "HOME_FAVORITE", CardDestination.DEVICE))
        assertNull(CardSource.decode(" ", "HOME_FAVORITE", CardDestination.DEVICE))
        assertNull(CardSource.decode("entry", "unknown", CardDestination.DEVICE))
        assertNull(CardSource.decode("entry", "HOME_SCAN", CardDestination.DEVICE))
        assertNull(CardSource.decode("entry", null, CardDestination.SCAN))
    }

    @Test fun `device cards need an identity while fixed actions can use an empty item id`() {
        assertNull(CardSource("entry", CardOrigin.HOME_FAVORITE).key())
        assertNotNull(CardSource("entry", CardOrigin.HOME_SCAN).key())
        assertNotNull(CardSource("entry", CardOrigin.WALLET).key())
        assertNull(CardSource("", CardOrigin.HOME_SCAN).key())
    }
    @Test fun `parts are unique and a stale source cannot navigate twice`() {
        val source = CardSource("entry", CardOrigin.HOME_FAVORITE)
        val key = source.key("device-1")!!
        assertNotEquals(key, key.copy(part = CardPart.TITLE))
        assertNotEquals(key.copy(part = CardPart.TITLE), key.copy(part = CardPart.AMOUNT))
        assertTrue(source.isCurrent("entry"))
        assertFalse(source.isCurrent("next-entry"))
        assertFalse(source.isCurrent(null))
        assertFalse(CardSource("", CardOrigin.WALLET).isCurrent(""))
    }
}
