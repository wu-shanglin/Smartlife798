package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.smartlife798.app.core.*
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** Terminal transport only; no accounts, installation or business actions. */
object UpdateSourceChecks {
    fun run(test: Instrumentation) {
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        check(Store.token.isNullOrBlank())
        val oldSource = Store.updateSource
        val oldPolicy = Store.updatePolicyJson
        val field = AppUpdate::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(null)
        val downloadField = UpdatePackage::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalDownload = downloadField.get(null)
        val requests = AtomicInteger()
        var downloadHost = ""
        var failAll = false
        var vm: MainViewModel? = null
        var stage = "setup"
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            val json = """{"versionCode":9999,"versionName":"99.0.0","minSupportedVersionCode":9999,"notes":"fixture","sha256":"${"a".repeat(64)}"}"""
            field.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                if (failAll || chain.request().url.host == "github.com") throw java.io.IOException()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(json.toResponseBody()).build()
            }.build())
            downloadField.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                downloadHost = chain.request().url.host
                throw java.io.IOException()
            }.build())
            Store.updatePolicyJson = null
            Store.updateSource = UpdateSource.GITHUB
            test.runOnMainSync { vm = MainViewModel(test.targetContext.applicationContext as android.app.Application) }
            fun checkUpdate() {
                test.runOnMainSync { vm!!.checkAppUpdate(manual = true) }
                repeat(100) {
                    test.waitForIdleSync()
                    if (!vm!!.checkingUpdate.value && requests.get() > 0) return
                    Thread.sleep(50)
                }
                error("check timeout")
            }
            stage = "fallback refusal"
            checkUpdate()
            check(vm!!.sourceFallback.value?.source == UpdateSource.GH_PROXY)
            check(vm!!.updatePolicy.value == null)
            test.runOnMainSync { vm!!.answerSourceFallback(false) }
            check(downloadHost.isEmpty() && Store.updatePolicyJson == null)
            stage = "fallback accepted without preference mutation"
            checkUpdate()
            test.runOnMainSync { vm!!.answerSourceFallback(true) }
            repeat(100) { if (downloadHost.isEmpty()) Thread.sleep(20) }
            check(downloadHost == "gh-proxy.org")
            check(Store.updateSource == UpdateSource.GITHUB && Store.updatePolicyJson == null)
            check(vm!!.updatePolicy.value?.required(BuildConfig.VERSION_CODE) == false)
            stage = "source retained after account clear and total failure"
            test.runOnMainSync { vm!!.cancelUpdateDownload() }
            Thread.sleep(100)
            test.runOnMainSync { vm!!.setUpdateSource(UpdateSource.GH_PROXY) }
            Store.clearAccount()
            check(Store.updateSource == UpdateSource.GH_PROXY)
            failAll = true
            checkUpdate()
            check(vm!!.sourceFallback.value == null)
            result.putString("stream", "PASS: fallback refusal/acceptance, temporary download routing, unchanged preference, mirror cannot force/cache policy, logout retention, all-sources failure\n")
            code = Activity.RESULT_OK
        } catch (e: Exception) {
            result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}\n")
        } finally {
            test.runOnMainSync { vm?.cancelUpdateDownload() }
            field.set(null, original)
            downloadField.set(null, originalDownload)
            Store.updateSource = oldSource
            Store.updatePolicyJson = oldPolicy
        }
        test.finish(code, result)
    }
}
