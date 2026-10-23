package com.smartlife798.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateVerificationFailure { HASH, PACKAGE_VERSION, VERSION_NAME, SIGNATURE }
class UpdateVerificationException(val reason: UpdateVerificationFailure) : IllegalArgumentException()

/** Only exposes a verified APK in the narrow updates/ cache directory. */
object UpdatePackage {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private const val MAX_BYTES = 200L * 1024 * 1024

    suspend fun download(context: Context, policy: UpdatePolicy, progress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            check(!Preview.enabled)
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "update.part")
            val complete = File(directory, "update.apk")
            var success = false
            try {
                client.newCall(Request.Builder().url(policy.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("APK unavailable")
                    val body = response.body ?: throw IOException("Empty APK")
                    val length = body.contentLength()
                    if (length > MAX_BYTES) throw IOException("APK too large")
                    var received = 0L
                    var lastProgress = -2
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(!Preview.enabled)
                                val count = input.read(buffer)
                                if (count == -1) break
                                received += count
                                if (received > MAX_BYTES) throw IOException("APK too large")
                                output.write(buffer, 0, count)
                                val percent = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 99) else -1
                                if (percent != lastProgress) {
                                    progress(percent)
                                    lastProgress = percent
                                }
                            }
                        }
                    }
                    if (length >= 0 && received != length) throw IOException("Incomplete APK")
                }
                currentCoroutineContext().ensureActive()
                verify(context, partial, policy)
                if (complete.exists() && !complete.delete()) throw IOException("Cannot replace APK")
                if (!partial.renameTo(complete)) throw IOException("Cannot save APK")
                success = true
                complete
            } finally {
                if (!success) partial.delete()
            }
        }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, policy: UpdatePolicy) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != policy.sha256) throw UpdateVerificationException(UpdateVerificationFailure.HASH)
        val pm = context.packageManager
        // Some archive parsers leave SigningInfo empty; request legacy certificates
        // too, and still require an exact non-empty match with the installed APK.
        val flags = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName || code != policy.versionCode.toLong()) {
            throw UpdateVerificationException(UpdateVerificationFailure.PACKAGE_VERSION)
        }
        if (archive.versionName != policy.versionName) throw UpdateVerificationException(UpdateVerificationFailure.VERSION_NAME)
        fun signatures(info: PackageInfo): Set<String> {
            val signers = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners ?: info.signatures
            } else info.signatures
            return signers.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val current = signatures(installed)
        if (current.isEmpty() || current != signatures(archive)) throw UpdateVerificationException(UpdateVerificationFailure.SIGNATURE)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, file: File): Intent {
        check(!Preview.enabled)
        require(file.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
