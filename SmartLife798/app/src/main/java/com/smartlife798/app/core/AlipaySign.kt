package com.smartlife798.app.core

/**
 * 支付宝「免密支付」签约深链（原版 `BasePayActivity.h0()` 的同款格式）。
 *
 * `sign-pga?type=20` 返回的 `code` 就是 `pgacode`；打开该链接会进入支付宝的
 * 小荷包协议页面（`appId=2019061465519660`，`page=/task/pouch`）。
 * 签约完成后服务端才会在 `trans/prepay/21` 返回空订单并直接扣款。
 */
object AlipaySign {
    const val APP_ID = "2019061465519660"

    /** The server encodes sign_params once; preserve literal '+' in raw params. */
    fun parameters(sign: String): String = if ('&' !in sign && sign.contains("%3D", ignoreCase = true)) {
        java.net.URLDecoder.decode(sign, "UTF-8")
    } else sign

    fun link(pgacode: String): String =
        "alipays://platformapi/startapp?appId=$APP_ID&page=/task/pouch&query=pgacode=$pgacode"
}
