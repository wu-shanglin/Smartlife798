package com.smartlife798.app.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface NfcRoute {
    data class Qr(val id: String) : NfcRoute
    data class Alipay(val code: String) : NfcRoute
    data object Unsupported : NfcRoute
}

/** Only documented card URI shapes. Nothing here follows redirects or guesses a device ID. */
object NfcRoutes {
    private val hosts = setOf("i.ilife798.com", "i.hnkzy.com", "act.hnkzy.com", "act2.hnkzy.com",
        "prerelease.ilife798.com")
    private val identifier = Regex("[A-Za-z0-9_-]{1,256}")

    fun parseAll(uris: List<String>): NfcRoute {
        if (uris.size > 16) return NfcRoute.Unsupported
        val candidates = uris.map(::parse).filter { it != NfcRoute.Unsupported }.distinct()
        return candidates.singleOrNull() ?: NfcRoute.Unsupported
    }

    fun parse(raw: String): NfcRoute = parse(raw, nested = false)

    private fun parse(raw: String, nested: Boolean): NfcRoute {
        if (raw.length !in 1..4096 || raw.any { it.isISOControl() }) return NfcRoute.Unsupported
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (uri.userInfo != null || uri.fragment != null || uri.port != -1) return NfcRoute.Unsupported
            val query = query(uri.rawQuery) ?: return NfcRoute.Unsupported
            when {
                scheme == "https" && host in hosts && uri.rawPath == "/nfc.html" ->
                    query["q"]?.takeIf(identifier::matches)?.let(NfcRoute::Qr) ?: NfcRoute.Unsupported
                scheme == "alipay" && host == "nfc" && uri.rawPath == "/app" && query["id"] == "20002153" ->
                    query["t"]?.takeIf(identifier::matches)?.let(NfcRoute::Alipay) ?: NfcRoute.Unsupported
                !nested && scheme == "https" && host == "render.alipay.com" &&
                    uri.rawPath == "/p/s/ulink/xty" && query["s"] == "dc" ->
                    query["scheme"]?.let { parse(it, nested = true) } ?: NfcRoute.Unsupported
                else -> NfcRoute.Unsupported
            }
        } catch (_: IllegalArgumentException) {
            NfcRoute.Unsupported
        } catch (_: java.net.URISyntaxException) {
            NfcRoute.Unsupported
        }
    }

    private fun query(raw: String?): Map<String, String>? {
        val values = linkedMapOf<String, String>()
        for (part in raw?.split('&').orEmpty()) {
            if (values.size >= 16) return null
            val name = URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            if (values.put(name, value) != null) return null
        }
        return values
    }
}
