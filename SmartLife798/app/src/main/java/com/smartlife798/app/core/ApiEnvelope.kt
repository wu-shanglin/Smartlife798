package com.smartlife798.app.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Wire envelope `{code,msg,data,time}`.
 *
 * `data` is decoded in a second, optional step. Several command endpoints
 * (`dev/start`, `dev/end`, `trans/confirm`, …) are modelled as [Unit] because
 * the client ignores the payload, but the production service does not keep
 * `data` an object for them: a hot-water machine was reported as `data:""`,
 * while other devices can answer `data:{...}` or `data:null`. Decoding the whole
 * body as `ApiResp<Unit>` made Gson throw `Expected BEGIN_OBJECT`, which
 * surfaced as a fake 「响应解析失败」 for a command the server had already
 * accepted. (The original client declares these responses as `BaseReq`, whose
 * `data` is plain `Object`, so it never had this failure mode.)
 */
internal object ApiEnvelope {
    private val type: Type = object : TypeToken<ApiResp<JsonElement>>() {}.type

    data class Parsed(val code: Int, val msg: String?, val data: JsonElement?)

    /** Returns null when [text] is not a JSON envelope (HTML error page, empty body…). */
    fun parse(gson: Gson, text: String): Parsed? = try {
        gson.fromJson<ApiResp<JsonElement>>(text, type)?.let { Parsed(it.code, it.msg, it.data) }
    } catch (_: RuntimeException) {
        // Also covers Gson's NumberFormatException on an unexpected numeric
        // field: an unreadable envelope is reported as such by the caller.
        null
    }

    /**
     * Decodes `data` into [dataType].
     *
     * [Unit] endpoints accept any payload shape; a mismatching shape for a
     * modelled payload still throws [JsonParseException], so the caller can
     * report 「响应解析失败」 instead of silently handing back empty data.
     */
    fun <T> decode(gson: Gson, data: JsonElement?, dataType: Type): T? {
        if (data == null || data.isJsonNull) return null
        if (dataType == Unit::class.java) return null
        return gson.fromJson<T>(data, dataType)
    }
}
