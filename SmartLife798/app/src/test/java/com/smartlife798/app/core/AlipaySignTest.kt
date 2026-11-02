package com.smartlife798.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlipaySignTest {

    @Test
    fun `sign params decode one encoded envelope without corrupting raw plus`() {
        assertEquals("app_id=demo&sign=a+b=", AlipaySign.parameters("app_id%3Ddemo%26sign%3Da%2Bb%3D"))
        assertEquals("app_id=demo&sign=a+b%3D", AlipaySign.parameters("app_id=demo&sign=a+b%3D"))
    }

    @Test
    fun `link mirrors the original alipay pouch deep link`() {
        assertEquals(
            "alipays://platformapi/startapp?appId=2019061465519660&page=/task/pouch&query=pgacode=20265821330935816554",
            AlipaySign.link("20265821330935816554"),
        )
    }
}
