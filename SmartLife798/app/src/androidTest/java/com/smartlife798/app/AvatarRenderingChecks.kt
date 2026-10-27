package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartlife798.app.core.Avatar
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.StoredProfile
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.components.NetworkAvatar
import com.smartlife798.app.ui.theme.SmartLifeTheme
import com.smartlife798.app.vm.MainViewModel
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Real Android PNG decoding/rendering with an isolated terminal interceptor; no external traffic. */
object AvatarRenderingChecks {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account-free guard"
        var activity: MainActivity? = null
        var fixtureStarted = false
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val clientField = Avatar::class.java.getDeclaredField("client").apply { isAccessible = true }
        val originalClient = clientField.get(null)
        val originalToken = session.token
        val originalUid = session.uid
        val originalPhone = session.phone
        val requests = AtomicInteger()
        val mode = AtomicInteger()
        val gate = AtomicReference<Gate?>()
        val currentCall = AtomicReference<Call?>()
        val gates = mutableListOf<Gate>()
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val png = ByteArrayOutputStream().use { stream ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        source.recycle()
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "avatar.invalid")
            check(chain.request().header("Authorization") == null)
            requests.incrementAndGet()
            currentCall.set(chain.call())
            gate.getAndSet(null)?.let {
                it.entered.countDown()
                check(it.release.await(20, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (mode.get() == 1) 404 else 200).message("offline avatar fixture")
                .body(png.toResponseBody("image/png".toMediaType())).build()
        }.build()
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank() && Store.profileJson == null &&
                Store.active() == null && Store.pendingStart() == null && Store.recents().isEmpty() &&
                Store.pinned().isEmpty() && session.tokenRejected.value == 0)
            fixtureStarted = true
            clientField.set(null, fixture)
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            val screen = activity
            lateinit var vm: MainViewModel
            fun recreate(image: String) = test.runOnMainSync {
                screen.setContent { }
                screen.viewModelStore.clear()
                Preview.enabled = false
                session.token = "offline-avatar-fixture"
                Store.saveProfile(StoredProfile("offline-avatar", "离线头像", "", img = image))
                vm = ViewModelProvider(screen)[MainViewModel::class.java]
                vm.setAppResumed(false)
            }
            val avatarVisible = androidx.compose.runtime.mutableStateOf(true)
            fun show() = test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme(darkTheme = false, dynamicColor = false) {
                        val profile by vm.profile.collectAsStateWithLifecycle()
                        val bitmap = com.smartlife798.app.ui.components.rememberAvatar(profile?.id, profile?.img, vm::loadAvatar)
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center) {
                            if (avatarVisible.value) NetworkAvatar(bitmap = bitmap,
                                name = profile?.name.orEmpty(), modifier = Modifier.size(96.dp))
                        }
                    }
                }
            }
            val image = "https://avatar.invalid/success.png"
            recreate(image)
            stage = "VM downloads and Android decodes actual PNG pixels"
            val decoded = runBlocking { vm.loadAvatar(image) } ?: error("PNG decode failed")
            check(decoded.width == 64 && decoded.height == 64 && decoded.getPixel(10, 10) == Color.MAGENTA)
            decoded.recycle()
            val savedProfile = Store.profileJson

            stage = "visible initials while pending then actual avatar replaces them"
            val pending = Gate().also { gates.add(it); gate.set(it) }
            show()
            check(pending.entered.await(8, TimeUnit.SECONDS))
            check(!isMagenta(sample(test)))
            pending.release.countDown()
            await { isMagenta(sample(test)) }
            check(Store.profileJson == savedProfile)

            stage = "disposing and restoring the list item retains pixels without a new download"
            val afterLoad = requests.get()
            test.runOnMainSync { avatarVisible.value = false }
            test.waitForIdleSync()
            await { !isMagenta(sample(test)) }
            test.runOnMainSync { avatarVisible.value = true }
            test.waitForIdleSync()
            await { isMagenta(sample(test)) }
            check(requests.get() == afterLoad)

            stage = "failed image falls back without changing account storage"
            mode.set(1)
            val beforeFailure = requests.get()
            recreate("https://avatar.invalid/missing.png")
            val failureProfile = Store.profileJson
            show()
            await { requests.get() > beforeFailure }
            test.waitForIdleSync()
            SystemClock.sleep(300)
            check(!isMagenta(sample(test)) && Store.profileJson == failureProfile)

            stage = "account transition cancels pending image and rejects old pixels"
            mode.set(0)
            val stale = Gate().also { gates.add(it); gate.set(it) }
            recreate("https://avatar.invalid/old-account.png")
            val storedBeforePreview = Store.profileJson
            show()
            check(stale.entered.await(8, TimeUnit.SECONDS))
            val oldCall = currentCall.get() ?: error("Missing pending image call")
            test.runOnMainSync { vm.startPreview() }
            await { oldCall.isCanceled() }
            stale.release.countDown()
            test.waitForIdleSync()
            SystemClock.sleep(300)
            check(!isMagenta(sample(test)))

            stage = "Preview cannot download or change cached account"
            val beforePreviewLoad = requests.get()
            runBlocking { check(vm.loadAvatar(image) == null) }
            check(requests.get() == beforePreviewLoad && Store.profileJson == storedBeforePreview)
            result.putString("stream", "PASS: real PNG bytes through Avatar and VM decode, pending initials, rendered avatar pixels, image-error fallback, account transition cancels old image, Preview no requests or account writes; requests=${requests.get()}\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            gates.forEach { it.release.countDown() }
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = false
            if (fixtureStarted) Store.clearAccount()
            session.token = originalToken
            session.uid = originalUid
            session.phone = originalPhone
            clientField.set(null, originalClient)
            fixture.dispatcher.executorService.shutdown()
        }
        test.finish(code, result)
    }

    private fun sample(test: Instrumentation): Int {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val offset = (20 * test.targetContext.resources.displayMetrics.density).toInt()
        return try { bitmap.getPixel(bitmap.width / 2 + offset, bitmap.height / 2 + offset) }
        finally { bitmap.recycle() }
    }

    private fun isMagenta(pixel: Int): Boolean =
        Color.red(pixel) > 240 && Color.blue(pixel) > 240 && Color.green(pixel) < 20

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        check(condition()) { "Timed out" }
    }
}
