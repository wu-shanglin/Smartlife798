package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPageTest {
    @Test fun missingOrUnknownPreferenceKeepsHome() {
        listOf(null, "", "devices", "OBSOLETE", "HOME ").forEach {
            assertEquals(StartupPage.HOME, StartupPage.fromName(it))
        }
    }

    @Test fun persistedChoicesRestoreTheirNavigationRoute() {
        assertEquals("home", StartupPage.fromName("HOME").route)
        assertEquals("devices", StartupPage.fromName("DEVICES").route)
        StartupPage.entries.forEach { assertEquals(it, StartupPage.fromName(it.name)) }
    }
}
