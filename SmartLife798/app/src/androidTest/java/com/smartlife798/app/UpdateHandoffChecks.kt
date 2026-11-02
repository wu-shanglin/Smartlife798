package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.smartlife798.app.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.source
import okio.buffer
import okhttp3.ResponseBody.Companion.asResponseBody

/** Real APK bytes and FileProvider reads; no live network or package installation. */
object UpdateHandoffChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "account guard"
        val field = UpdatePackage::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(null)
        val context = test.targetContext
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val legacy = File(directory, "update.apk")
        val unrelated = File(directory, "handoff-unrelated.txt")
        var saved: File? = null
        var createdFixtures = false
        try {
            test.runOnMainSync { ServiceLocator.init(context) }
            check(Store.token.isNullOrBlank())
            check(!legacy.exists() && !unrelated.exists())
            val source = File(context.applicationInfo.sourceDir)
            fun hash(file: File): String = file.inputStream().use {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
            val policy = UpdatePolicy(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, 1, "", hash(source))
            createdFixtures = true
            legacy.writeText("old installer bytes")
            unrelated.writeText("preserve unrelated cache")
            val oldUri = UpdatePackage.installIntent(context, legacy).data
            field.set(null, OkHttpClient.Builder().addInterceptor { chain ->
                check(chain.request().url.toString() == policy.apkUrl ||
                    chain.request().url.toString() == policy.copy(versionCode = policy.versionCode + 1).apkUrl)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("offline APK fixture").body(source.source().buffer().asResponseBody(contentLength = source.length())).build()
            }.build())
            stage = "verified download replaces legacy URI"
            saved = runBlocking { UpdatePackage.download(context, policy) { } }
            check(saved.name == policy.cacheApkName && saved.exists())
            check(!legacy.exists() && unrelated.readText() == "preserve unrelated cache")
            val intent = UpdatePackage.installIntent(context, saved)
            val uri = requireNotNull(intent.data)
            check(uri != oldUri && uri.lastPathSegment == policy.cacheApkName)
            check(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            stage = "provider hands actual verified bytes to installer"
            val fromProvider = File(context.cacheDir, "handoff-provider.apk")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    fromProvider.outputStream().use { input.copyTo(it) }
                }
                check(hash(fromProvider) == policy.sha256)
                UpdatePackage.verify(context, fromProvider, policy)
            } finally { fromProvider.delete() }
            stage = "failed next release retains verified artifact"
            legacy.writeText("retain if next download fails")
            var rejected = false
            try { runBlocking { UpdatePackage.download(context, policy.copy(versionCode = policy.versionCode + 1)) { } } }
            catch (_: UpdateVerificationException) { rejected = true }
            check(rejected && saved.exists() && legacy.exists() && unrelated.exists())
            check(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
            stage = "repeat download yields same verified content"
            val again = runBlocking { UpdatePackage.download(context, policy) { } }
            check(UpdatePackage.installIntent(context, again).data == uri && hash(again) == policy.sha256)
            result.putString("stream", "PASS: terminal real-APK download; legacy URI differs; FileProvider bytes/version/signature match; failed next version retains valid package, removes partial; unrelated cache retained; repeated same artifact URI stable. No business network or install.\n")
            code = Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}\n")
        } finally {
            field.set(null, original)
            saved?.delete()
            if (createdFixtures) { legacy.delete(); unrelated.delete() }
        }
        test.finish(code, result)
    }
}
