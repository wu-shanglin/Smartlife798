package com.smartlife798.app.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface NfcRoute {
    data class Qr(val id: String) : NfcRoute
    data class Device(val id: String) : NfcRoute
    data class Alipay(val code: String) : NfcRoute
    data object Unsupported : NfcRoute
}

/** Only observed card/device URI shapes. Nothing here follows redirects or guesses a device ID. */
object NfcRoutes {
    private val hosts = setOf("i.ilife798.com", "i.hnkzy.com", "act.hnkzy.com", "act2.hnkzy.com",
        "prerelease.ilife798.com")
    private val identifier = Regex("[A-Za-z0-9_-]{1,256}")

    fun parseAll(uris: List<String>): NfcRoute {
        if (uris.size > 16) return NfcRoute.Unsupported
        val candidates = uris.map(::parse).filter { it != NfcRoute.Unsupported }.distinct()
        return candidates.singleOrNull() ?: NfcRoute.Unsupported
    }

    fun parse(raw: String): NfcRoute = parse(raw, depth = 0)

    private fun parse(raw: String, depth: Int): NfcRoute {
        if (depth > 3 || raw.length !in 1..4096 || raw.any { it.isISOControl() }) return NfcRoute.Unsupported
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
                scheme in setOf("alipay", "alipays") && host == "platformapi" && uri.rawPath == "/startapp" &&
                    query["appId"] == "2019061465519660" -> miniappDevice(query["page"])
                scheme == "alipay" && host == "nfc" && uri.rawPath == "/app" &&
                    query["id"] == "10000007" && query["actionType"] == "route" && query["t"] == null ->
                    query["codeContent"]?.let { parse(it, depth + 1) } ?: NfcRoute.Unsupported
                depth == 0 && scheme == "https" && host == "render.alipay.com" &&
                    uri.rawPath == "/p/s/ulink/xty" && query["s"] == "dc" ->
                    query["scheme"]?.let { parse(it, depth + 1) } ?: NfcRoute.Unsupported
                else -> NfcRoute.Unsupported
            }
        } catch (_: IllegalArgumentException) {
            NfcRoute.Unsupported
        } catch (_: java.net.URISyntaxException) {
            NfcRoute.Unsupported
        }
    }

    /** Exact HuiLife miniapp page observed in the supplied public share redirect. */
    private fun miniappDevice(page: String?): NfcRoute {
        val uri = page?.let(::URI) ?: return NfcRoute.Unsupported
        if (uri.scheme != null || uri.authority != null || uri.fragment != null || uri.rawPath != "devview/view")
            return NfcRoute.Unsupported
        val id = query(uri.rawQuery)?.get("id") ?: return NfcRoute.Unsupported
        return if (id.matches(Regex("[0-9]{1,32}"))) NfcRoute.Device(id) else NfcRoute.Unsupported
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
