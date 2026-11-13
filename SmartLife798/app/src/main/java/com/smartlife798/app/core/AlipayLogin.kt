package com.smartlife798.app.core

import android.app.Activity
import com.alipay.sdk.app.AuthTask
import com.smartlife798.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AlipayLogin {
    val isConfigured: Boolean get() = BuildConfig.ALIPAY_LOGIN_AUTH_INFO.isNotBlank()

    suspend fun authorize(activity: Activity): Res<String> {
        if (!isConfigured) return Res.Fail(-1, "当前版本暂未开通支付宝登录")
        if (!AlipaySdk.isInstalled(activity)) return Res.Fail(-1, "请先安装支付宝，或使用手机号登录")
        return withContext(Dispatchers.IO) {
            try {
                parseResult(AuthTask(activity).authV2(BuildConfig.ALIPAY_LOGIN_AUTH_INFO, true))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Res.Fail(-1, "无法完成支付宝授权，请稍后重试")
            }
        }
    }

    fun parseResult(result: Map<String, String>?): Res<String> {
        when (result?.get("resultStatus")) {
            "6001" -> return Res.Fail(-1, "已取消支付宝授权")
            "6002" -> return Res.Fail(-1, "支付宝网络异常，请稍后重试")
            "9000" -> Unit
            else -> return authorizationFailed()
        }
        val fields = result["result"].orEmpty().split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
        val resultCode = fields.singleOrNull { it.first == "result_code" }?.second
        val authCode = fields.singleOrNull { it.first == "auth_code" }?.second
        return if (resultCode == "200" && authCode != null && validCode(authCode)) {
            Res.Ok(authCode)
        } else {
            authorizationFailed()
        }
    }

    fun validCode(code: String): Boolean =
        code.isNotEmpty() && code.all { it in '!'..'~' && it != '&' && it != '"' }

    private fun authorizationFailed() = Res.Fail(-1, "支付宝授权未完成，请重试或使用手机号登录")
}
