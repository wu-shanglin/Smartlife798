package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.smartlife798.app.core.WxPay
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.modelpay.PayReq
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Fake SDK object only: no installed WeChat, external activity, network or funds. */
object WxCallbackChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val api = WxPay::class.java.getDeclaredField("api").apply { isAccessible = true }
        val original = api.get(null)
        val request = AtomicReference<PayReq>()
        val sdk = Proxy.newProxyInstance(IWXAPI::class.java.classLoader, arrayOf(IWXAPI::class.java)) { _, method, args ->
            when (method.name) {
                "isWXAppInstalled", "registerApp" -> true
                "sendReq" -> { request.set(args!![0] as PayReq); true }
                else -> null
            }
        } as IWXAPI
        try {
            api.set(null, sdk)
            val order = WxPay.Order("offline", "offline", "offline", "offline", "1", "Sign=WXPay", "offline-signature")
            runBlocking {
                check(WxPay.pay(test.targetContext, order, timeoutMillis = 100L).code == -5)
                val stale = request.get().transaction
                val pending = async { WxPay.pay(test.targetContext, order, timeoutMillis = 5_000L) }
                yield()
                check(WxPay.pay(test.targetContext, order, timeoutMillis = 100L).code == -4)
                WxPay.deliver(0, stale)
                delay(30)
                check(!pending.isCompleted)
                WxPay.deliver(0, request.get().transaction)
                check(pending.await().success)
                val cancelled = async { WxPay.pay(test.targetContext, order) }
                yield()
                cancelled.cancelAndJoin()
                check(WxPay.pay(test.targetContext, order, timeoutMillis = 100L).code == -5)
            }
            result.putString("stream", "PASS: callback timeout, duplicate-entry rejection, stale transaction ignored, matching success, cancellation unlock and retry; fake SDK only\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.javaClass.simpleName}; source=${failure.stackTrace.firstOrNull { it.className.startsWith("com.smartlife798.app") }}\n")
        } finally { api.set(null, original) }
        test.finish(code, result)
    }
}
