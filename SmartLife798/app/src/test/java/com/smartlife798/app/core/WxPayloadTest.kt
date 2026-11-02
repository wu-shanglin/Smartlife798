package com.smartlife798.app.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class WxPayloadTest {
    @Test fun `aliases and numeric timestamp preserve server app id`() {
        val order = WxPay.parse(JsonParser.parseString("""{"appId":"wx-test","partnerId":"merchant","prepayId":"prepay","nonceStr":"nonce","timeStamp":1234,"packageValue":"Sign=WXPay","sign":"opaque"}"""))!!
        assertEquals("wx-test", order.appId)
        assertEquals("1234", order.timeStamp)
        assertEquals("Sign=WXPay", order.packageValue)
    }
    @Test fun `malformed nested values fail without crashing`() {
        assertNull(WxPay.parse(JsonParser.parseString("""{"partnerid":{},"prepayid":"p","noncestr":"n","timestamp":1,"sign":"s"}""")))
        assertNull(WxPay.parse(JsonParser.parseString("[]")))
    }
}
