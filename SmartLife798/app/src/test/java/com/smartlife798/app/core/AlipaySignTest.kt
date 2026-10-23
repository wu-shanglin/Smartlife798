package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlipaySignTest {

    @Test
    fun `link mirrors the original alipay pouch deep link`() {
        assertEquals(
            "alipays://platformapi/startapp?appId=2019061465519660&page=/task/pouch&query=pgacode=20265821330935816554",
            AlipaySign.link("20265821330935816554"),
        )
    }
}
