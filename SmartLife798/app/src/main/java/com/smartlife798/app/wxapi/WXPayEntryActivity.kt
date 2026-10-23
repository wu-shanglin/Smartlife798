package com.smartlife798.app.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.smartlife798.app.core.Diag
import com.smartlife798.app.core.WxPay
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler

/**
 * WeChat requires the result entry to live at `{applicationId}.wxapi.WXPayEntryActivity`.
 *
 * It only forwards the result code to [WxPay]; the payment is still confirmed with
 * `trans/confirm`, so this activity never decides success on its own.
 */
class WXPayEntryActivity : Activity(), IWXAPIEventHandler {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { WxPay.api(this).handleIntent(intent, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { WxPay.api(this).handleIntent(intent, this) }
    }

    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        Diag.event("wxpay.outcome", "errCode" to (resp?.errCode ?: -1), "errStr" to resp?.errStr)
        WxPay.deliver(resp?.errCode ?: -1, resp?.errStr)
        finish()
    }
}
