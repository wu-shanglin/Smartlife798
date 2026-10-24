package com.smartlife798.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Locally persisted, non-authoritative data (session + device bookkeeping). */
data class RecentDevice(
    val id: String,
    val name: String,
    val dtype: Int,
    val lastUsedAt: Long,
    val uses: Int = 1,
    val scannedOnly: Boolean = false,
)

/** A usage session started from this client; live state always comes from the server. */
data class ActiveUsage(
    val deviceId: String,
    val name: String,
    val dtype: Int,
    val startedAt: Long,
    val payType: Int,
)

data class StoredProfile(
    val id: String,
    val name: String,
    val phone: String,
    val no: String? = null,
    val arrear: Double? = null,
    val score: Int? = null,
)

/**
 * The only class allowed to touch SharedPreferences.
 *
 * Keys fall into two groups; anything new must be classified in one of them:
 *  * **account data** — removed by [clearAccount] (token, profile, favourites
 *    cache, and the device indices that account produced);
 *  * **install preference** — kept across logins (theme mode, dynamic colour).
 */
object Store {
    private const val PREF = "smartlife798"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    /** Installation-wide validated update policy; retained on logout. No account data. */
    var updatePolicyJson: String?
        get() = prefs.getString("updatePolicy", null)
        set(value) {
            if (!Preview.enabled) prefs.edit { putString("updatePolicy", value) }
        }

    /** Public original-app version; installation-wide and retained on logout. */
    var originalAppVersion: String?
        get() = prefs.getString("originalAppVersion", null)
        set(value) {
            if (!Preview.enabled) prefs.edit { putString("originalAppVersion", value) }
        }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit { putString("token", v) }

    var uid: String?
        get() = prefs.getString("uid", null)
        set(v) = prefs.edit { putString("uid", v) }

    var phone: String?
        get() = prefs.getString("phone", null)
        set(v) = prefs.edit { putString("phone", v) }

    var profileJson: String?
        get() = prefs.getString("profile", null)
        set(v) = prefs.edit { putString("profile", v) }

    fun profile(): StoredProfile? = profileJson?.let {
        runCatching { gson.fromJson(it, StoredProfile::class.java) }.getOrNull()
    }

    fun saveProfile(p: StoredProfile) {
        profileJson = gson.toJson(p)
    }

    fun recents(): MutableList<RecentDevice> =
        runCatching {
            gson.fromJson<List<RecentDevice>>(
                prefs.getString("recents", "[]"),
                object : TypeToken<List<RecentDevice>>() {}.type,
            ).toMutableList()
        }.getOrElse { mutableListOf() }

    fun saveRecents(list: List<RecentDevice>) {
        prefs.edit { putString("recents", gson.toJson(list.take(60))) }
    }

    /**
     * Records a scan (or a completed start) for the device.
     *
     * A scan never downgrades a device that was actually used, so `uses` and the
     * "used at least once" flag stay monotonic.
     */
    fun touchRecent(device: Device, used: Boolean) {
        val list = recents()
        val idx = list.indexOfFirst { it.id == device.id }
        val existing = if (idx >= 0) list.removeAt(idx) else null
        list.add(
            0,
            RecentDevice(
                id = device.id,
                name = device.name.ifBlank { existing?.name ?: "未命名设备" },
                dtype = device.dtype,
                lastUsedAt = System.currentTimeMillis(),
                uses = (existing?.uses ?: 0) + if (used) 1 else 0,
                scannedOnly = if (used) false else (existing?.scannedOnly ?: true),
            ),
        )
        saveRecents(list)
    }

    fun clearRecent(id: String) {
        saveRecents(recents().filterNot { it.id == id })
    }

    fun clearRecents() {
        saveRecents(emptyList())
    }

    fun pinned(): Set<String> =
        prefs.getStringSet("pinned", emptySet())?.toMutableSet() ?: mutableSetOf()

