package com.smartlife798.app.core

import android.content.Context
import com.google.gson.JsonElement
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
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
 * 客户端不伪造结果，回调仅保留状态码和安全文案。
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
    private var apiAppId: String? = null
    private val payment = Mutex()
    private data class Pending(val id: String, val prepayId: String, val reply: CompletableDeferred<Result>)
    @Volatile private var pending: Pending? = null

    @Synchronized
    fun api(context: Context, appId: String? = null): IWXAPI {
        val requested = appId ?: apiAppId ?: FALLBACK_APP_ID
        api?.let { if (appId == null || apiAppId == null || apiAppId == requested) return it }
        api?.detach()
        val created = WXAPIFactory.createWXAPI(context.applicationContext, requested, true)
        created.registerApp(requested)
        apiAppId = requested
        api = created
        return created
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { api(context).isWXAppInstalled }.getOrDefault(false)

    /** Parses the prepay payload; accepts both camelCase and the server's snake-case keys. */
    fun parse(json: JsonElement?): Order? {
        val obj = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.let { p -> p.isString || p.isNumber } }
                ?.asString?.takeIf { it.isNotBlank() }
        }
        val appId = text("appid", "appId") ?: FALLBACK_APP_ID
        return Order(
            appId = appId,
            partnerId = text("partnerid", "partnerId") ?: return null,
            prepayId = text("prepayid", "prepayId") ?: return null,
            nonceStr = text("noncestr", "nonceStr") ?: return null,
            timeStamp = text("timestamp", "timeStamp") ?: return null,
            packageValue = text("package", "packageValue") ?: "Sign=WXPay",
            sign = text("sign") ?: return null,
        )
    }

    /** Sends the request and suspends until [deliver] is called from the WX entry activity. */
    suspend fun pay(context: Context, order: Order, timeoutMillis: Long = 120_000L): Result {
        if (!payment.tryLock()) return Result(-4, "支付处理中，请勿重复操作")
        var own: Pending? = null
        try {
            val wx = api(context, order.appId)
            if (!wx.isWXAppInstalled) return Result(-2, "未安装微信，无法完成支付")
            wx.registerApp(order.appId)
            val deferred = CompletableDeferred<Result>()
            val id = java.util.UUID.randomUUID().toString()
            own = Pending(id, order.prepayId, deferred)
            synchronized(this) { pending = own }
            val request = PayReq().apply {
                transaction = id
                appId = order.appId
                partnerId = order.partnerId
                prepayId = order.prepayId
                nonceStr = order.nonceStr
                timeStamp = order.timeStamp
                packageValue = order.packageValue
                sign = order.sign
            }
            val sent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                runCatching { wx.sendReq(request) }.getOrDefault(false)
            }
            if (!sent) {
                return Result(-3, "拉起微信支付失败")
            }
            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: Result(-5, "支付结果待确认，请查询账单，勿重复付款")
        } finally {
            synchronized(this) { if (pending === own) pending = null }
            payment.unlock()
        }
    }

    /** Called by the `wxapi` entry activity. */
    @Synchronized
    fun deliver(code: Int, transaction: String?, prepayId: String? = null) {
        val current = pending?.takeIf { matchesCallback(it.id, it.prepayId, transaction, prepayId) } ?: return
        pending = null
        val safeMessage = when (code) {
            0 -> "请以服务端账单确认为准"
            -2 -> "用户已取消，请核对账单状态"
            else -> "微信未确认支付，请核对账单状态"
        }
        current.reply.complete(Result(code, safeMessage))
    }

    internal fun matchesCallback(expected: String, expectedPrepayId: String, transaction: String?, prepayId: String?): Boolean =
        if (!transaction.isNullOrBlank()) transaction == expected
        else !prepayId.isNullOrBlank() && prepayId == expectedPrepayId
}
