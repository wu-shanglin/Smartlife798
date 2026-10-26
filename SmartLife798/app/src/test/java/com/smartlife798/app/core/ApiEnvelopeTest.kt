package com.smartlife798.app.core

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope must survive payload shapes we do not model. `dev/start` on a
 * hot-water machine answers `data:""` while other devices answer an object or
 * null; treating that as “响应解析失败” made the client report a failure for a
 * command the server had already accepted.
 */
class ApiEnvelopeTest {

    private val gson = Gson()

    @Test
    fun `envelope is read independently from the payload`() {
        val parsed = ApiEnvelope.parse(gson, """{"code":0,"data":"","time":1792512371043}""")!!
        assertEquals(0, parsed.code)
        assertEquals(1792512371043L, parsed.time)
        assertNull(parsed.msg)
        assertTrue(parsed.data!!.isJsonPrimitive)
    }

    @Test
    fun `unit endpoints accept every payload shape`() {
        val payloads = listOf("\"\"", "\"ok\"", "123", "true", "[]", "{\"order\":\"x\"}", "null")
        payloads.forEach { payload ->
            val parsed = ApiEnvelope.parse(gson, """{"code":0,"data":$payload}""")!!
            assertNull(
                "data=$payload must be ignored for Unit endpoints",
                ApiEnvelope.decode<Unit>(gson, parsed.data, Unit::class.java),
            )
        }
    }

    @Test
    fun `modelled payload still decodes`() {
        val parsed = ApiEnvelope.parse(
            gson,
            """{"code":0,"data":{"channels":[{"type":21,"name":"支付宝"}]}}""",
        )!!
        val channels = ApiEnvelope.decode<PayChannelData>(gson, parsed.data, PayChannelData::class.java)!!
        assertEquals(21, channels.channels.single().type)
        assertEquals("支付宝", channels.channels.single().name)
    }

    @Test
    fun `negative envelope keeps the server message`() {
        val parsed = ApiEnvelope.parse(gson, """{"code":-52,"msg":"存在待付款账单"}""")!!
        assertEquals(-52, parsed.code)
        assertEquals("存在待付款账单", parsed.msg)
        assertNull(parsed.data)
    }

    @Test
    fun `non json bodies are rejected`() {
        assertNull(ApiEnvelope.parse(gson, "<html>502 Bad Gateway</html>"))
        assertNull(ApiEnvelope.parse(gson, ""))
        assertNull(ApiEnvelope.parse(gson, "not-json"))
    }

    @Test(expected = JsonParseException::class)
    fun `a modelled payload with the wrong shape still fails`() {
        val parsed = ApiEnvelope.parse(gson, """{"code":0,"data":""}""")!!
        ApiEnvelope.decode<PayChannelData>(gson, parsed.data, PayChannelData::class.java)
    }
}