    fun setPinned(id: String, pinned: Boolean) {
        val s = pinned().toMutableSet()
        if (pinned) s.add(id) else s.remove(id)
        prefs.edit { putStringSet("pinned", s) }
    }

    var activeUsageJson: String?
        get() = prefs.getString("active", null)
        set(v) = prefs.edit { putString("active", v) }

    fun active(): ActiveUsage? = activeUsageJson?.let {
        runCatching { gson.fromJson(it, ActiveUsage::class.java) }.getOrNull()
    }

    fun setActive(a: ActiveUsage?) {
        activeUsageJson = a?.let { gson.toJson(it) }
    }

    /** Accepted command awaiting state confirmation; never a confirmed usage. */
    fun pendingStart(): ActiveUsage? = prefs.getString("pendingStart", null)?.let {
        runCatching { gson.fromJson(it, ActiveUsage::class.java) }.getOrNull()
    }

    fun setPendingStart(usage: ActiveUsage?) {
        if (!Preview.enabled) prefs.edit { putString("pendingStart", usage?.let(gson::toJson)) }
    }

    fun favoritesCacheJson(): String? = prefs.getString("favcache", null)

    fun saveFavoritesCache(v: String?) {
        prefs.edit { putString("favcache", v) }
    }

    // ------------------------------------------------------------ appearance

    /** [ThemeMode.name] or null when the user never chose. */
    fun themeModeName(): String? = prefs.getString("themeMode", null)

    fun saveThemeMode(mode: ThemeMode) {
        if (!Preview.enabled) prefs.edit { putString("themeMode", mode.name) }
    }

    /** True when the user opted into wallpaper-based dynamic colour (API 31+). */
    fun dynamicColor(): Boolean = prefs.getBoolean("dynamicColor", false)

    fun saveDynamicColor(enabled: Boolean) {
        if (!Preview.enabled) prefs.edit { putBoolean("dynamicColor", enabled) }
    }

    fun startupPage(): StartupPage = StartupPage.fromName(prefs.getString("startupPage", null))

    fun saveStartupPage(page: StartupPage) {
        if (!Preview.enabled) prefs.edit { putString("startupPage", page.name) }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }

    /**
     * Drops everything derived from the signed-in account, including the device
     * bookkeeping it produced (recent list / pins), so a different account can
     * never inherit another account's devices.
     *
     * Installation preferences are deliberately retained: logging out should
     * not reset the user's appearance or startup-page choice.
     */
    fun clearAccount() {
        prefs.edit {
            remove("token")
            remove("uid")
            remove("phone")
            remove("profile")
            remove("active")
            remove("pendingStart")
            remove("favcache")
            remove("recents")
            remove("pinned")
        }
    }
}

/** Process-wide session state shared by the HTTP layer and the UI. */
object session {
    @Volatile var token: String? = null
    @Volatile var uid: String? = null
    @Volatile var phone: String? = null

    /** Incremented whenever the server rejects the token; the UI logs out. */
    val tokenRejected = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun versionHeader(): String = OriginalAppVersion.current.value

    fun restore() {
        token = Store.token
        uid = Store.uid
        phone = Store.phone
    }

    fun saveLogin(info: LoginInfo, phone: String) {
        token = info.token
        uid = info.uid
        this.phone = phone
        Store.token = info.token
        Store.uid = info.uid
        Store.phone = phone
    }

    fun clear() {
        token = null
        uid = null
        phone = null
        Store.clearAccount()
    }

    /**
     * Drops the in-memory credentials without touching persisted storage.
     *
     * Preview mode uses this so that even a code path which forgot its
     * `Preview.enabled` check cannot reach the real account with a live token.
     */
    fun suspendForPreview() {
        token = null
        uid = null
        phone = null
    }

    fun onTokenRejected() {
        if (token != null) {
            tokenRejected.value = tokenRejected.value + 1
        }
    }

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()
}
