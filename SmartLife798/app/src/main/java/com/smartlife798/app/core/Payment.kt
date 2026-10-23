package com.smartlife798.app.core

import android.app.Activity
import com.alipay.sdk.app.PayTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alipay app-payment bridge.
 *
 * The order string returned by `GET /api/v1/trans/prepay/21?id=<billId>` is signed
 * by the merchant key on the server; the client only hands it to Alipay's SDK and
 * reports the result back to the server (`trans/confirm`). No local signature,
 * secret or payment logic is involved, and the APK signing key is irrelevant.
 */
object AlipaySdk {
    data class Result(val success: Boolean, val status: String, val memo: String)

    suspend fun pay(activity: Activity, orderInfo: String): Result = withContext(Dispatchers.IO) {
        try {
            val raw: Map<String, String> = PayTask(activity).payV2(orderInfo, true)
            val status = raw["resultStatus"].orEmpty()
            val memo = raw["memo"] ?: raw["result"].orEmpty()
            Diag.event("alipay.result", "resultStatus" to status, "memo" to memo)
            Result(
                success = status == "9000",
                status = status,
                memo = memo,
            )
        } catch (e: Throwable) {
            Diag.event("alipay.exception", "message" to (e.message ?: e.javaClass.simpleName))
            Result(false, "error", e.message ?: "支付宝调用失败")
        }
    }

    fun isInstalled(context: android.content.Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo("com.eg.android.AlipayGphone", 0)
            true
        }.getOrDefault(false)
}

/** Business-level payment orchestration with explicit, user-visible states. */
object PaymentFlow {
    sealed interface Outcome {
        data class Paid(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
        data class Unavailable(val message: String) : Outcome
    }

    fun isWalletChannel(type: Int): Boolean = type == 91 || type == 92 || type == 93

    fun channelLabel(type: Int, fallback: String?): String = when (type) {
        11 -> "微信支付"
        21 -> "支付宝"
        41 -> "云闪付"
        51 -> "招商银行"
        52 -> "农业银行"
        91 -> "商家钱包"
        92 -> "账户余额"
        93 -> "一卡通"
        else -> fallback ?: "支付方式 $type"
    }
}
