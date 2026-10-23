package com.smartlife798.app.core

import android.content.Context
import com.google.gson.JsonElement
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import kotlinx.coroutines.CompletableDeferred

/**
 * 微信支付桥（与原 APK 的 `PayHelper` 同链路）。
 *
 * 服务端 `GET /trans/prepay/11?id=<billId>` 返回 `appid/partnerid/prepayid/noncestr/
 * timestamp/package/sign` 七元组，客户端把它填进 [PayReq] 并交给微信 SDK；结果从
 * `com.smartlife798.app.wxapi.WXPayEntryActivity` 回传，最后仍以 `trans/confirm` 为准。
 *
 * 注意：微信要求 **应用包名 + 签名 + appId** 在开放平台登记一致。原 APK 的
 * `wx03092553dbf49755` 绑定的是原厂签名，重制版若用 debug 签名，微信会返回 -1（签名校验失败）。
 * 客户端不伪造结果，失败原因按微信回调原样上报。
 */
object WxPay {
    /** Fallback app id hardcoded in the original `PayHelper` (used when `appid` is absent). */
    const val FALLBACK_APP_ID = "wx03092553dbf49755"

    data class Order(
        val appId: String,
        val partnerId: String,
        val prepayId: String,
        val nonceStr: String,
        val timeStamp: String,
        val packageValue: String,
        val sign: String,
    )

    data class Result(val code: Int, val message: String?) {
        val success: Boolean get() = code == 0
    }

    private var api: IWXAPI? = null
    private var pending: CompletableDeferred<Result>? = null

    fun api(context: Context): IWXAPI {
        api?.let { return it }
        val created = WXAPIFactory.createWXAPI(context.applicationContext, FALLBACK_APP_ID, true)
        created.registerApp(FALLBACK_APP_ID)
        api = created
        return created
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { api(context).isWXAppInstalled }.getOrDefault(false)

    /** Parses the prepay payload; accepts both camelCase and the server's snake-case keys. */
    fun parse(json: JsonElement?): Order? {
        val obj = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }
        val appId = text("appid", "appId") ?: FALLBACK_APP_ID
        return Order(
            appId = appId,
            partnerId = text("partnerid", "partnerId") ?: return null,
            prepayId = text("prepayid", "prepayId") ?: return null,
            nonceStr = text("noncestr", "nonceStr") ?: return null,
            timeStamp = text("timestamp", "timeStamp") ?: return null,
            packageValue = text("package") ?: "Sign=WXPay",
            sign = text("sign") ?: return null,
        )
    }

    /** Sends the request and suspends until [deliver] is called from the WX entry activity. */
    suspend fun pay(context: Context, order: Order): Result {
        val wx = api(context)
        if (!wx.isWXAppInstalled) return Result(-2, "未安装微信，无法完成支付")
        wx.registerApp(order.appId)
        val deferred = CompletableDeferred<Result>()
        pending?.cancel()
        pending = deferred
        val request = PayReq().apply {
            appId = order.appId
            partnerId = order.partnerId
            prepayId = order.prepayId
            nonceStr = order.nonceStr
            timeStamp = order.timeStamp
            packageValue = order.packageValue
            sign = order.sign
        }
        val sent = runCatching { wx.sendReq(request) }.getOrDefault(false)
        if (!sent) {
            pending = null
            return Result(-3, "拉起微信支付失败")
        }
        return deferred.await()
    }

    /** Called by the `wxapi` entry activity. */
    fun deliver(code: Int, message: String?) {
        pending?.complete(Result(code, message))
        pending = null
    }
}
