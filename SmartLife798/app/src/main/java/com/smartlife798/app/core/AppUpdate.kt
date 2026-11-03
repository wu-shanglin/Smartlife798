package com.smartlife798.app.core

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class UpdateRelease(val versionName: String, val notes: String)

data class UpdatePolicy(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val notes: String,
    val sha256: String,
    val releases: List<UpdateRelease> = emptyList(),
    val historyAvailable: Boolean = false,
    val artifactName: String? = null,
) {
    val releaseUrl: String get() = "$REPOSITORY/releases/tag/v$versionName"
    // The installer must never see different APK bytes under the same content URI.
    val cacheApkName: String get() {
        require(versionCode > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
        ReleaseVersion.parse(versionName)
        return "SmartLife798-$versionName-$versionCode-$sha256.apk"
    }
    val apkName: String get() = artifactName ?: "SmartLife798-$versionName-debug.apk"
    val apkUrl: String get() = "$REPOSITORY/releases/download/v$versionName/$apkName"
    fun available(currentCode: Int) = versionCode > currentCode
    fun required(currentCode: Int) = available(currentCode) && currentCode < minSupportedVersionCode

    fun notesSince(currentName: String): String {
        val current = ReleaseVersion.parse(currentName)
        val latest = ReleaseVersion.parse(versionName)
        val newer = releases.filter { ReleaseVersion.parse(it.versionName) > current &&
            ReleaseVersion.parse(it.versionName) <= latest }
            .sortedByDescending { ReleaseVersion.parse(it.versionName) }
        return if (newer.isEmpty()) notes else newer.joinToString("\n\n") { "${it.versionName}\n${it.notes}" }
    }

    companion object {
        const val REPOSITORY = "https://github.com/wu-shanglin/Smartlife798"

        /** Strict parsing: malformed metadata must never lock users out. */
        fun parse(json: String): UpdatePolicy {
            val root = JsonParser.parseString(json).asJsonObject
            fun positiveInt(key: String): Int {
                val value = root.get(key)?.asJsonPrimitive ?: error("Missing update field")
                require(value.isNumber && value.toString().matches(Regex("[1-9][0-9]*")))
                return value.asString.toInt()
            }
            val code = positiveInt("versionCode")
            val minimum = positiveInt("minSupportedVersionCode")
            val name = root.get("versionName")?.asJsonPrimitive?.also { require(it.isString) }
                ?.asString ?: error("Missing version name")
            ReleaseVersion.parse(name)
            require(minimum <= code)
            val notes = root.get("notes")?.asJsonPrimitive?.also { require(it.isString) }?.asString.orEmpty()
            require(notes.length <= 4000)
            val hash = root.get("sha256")?.asJsonPrimitive?.also { require(it.isString) }
                ?.asString ?: error("Missing APK hash")
            require(hash.matches(Regex("[0-9a-f]{64}")))
            // History is optional for old releases. Bad history must not disable a valid update policy.
            val releases = try {
                root.getAsJsonArray("releases")?.also { require(it.size() <= 256) }?.map { entry ->
                    val item = entry.asJsonObject
                    val releaseName = item.get("versionName").asJsonPrimitive.also { require(it.isString) }.asString
                    ReleaseVersion.parse(releaseName)
                    val releaseNotes = item.get("notes").asJsonPrimitive.also { require(it.isString) }.asString
                    require(releaseNotes.isNotBlank() && releaseNotes.length <= 4000)
                    UpdateRelease(releaseName, releaseNotes)
                }.orEmpty().also { history ->
                    require(history.map { it.versionName }.distinct().size == history.size)
                    require(history.isEmpty() || history.any { it.versionName == name })
                }
            } catch (_: RuntimeException) {
                emptyList()
            }
            val historyAvailable = root.get("historyAvailable")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
            } == true
            val artifact = root.get("apkName")?.asString
            require(artifact == null || artifact in setOf("SmartLife798-$name-release.apk", "SmartLife798-$name-debug.apk"))
            return UpdatePolicy(code, name, minimum, notes, hash, releases, historyAvailable, artifact)
        }

        fun cached(json: String?): UpdatePolicy? = try {
            json?.let(::parse)
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Dedicated public client: no business interceptors, cookies, credentials or retries. */
object AppUpdate {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Version binding prevents a stale/moved history asset from being shown for another target. */
    internal fun mergeHistory(policyJson: String, historyJson: String): String {
        val policy = UpdatePolicy.parse(policyJson)
        val history = JsonParser.parseString(historyJson).asJsonObject
        require(history.get("versionName").asString == policy.versionName)
        val root = JsonParser.parseString(policyJson).asJsonObject
        root.add("releases", history.getAsJsonArray("releases"))
        return root.toString().also { require(UpdatePolicy.parse(it).releases.isNotEmpty()) }
    }

    internal fun retainHistory(freshJson: String, cachedJson: String?): String {
        val fresh = UpdatePolicy.parse(freshJson)
        val cached = UpdatePolicy.cached(cachedJson)
        if (fresh.releases.isNotEmpty() || cached == null || cached.releases.isEmpty() ||
            fresh.versionCode != cached.versionCode || fresh.versionName != cached.versionName ||
            fresh.sha256 != cached.sha256 || fresh.notes != cached.notes) return freshJson
        val root = JsonParser.parseString(freshJson).asJsonObject
        root.add("releases", com.google.gson.Gson().toJsonTree(cached.releases))
        return root.toString()
    }

    data class Result(val json: String, val source: UpdateSource)

    suspend fun fetch(preferred: UpdateSource): Result = fetchWith(preferred, ::fetchSource)

    internal suspend fun fetchWith(preferred: UpdateSource, fetch: suspend (UpdateSource) -> String): Result {
        var failure: Exception? = null
        for (source in preferred.attempts()) {
            try { return Result(fetch(source), source) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: java.io.IOException) { failure = e }
            catch (e: RuntimeException) { failure = e }
        }
        throw failure ?: java.io.IOException("No update source")
    }

    private suspend fun fetchSource(source: UpdateSource): String {
        val json = client.fetchPublicText(source.url("${UpdatePolicy.REPOSITORY}/releases/latest/download/update.json"), 16_384)
        val policy = UpdatePolicy.parse(json)
        if (!policy.historyAvailable) return json
        return try {
            val history = client.fetchPublicText(
                source.url("${UpdatePolicy.REPOSITORY}/releases/download/v${policy.versionName}/release-history.json"), 1_048_576,
            )
            mergeHistory(json, history)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            json
        } catch (_: RuntimeException) {
            json
        }
    }
}
