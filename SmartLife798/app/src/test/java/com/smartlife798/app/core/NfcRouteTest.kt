package com.smartlife798.app.core

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class NfcRouteTest {
    @Test fun officialCardContainsAQrIdentifierRatherThanADeviceIdentifier() {
        for (host in listOf("i.ilife798.com", "i.hnkzy.com", "act.hnkzy.com", "act2.hnkzy.com", "prerelease.ilife798.com"))
            assertEquals(NfcRoute.Qr("fixture_QR-123"), NfcRoutes.parse("https://$host/nfc.html?q=fixture_QR-123"))
        assertEquals(NfcRoute.Qr("fixture_123"), NfcRoutes.parse("https://I.ILIFE798.COM/nfc.html?q=fixture%5F123"))
    }

    @Test fun alipayEntryIsExplicitlyClassifiedWithoutGuessingDeviceId() {
        val inner = "alipay://nfc/app?id=20002153&t=fixtureCard"
        assertEquals(NfcRoute.Alipay("fixtureCard"), NfcRoutes.parse(inner))
        assertEquals(NfcRoute.Alipay("fixtureCard"), NfcRoutes.parse(
            "https://render.alipay.com/p/s/ulink/xty?s=dc&scheme=" + URLEncoder.encode(inner, "UTF-8")))
    }

    @Test fun malformedForeignAmbiguousAndOversizedInputsDoNotResolve() {
        val invalid = listOf("https://i.ilife798.com.attacker.test/nfc.html?q=123", "http://i.ilife798.com/nfc.html?q=123",
            "https://i.ilife798.com/nfc.html?q=1&q=2", "https://i.ilife798.com/nfc.html?q=%2Fdevice%2F1",
            "https://user@i.ilife798.com/nfc.html?q=123", "https://i.ilife798.com:443/nfc.html?q=123",
            "https://i.ilife798.com/nfc.html/extra?q=123", "https://i.ilife798.com/nfc.html?q=123#fragment",
            "https://i.ilife798.com/nfc.html?q=%XX", "https://i.ilife798.com/nfc.html?q=", "x".repeat(4097))
        invalid.forEach { assertEquals(it, NfcRoute.Unsupported, NfcRoutes.parse(it)) }
        assertEquals(NfcRoute.Unsupported, NfcRoutes.parseAll(listOf(
            "https://i.ilife798.com/nfc.html?q=1", "https://i.ilife798.com/nfc.html?q=2")))
    }
}
