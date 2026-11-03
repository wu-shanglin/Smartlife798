package com.smartlife798.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartlife798.app.BuildConfig
import com.smartlife798.app.core.ActiveUsage
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.BillDetail
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.Wallet
import com.smartlife798.app.core.Product
import com.smartlife798.app.core.IdCata
import com.smartlife798.app.core.IdCount
import com.smartlife798.app.core.RechargeBillBody
import com.smartlife798.app.core.DeviceDetail
import com.smartlife798.app.core.deviceEntrySnapshot
import com.smartlife798.app.core.DeviceStateKind
import com.smartlife798.app.core.DevicesShelf
import com.smartlife798.app.core.Diag
import com.smartlife798.app.core.ErrorCodes
import com.smartlife798.app.core.EndCommand
import com.smartlife798.app.core.LoginData
import com.smartlife798.app.core.Master
import com.smartlife798.app.core.Paging
import com.smartlife798.app.core.PayChannel
import com.smartlife798.app.core.PaymentFlow
import com.smartlife798.app.core.QrData
import com.smartlife798.app.core.RecentDevice
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.StartSignal
import com.smartlife798.app.core.StartWatch
import com.smartlife798.app.core.StoredProfile
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.ProfilePatch
import com.smartlife798.app.core.CheckIn
import com.smartlife798.app.core.pointsBalance
import com.smartlife798.app.core.storedProfile
import com.smartlife798.app.core.activeBelongsTo
import com.smartlife798.app.core.blockedByOtherSession
import com.smartlife798.app.core.isAbnormal
import com.smartlife798.app.core.isInUse
import com.smartlife798.app.core.access
import com.smartlife798.app.core.isStandby
import com.smartlife798.app.core.isUnpaid
import com.smartlife798.app.core.mergeStatus
import com.smartlife798.app.core.statusOnly
import com.smartlife798.app.core.session
import com.smartlife798.app.core.stateLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** A pending "go pay the previous bill" request raised by a failed device start. */
    data class PayRequest(val bill: Bill?, val note: String, val createdAt: Long = System.currentTimeMillis())

    init { Store.initializeGuide() }
    private var guideSequence = 0
    private val _guideDeck: MutableStateFlow<com.smartlife798.app.core.GuideDeck?> = MutableStateFlow(
        com.smartlife798.app.core.FeatureGuide.pending(Store.guideSeen(), includeBasics = true)
            .takeIf { it.isNotEmpty() }?.let { com.smartlife798.app.core.GuideDeck(guideSequence, it, !Store.guideBasicsDone()) },
    )
    val guideDeck: StateFlow<com.smartlife798.app.core.GuideDeck?> = _guideDeck.asStateFlow()

    fun readGuideCard(deckId: Int, key: String) {
        val deck = _guideDeck.value?.takeIf { it.id == deckId } ?: return
        val topic = deck.topics.firstOrNull { it.key == key } ?: return
        Store.saveGuideSeen(setOf(key), Store.guideBasicsDone() || topic.basic)
    }

    fun finishGuide(deckId: Int) {
        val deck = _guideDeck.value?.takeIf { it.id == deckId } ?: return
        Store.saveGuideSeen(deck.topics.map { it.key }.toSet(), Store.guideBasicsDone() || deck.topics.any { it.basic })
        _guideDeck.value = null
    }

    fun dismissGuide(deckId: Int) {
        if (_guideDeck.value?.id == deckId) _guideDeck.value = null
    }

    fun replayGuides() {
        _guideDeck.value = com.smartlife798.app.core.GuideDeck(++guideSequence,
            com.smartlife798.app.core.FeatureGuide.current(), firstUse = false, manual = true)
    }

    fun resetGuides() {
        if (!_developerToolsEnabled.value) return
        Store.resetGuide()
        _guideDeck.value = com.smartlife798.app.core.GuideDeck(++guideSequence,
            com.smartlife798.app.core.FeatureGuide.current(), firstUse = true, manual = true)
    }

    private val gson = Gson()
    private val transaction = Mutex()
    private val transactionJobs = mutableSetOf<Job>()
    private var recentSaveJob: Job? = null

    private val _autoCheckIn = MutableStateFlow(Store.autoCheckIn())
    val autoCheckIn: StateFlow<Boolean> = _autoCheckIn.asStateFlow()
    private val _checkInStatus = MutableStateFlow(if (Store.autoCheckIn()) "尚未检查签到" else "自动签到已关闭")
    val checkInStatus: StateFlow<String> = _checkInStatus.asStateFlow()
    private val _checkingIn = MutableStateFlow(false)
    val checkingIn: StateFlow<Boolean> = _checkingIn.asStateFlow()

    private val _availableScore = MutableStateFlow<String?>(null)
    val availableScore = _availableScore.asStateFlow()
    private val _signedInToday = MutableStateFlow<Boolean?>(null)
    val signedInToday = _signedInToday.asStateFlow()
    fun refreshCheckInStatus() = requestCheckIn(manual = false, statusOnly = true)

    private var checkInJob: Job? = null
    private var checkInIsManual = false
    private var checkInPostStarted = false
    private var checkInResumePending = false
    private var checkInDoneDay: String? = null
    private var checkInServerTime = 0L
    private var checkInSnapshotElapsed = 0L
    private var lastAutoCheckInNotice: String? = null
    private var lastCheckInMessage: String? = null

    fun setAutoCheckIn(enabled: Boolean) {
        _autoCheckIn.value = enabled
        Store.saveAutoCheckIn(enabled)
        if (enabled) {
            if (!_checkingIn.value) _checkInStatus.value =
                "尚未检查签到"
            requestAutoCheckIn()
        } else {
            checkInResumePending = false
            // Turning off automation must not cancel an explicit manual action.
            if (!checkInIsManual || !_checkingIn.value) {
                checkInJob?.cancel()
                _checkInStatus.value = if (_checkingIn.value && checkInPostStarted)
                    "自动签到已关闭，本次结果待确认，可手动检查" else "自动签到已关闭"
            }
        }
    }

    /** Explicit user action: works with automation disabled and always reads fresh state. */
    fun checkInNow() = requestCheckIn(manual = true)

    private fun requestAutoCheckIn() = requestCheckIn(manual = false)

    private fun publishCheckIn(status: String, manual: Boolean, day: String? = null, notice: String = status) {
        _checkInStatus.value = status
        val key = "$day:$notice"
        if (manual || (lastAutoCheckInNotice != key && (day == null || Store.checkInNoticeDay() != day))) {
            if (!manual && day != null) Store.saveCheckInNoticeDay(day)
            lastAutoCheckInNotice = key
            showCheckInNotice(notice)
        }
    }

    private fun showCheckInNotice(notice: String) {
        lastCheckInMessage = notice
        _message.value = notice
    }

    private fun clearCheckInNotice() {
        if (_message.value == lastCheckInMessage) _message.value = null
        lastCheckInMessage = null
        lastAutoCheckInNotice = null
        checkInResumePending = false
    }

    private fun checkInFailure(failure: Res.Fail): String = when (failure.code) {
        ErrorCodes.NETWORK -> "网络不可用或请求超时"
        ErrorCodes.TOKEN_EXPIRED -> "登录状态已过期，请重新登录"
        else -> failure.msg.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(120)
            .ifBlank { "服务端未确认（${failure.code}）" }
    }

    private fun requestCheckIn(manual: Boolean, statusOnly: Boolean = false) {

        if (!session.isLoggedIn || !appResumed) {
            if (manual) publishCheckIn(if (!session.isLoggedIn) "请先登录后再签到" else "请在应用前台签到", manual = true)
            return
        }
        if (!manual && !statusOnly && !_autoCheckIn.value) return
        if (_checkingIn.value || checkInJob?.isCompleted == false) {
            // Resume may arrive while a cancelled request is still releasing its durable marker.
            if (!manual && checkInJob?.isCancelled == true) checkInResumePending = true
            if (manual) showCheckInNotice("签到处理中，请稍候")
            return
        }
        if (!manual && !statusOnly && Store.confirmedCheckIn()?.isCurrent(System.currentTimeMillis()) == true) {
            _checkInStatus.value = "今日已签到"
            _signedInToday.value = true
            return
        }
        if (!manual && !statusOnly && checkInDoneDay != null && checkInServerTime > 0L &&
            CheckIn.dayKey(checkInServerTime + android.os.SystemClock.elapsedRealtime() - checkInSnapshotElapsed) == checkInDoneDay) {
            _checkInStatus.value = "今日已签到"
            return
        }
        checkInIsManual = manual
        checkInPostStarted = false
        _checkingIn.value = true
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val token = session.token ?: return@launch
            val generation = accountGeneration
            fun current() = session.token == token && accountGeneration == generation
            fun allowed() = current() && appResumed && (statusOnly || manual || _autoCheckIn.value)
            var locked = false
            var submitted = false
            var recorded = false
            var ownsNewAttempt = false
            var attemptDay: String? = null
            try {
                if (manual) locked = CHECK_IN_COMMAND.tryLock() else {
                    // A previous Activity may still be releasing an unsent durable attempt.
                    CHECK_IN_COMMAND.lock()
                    locked = true
                }
                if (!locked) {
                    if (manual && current()) publishCheckIn("签到处理中，请稍候", manual = true)
                    return@launch
                }
                if (!allowed()) return@launch
                val uid = session.uid
                if (!statusOnly && uid.isNullOrBlank()) {
                    publishCheckIn("账号信息不足，暂无法签到", manual)
                    return@launch
                }
                _checkInStatus.value = "正在检查今日签到…"
                val response = Api.checkInMissions()
                if (!allowed()) return@launch
                if (response is Res.Fail) {
                    _signedInToday.value = null
                    _availableScore.value = null
                    if (statusOnly) _checkInStatus.value = "签到检查失败：${checkInFailure(response)}"
                    else publishCheckIn("签到检查失败：${checkInFailure(response)}", manual)
                    return@launch
                }
                val missions = (response as? Res.Ok)?.data
                val serverTime = (response as? Res.Ok)?.serverTime ?: 0L
                if (missions == null || serverTime <= 0L) {
                    _signedInToday.value = null
                    _availableScore.value = null
                    publishCheckIn("无法确认今日签到，服务端时间或数据缺失", manual)
                    return@launch
                }
                _availableScore.value = missions.pointsBalance()
                checkInServerTime = serverTime
                checkInSnapshotElapsed = android.os.SystemClock.elapsedRealtime()
                val day = CheckIn.dayKey(serverTime)
                attemptDay = day
                val weekday = CheckIn.weekday(serverTime)
                // Original 3.1.5 also treats a missing score account (before its first reward) as unsigned.
                // Invalid bitmaps stay unknown; the fallback never confirms a successful submission.
                val signed = CheckIn.isSignedIn(missions, weekday, allowUnsignedDefault = true)
                _signedInToday.value = signed
                if (signed == true) Store.saveConfirmedCheckIn(serverTime, token)
                else if (signed == false) Store.clearConfirmedCheckIn(token)
                if (statusOnly) {
                    checkInDoneDay = day.takeIf { signed == true }
                    _checkInStatus.value = when (signed) {
                        true -> "今日已签到"
                        false -> "今日未签到"
                        null -> "签到状态不完整，请刷新重试"
                    }
                    return@launch
                }
                when (signed) {
                    true -> {
                        checkInDoneDay = day
                        publishCheckIn("今日已签到", manual, day)
                        return@launch
                    }
                    null -> {
                        checkInDoneDay = null
                        publishCheckIn("签到状态不完整，未提交", manual, day)
                        return@launch
                    }
                    false -> Unit
                }
                checkInDoneDay = null
                val previousAttempt = Store.checkInAttemptDay() == day
                if (previousAttempt && !manual) {
                    publishCheckIn("今日签到已提交过，结果待确认；可手动检查并重试", manual, day)
                    return@launch
                }
                val params = CheckIn.prepare(missions, weekday, allowUnsignedDefault = true)
                if (params == null) {
                    publishCheckIn("签到参数不完整，未提交", manual, day)
                    return@launch
                }
                val protocolVersion = session.versionHeader()
                if (!CheckIn.supportsProtocol(protocolVersion)) {
                    publishCheckIn("当前官方协议尚未适配签到，请更新客户端；本次未提交", manual, day)
                    return@launch
                }
                if (!allowed()) return@launch
                withContext(Dispatchers.IO) {
                    // commit(false) can still update preference memory: re-confirm durability on manual retry.
                    recorded = Store.markCheckInAttempt(day, token, allowExisting = manual)
                    ownsNewAttempt = recorded && !previousAttempt
                }
                if (!recorded || !allowed()) {
                    if (current() && appResumed) publishCheckIn("未提交签到，请稍后重新检查", manual, day)
                    return@launch
                }
                val submitElapsed = android.os.SystemClock.elapsedRealtime() - checkInSnapshotElapsed
                if (CheckIn.dayKey(serverTime + submitElapsed) != day) {
                    publishCheckIn("日期已变化，请重新检查签到", manual, day)
                    return@launch
                }
                val sign = CheckIn.sign(params.adId, token, requireNotNull(uid), serverTime, submitElapsed,
                    protocolVersion = protocolVersion)
                _checkInStatus.value = if (manual) "正在签到…" else "正在自动签到…"
                submitted = true
                checkInPostStarted = true
                val result = Api.checkIn(params, sign, protocolVersion)
                if (!allowed()) return@launch
                // Reconcile even a rejection: a duplicate day or lost response can already be signed.
                val confirmed = Api.checkInMissions()
                if (!allowed()) return@launch
                val state = (confirmed as? Res.Ok)?.data
                _availableScore.value = state?.pointsBalance()
                val confirmedSigned = if (state != null && confirmed.serverTime > 0L &&
                    CheckIn.dayKey(confirmed.serverTime) == day) CheckIn.isSignedIn(state, weekday) else null
                _signedInToday.value = confirmedSigned
                if (confirmedSigned == true) {
                    checkInDoneDay = day
                    Store.saveConfirmedCheckIn((confirmed as Res.Ok).serverTime, token)
                    publishCheckIn(if (manual) "今日已签到" else "今日已自动签到", manual, day,
                        notice = if (result is Res.Fail) "今日已签到" else if (manual) "签到成功" else "自动签到成功")
                } else if (result is Res.Fail) {
                    publishCheckIn("${if (manual) "签到" else "自动签到"}未确认：${checkInFailure(result)}；请刷新状态后重试", manual, day)
                } else {
                    publishCheckIn("签到已受理，状态待确认；请刷新状态", manual, day)
                }
                refreshMasterInternal()
            } catch (cancelled: CancellationException) {
                if (current() && (statusOnly || manual || _autoCheckIn.value)) _checkInStatus.value =
                    if (submitted) "签到结果待确认，可手动检查" else "签到检查已暂停"
                throw cancelled
            } catch (_: IllegalArgumentException) {
                if (allowed()) publishCheckIn("签到参数无效，未提交", manual, attemptDay)
            } finally {
                if (ownsNewAttempt && recorded && !submitted) {
                    val day = attemptDay
                    if (day != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        if (current()) Store.clearUnsentCheckInAttempt(day, token)
                    }
                }
                if (locked) CHECK_IN_COMMAND.unlock()
            }
        }
        checkInJob = task
        task.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (checkInJob === task) {
                    _checkingIn.value = false
                    if (checkInResumePending) {
                        checkInResumePending = false
                        requestAutoCheckIn()
                    }
                }
            }
        }
        task.start()
    }

    private val _developerToolsEnabled = MutableStateFlow(Store.developerToolsEnabled())
    val developerToolsEnabled: StateFlow<Boolean> = _developerToolsEnabled.asStateFlow()

    fun setDeveloperToolsEnabled(enabled: Boolean) {
        _developerToolsEnabled.value = enabled
        Store.saveDeveloperToolsEnabled(enabled)
    }

    private val _startupPage: MutableStateFlow<StartupPage> = MutableStateFlow(Store.startupPage())
    val startupPage: StateFlow<StartupPage> = _startupPage.asStateFlow()

    fun setStartupPage(page: StartupPage) {
        _startupPage.value = page
        Store.saveStartupPage(page)
    }

    val protocolVersion = com.smartlife798.app.core.OriginalAppVersion.current
    private var protocolJob: Job? = null
    private var lastProtocolCheck = 0L
    private var protocolCheckInterval = 300_000L

    private fun refreshProtocolVersion(manual: Boolean) {
        if (protocolJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastProtocolCheck != 0L && now - lastProtocolCheck < protocolCheckInterval) return
        lastProtocolCheck = now
        protocolCheckInterval = 300_000L
        protocolJob = viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { com.smartlife798.app.core.OriginalAppVersion.fetch() }
                com.smartlife798.app.core.OriginalAppVersion.accept(version)
                protocolCheckInterval = 21_600_000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.io.IOException) {
                // Keep the last valid installation cache / bundled fallback.
            } catch (_: RuntimeException) {
                // Store layout changes and invalid versions are not protocol compatibility proof.
            }
        }
    }

    private val _updatePolicy = MutableStateFlow(com.smartlife798.app.core.UpdatePolicy.cached(Store.updatePolicyJson))
    val updatePolicy = _updatePolicy.asStateFlow()
    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate = _checkingUpdate.asStateFlow()
    private val _updateSource = MutableStateFlow(Store.updateSource)
    val updateSource = _updateSource.asStateFlow()
    private var downloadSource = Store.updateSource
    data class SourceFallback(val json: String, val source: com.smartlife798.app.core.UpdateSource)
    private val _sourceFallback = MutableStateFlow<SourceFallback?>(null)
    val sourceFallback = _sourceFallback.asStateFlow()

    fun setUpdateSource(source: com.smartlife798.app.core.UpdateSource) {
        if (_checkingUpdate.value || downloadJob?.isActive == true) return
        Store.updateSource = source
        _updateSource.value = source
        downloadSource = source
        _sourceFallback.value = null
        lastUpdateCheck = 0L
    }

    fun answerSourceFallback(accept: Boolean) {
        val fallback = _sourceFallback.value ?: return
        _sourceFallback.value = null
        if (!accept) {
            _showUpdate.value = _updatePolicy.value?.required(BuildConfig.VERSION_CODE) == true
            return
        }
        downloadSource = fallback.source
        applyUpdatePolicy(fallback.json, manual = true, trusted = fallback.source == com.smartlife798.app.core.UpdateSource.GITHUB)
        if (_updatePolicy.value?.available(BuildConfig.VERSION_CODE) == true) downloadAppUpdate()
    }

    private fun applyUpdatePolicy(fetched: String, manual: Boolean, trusted: Boolean) {
        val json = com.smartlife798.app.core.AppUpdate.retainHistory(fetched, Store.updatePolicyJson)
        // A proxy cannot introduce or persist a mandatory-update policy.
        val parsed = com.smartlife798.app.core.UpdatePolicy.parse(json)
        val known = com.smartlife798.app.core.UpdatePolicy.cached(Store.updatePolicyJson)
        if (!trusted && known != null && parsed.versionCode < known.versionCode) {
            _showUpdate.value = known.required(BuildConfig.VERSION_CODE)
            notify("备用源版本落后，请稍后重试")
            return
        }
        val policy = if (trusted) parsed else parsed.copy(minSupportedVersionCode = known?.minSupportedVersionCode ?: 1)
        if (policy != _updatePolicy.value) _updateDownload.value = UpdateDownload()
        if (trusted) Store.updatePolicyJson = json
        _updatePolicy.value = policy
        _showUpdate.value = policy.available(BuildConfig.VERSION_CODE) && (manual || dismissedUpdateCode != policy.versionCode)
        if (manual && !policy.available(BuildConfig.VERSION_CODE)) notify("当前已是最新版本")
    }

    private var updateJob: Job? = null
    private var lastUpdateCheck = 0L
    private var dismissedUpdateCode: Int? = null
    private val _showUpdate = MutableStateFlow(false)
    val showUpdate = _showUpdate.asStateFlow()
    data class UpdateDownload(
        val busy: Boolean = false,
        val percent: Int = -1,
        val ready: java.io.File? = null,
        val error: String? = null,
    )
    private val _updateDownload = MutableStateFlow(UpdateDownload())
    val updateDownload = _updateDownload.asStateFlow()
    private var downloadJob: Job? = null

    fun cancelUpdateDownload() {
        downloadJob?.cancel()
    }

    fun downloadAppUpdate() {
        if (downloadJob?.isActive == true) return
        val policy = _updatePolicy.value ?: return
        if (!policy.available(BuildConfig.VERSION_CODE)) return
        downloadJob = viewModelScope.launch {
            _updateDownload.value = UpdateDownload(busy = true)
            try {
                val file = com.smartlife798.app.core.UpdatePackage.download(getApplication(), policy, downloadSource) { percent ->
                    _updateDownload.value = UpdateDownload(busy = true, percent = percent)
                }
                if (_updatePolicy.value == policy) {
                    _updateDownload.value = UpdateDownload(ready = file, percent = 100)
                } else {
                    _updateDownload.value = UpdateDownload()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                _updateDownload.value = UpdateDownload(error = "下载失败，请检查网络和存储空间后重试")
            } catch (_: RuntimeException) {
                currentCoroutineContext().ensureActive()
                _updateDownload.value = UpdateDownload(error = "安装包校验失败，请重试；仍失败时请联系发布者核对签名与版本")
            } finally {
                if (_updateDownload.value.busy) _updateDownload.value = UpdateDownload()
            }
        }
    }

    suspend fun updateInstallIntent(): android.content.Intent? {
        val policy = _updatePolicy.value ?: return null
        val file = _updateDownload.value.ready ?: return null
        return try {
            withContext(Dispatchers.IO) { com.smartlife798.app.core.UpdatePackage.verify(getApplication(), file, policy) }
            if (policy != _updatePolicy.value) null
            else com.smartlife798.app.core.UpdatePackage.installIntent(getApplication(), file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            _updateDownload.value = UpdateDownload(error = "安装包已失效，请重新下载")
            null
        } catch (_: RuntimeException) {
            _updateDownload.value = UpdateDownload(error = "安装包校验失败，请重新下载")
            null
        }
    }

    fun checkAppUpdate(manual: Boolean = false) {

        refreshProtocolVersion(manual)
        if (updateJob?.isActive == true || downloadJob?.isActive == true || _sourceFallback.value != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastUpdateCheck != 0L && now - lastUpdateCheck < 300_000L) return
        lastUpdateCheck = now
        _updatePolicy.value?.let { cached ->
            _showUpdate.value = cached.available(BuildConfig.VERSION_CODE) &&
                (manual || dismissedUpdateCode != cached.versionCode)
        }
        updateJob = viewModelScope.launch {
            _checkingUpdate.value = true
            try {
                val preferred = _updateSource.value
                val fetched = withContext(Dispatchers.IO) { com.smartlife798.app.core.AppUpdate.fetch(preferred) }
                if (fetched.source != preferred) {
                    _showUpdate.value = false
                    _sourceFallback.value = SourceFallback(fetched.json, fetched.source)
                } else {
                    downloadSource = fetched.source
                    applyUpdatePolicy(fetched.json, manual, trusted = fetched.source == com.smartlife798.app.core.UpdateSource.GITHUB)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.io.IOException) {
                if (manual) notify("无法检查更新，请检查网络或稍后重试")
            } catch (_: RuntimeException) {
                if (manual) notify("更新信息无效，请稍后重试")
            } finally {
                _checkingUpdate.value = false
            }
        }
    }

    fun dismissAppUpdate() {
        if (_updatePolicy.value?.required(BuildConfig.VERSION_CODE) == true) return
        dismissedUpdateCode = _updatePolicy.value?.versionCode
        _showUpdate.value = false
    }

    private val _loggedIn = MutableStateFlow(session.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _profile = MutableStateFlow<StoredProfile?>(null)
    val profile: StateFlow<StoredProfile?> = _profile.asStateFlow()

    private val profileMutation = Mutex()
    private val _savingProfile = MutableStateFlow(false)
    val savingProfile: StateFlow<Boolean> = _savingProfile.asStateFlow()
    private var profileRevision = 0L
    @Volatile private var accountGeneration = 0L
    private var profileJob: Job? = null

    suspend fun changeNickname(value: String): Res<Unit> {
        val patch = try { ProfilePatch.nickname(value) } catch (_: IllegalArgumentException) {
            return Res.Fail(-1, "请输入不含控制字符的昵称")
        }
        return changeProfile(patch)
    }

    suspend fun setUseScore(enabled: Boolean): Res<Unit> = changeProfile(ProfilePatch.useScore(enabled))

    private suspend fun changeProfile(patch: ProfilePatch): Res<Unit> = withContext(Dispatchers.Main.immediate) {
        accountRestoreJob?.join()
        if (!profileMutation.tryLock()) return@withContext Res.Fail(-1, "账号设置正在保存，请稍后再试")
        if (deviceCommand.isLocked) {
            profileMutation.unlock()
            return@withContext Res.Fail(-1, "设备操作处理中，请完成后再修改账号设置")
        }
        val task = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { changeProfileLocked(patch) }
        profileJob = task
        task.await()
    }

    private suspend fun changeProfileLocked(patch: ProfilePatch): Res<Unit> {
        _savingProfile.value = true
        profileRevision += 1
        val token = session.token
        val generation = accountGeneration
        val before = _profile.value
        try {
            if (before == null) return Res.Fail(-1, "请先刷新账号资料")
            val result = run {
                if (!session.isLoggedIn) return Res.Fail(-99, "请先登录")
                Api.updateProfile(patch)
            }
            currentCoroutineContext().ensureActive()
            if (accountGeneration != generation || session.token != token) {
                throw CancellationException("Account changed")
            }
            if (result is Res.Ok) {
                val current = _profile.value ?: return Res.Fail(-1, "账号资料已变更，请刷新后重试")
                val updated = current.copy(name = patch.name ?: current.name, useScore = patch.useScore ?: current.useScore)
                _profile.value = updated
                Store.saveProfile(updated)
            }
            return result
        } finally {
            profileRevision += 1
            _savingProfile.value = false
            profileMutation.unlock()
        }
    }

    private data class CachedAvatar(val generation: Long, val image: String, val bitmap: android.graphics.Bitmap)
    @Volatile private var avatarCache: CachedAvatar? = null

    /** One small account-scoped bitmap; a recreated page can render it on its first frame. */
    fun cachedAvatar(image: String?): android.graphics.Bitmap? {
        if (!session.isLoggedIn || image == null || image != _profile.value?.img) return null
        return avatarCache?.takeIf { it.generation == accountGeneration && it.image == image }?.bitmap
    }

    suspend fun loadAvatar(image: String): android.graphics.Bitmap? {
        if (!session.isLoggedIn || image != _profile.value?.img) return null
        cachedAvatar(image)?.let { return it }
        val token = session.token
        val generation = accountGeneration
        val bitmap = com.smartlife798.app.core.Avatar.load(image)
        return bitmap?.takeIf {
            session.token == token && generation == accountGeneration && image == _profile.value?.img
        }?.also { avatarCache = CachedAvatar(generation, image, it) }
    }

    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()

    private var accountRestoreJob: Job? = null
    private data class AccountCache(val profile: StoredProfile?, val recents: List<RecentDevice>,
        val pinned: Set<String>, val active: ActiveUsage?, val pending: ActiveUsage?)
    private fun readAccountCache() = AccountCache(Store.profile(), Store.recents(), Store.pinned(), Store.active(), Store.pendingStart())
    private fun publishAccountCache(cache: AccountCache) {
        _profile.value = cache.profile
        _recents.value = cache.recents
        _pinned.value = cache.pinned
        _active.value = cache.active
        _pendingStart.value = cache.pending
    }

    private val favoritesLock = Any()
    private var favoriteRestoreJob: Job? = null
    private val favoriteMutation = Mutex()
    private var favoriteRevision = 0L
    private val _favorites = MutableStateFlow<List<Device>>(emptyList())
    val favorites: StateFlow<List<Device>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentDevice>>(emptyList())
    val recents: StateFlow<List<RecentDevice>> = _recents.asStateFlow()

    private val _pinned = MutableStateFlow<Set<String>>(emptySet())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _active = MutableStateFlow<ActiveUsage?>(null)
    val active: StateFlow<ActiveUsage?> = _active.asStateFlow()

    private val _pendingStart: MutableStateFlow<ActiveUsage?> = MutableStateFlow<ActiveUsage?>(null)
    val pendingStart: StateFlow<ActiveUsage?> = _pendingStart.asStateFlow()
    private val deviceCommand = Mutex()
    private var startJob: Job? = null
    private var deviceRevision = 0L

    private fun checkDeviceSession(token: String?) {
        if (!session.isLoggedIn || session.token != token) {
            throw CancellationException("Device session changed")
        }
    }

    private val _liveStatus = MutableStateFlow<Device?>(null)
    val liveStatus: StateFlow<Device?> = _liveStatus.asStateFlow()

    private val _liveUpdatedAt = MutableStateFlow(0L)
    val liveUpdatedAt: StateFlow<Long> = _liveUpdatedAt.asStateFlow()

    @Volatile private var appResumed = true
    private val detailPage = com.smartlife798.app.core.DevicePageOwner()
    private var watchJob: Job? = null
    private var masterJob: Job? = null
    private val accountReads = mutableMapOf<String, Deferred<Unit>>()
    private var recentBillsRead: Deferred<List<Bill>?>? = null

    /** Share only concurrent read requests, never cache business results or retry writes. */
    private suspend fun accountRead(key: String, read: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        accountRestoreJob?.join()
        val task = accountReads[key]?.takeIf { it.isActive }
            ?: viewModelScope.async(start = CoroutineStart.LAZY) { read() }.also {
                accountReads[key] = it
                it.start()
            }
        try {
            task.await()
        } finally {
            if (task.isCompleted && accountReads[key] === task) accountReads.remove(key)
        }
    }

    private fun cancelAccountReads() {
        accountReads.values.forEach { it.cancel() }
        accountReads.clear()
        recentBillsRead?.cancel()
        recentBillsRead = null
        _loadingMaster.value = false
    }
    private val endCommand = EndCommand()

    private val _loadingMaster = MutableStateFlow(false)
    val loadingMaster: StateFlow<Boolean> = _loadingMaster.asStateFlow()

    private val _masterError = MutableStateFlow<String?>(null)
    val masterError: StateFlow<String?> = _masterError.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Number of cloud bills that still need payment (status 1 / 4). */
    private val _unpaidCount = MutableStateFlow(0)
    val unpaidCount: StateFlow<Int> = _unpaidCount.asStateFlow()

    /**
     * Set when a device start is rejected because a previous bill is unpaid.
     * OrdersScreen consumes it and opens the pay sheet for that bill.
     */
    private val _payRequest = MutableStateFlow<PayRequest?>(null)
    val payRequest: StateFlow<PayRequest?> = _payRequest.asStateFlow()

    /** Incremented on every resume / navigation so screens reload without manual buttons. */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    private var refreshDebounce: Job? = null
    private var foregroundEpoch = 0L
    private data class MeRefreshKey(val account: Long, val foreground: Long, val day: String?)
    private var meRefreshKey: MeRefreshKey? = null
    private var meRefreshJob: Job? = null

    /**
     * Navigation can compose Me before its debounced refresh tick arrives. Both
     * entry signals share one account/foreground/day snapshot, so switching tabs
     * retains the loaded screen. A pull refresh is explicit and always waits for
     * the current read, or starts a new read once it has completed.
     */
    fun refreshMePage(force: Boolean = false): Job? {
        if (!session.isLoggedIn || !appResumed) return null
        meRefreshJob?.takeIf { it.isActive }?.let { return it }
        val day = checkInServerTime.takeIf { it > 0L }?.let {
            CheckIn.dayKey(it + android.os.SystemClock.elapsedRealtime() - checkInSnapshotElapsed)
        }
        val key = MeRefreshKey(accountGeneration, foregroundEpoch, day)
        if (!force && meRefreshKey == key) return null
        meRefreshKey = key
        val generation = accountGeneration
        val epoch = foregroundEpoch
        return viewModelScope.launch {
            refreshCheckInStatus()
            val statusRead = checkInJob
            val masterRead = async {
                val existing = masterJob?.takeIf { it.isActive }
                if (existing != null) existing.join() else refreshMasterInternal()
            }
            val walletRead = async { refreshWallet() }
            masterRead.await()
            walletRead.await()
            statusRead?.join()
            if (accountGeneration == generation) {
                // The first mission response may supply the previously unknown
                // server day. Adopt it without turning the next nav tick into a read.
                val loadedDay = checkInServerTime.takeIf { it > 0L }?.let {
                    CheckIn.dayKey(it + android.os.SystemClock.elapsedRealtime() - checkInSnapshotElapsed)
                }
                meRefreshKey = MeRefreshKey(generation, epoch, loadedDay)
            }
        }.also { meRefreshJob = it }
    }

    /**
     * Bursts are normal: a tab change fires the destination listener *and* the
     * resume effect, and the first frame fires both again. Coalescing them keeps
     * one reload per user-visible navigation instead of two or three.
     */
    fun bumpRefresh() {
        refreshDebounce?.cancel()
        refreshDebounce = viewModelScope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            _refreshTick.value = _refreshTick.value + 1
        }
    }

    private val _historySyncedAt = MutableStateFlow(0L)
    val historySyncedAt: StateFlow<Long> = _historySyncedAt.asStateFlow()

    /** One-shot deep link from Home's "更多" into the Devices shelf. */
    private val _requestedShelf = MutableStateFlow<DevicesShelf?>(null)

    fun requestShelf(shelf: DevicesShelf) {
        _requestedShelf.value = shelf
    }

    fun consumeShelf(): DevicesShelf? = _requestedShelf.value.also { _requestedShelf.value = null }

    private fun publishFavorites(devices: List<Device>) = synchronized(favoritesLock) {
        favoriteRestoreJob?.cancel()
        _favorites.value = devices
    }

    init {
        // Disk/JSON work must not block the first UI frame. Live data always wins.
        val restoreGeneration = accountGeneration
        val restoreToken = session.token
        accountRestoreJob = viewModelScope.launch {
            val cache = withContext(Dispatchers.IO) { readAccountCache() }
            if (restoreGeneration == accountGeneration && restoreToken == session.token) publishAccountCache(cache)
        }
        favoriteRestoreJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    Store.favoritesCacheJson()?.let {
                        gson.fromJson<List<Device>>(it, object : TypeToken<List<Device>>() {}.type)
                    }
                }.getOrNull()
            }
            synchronized(favoritesLock) {
                if (isActive && restoreGeneration == accountGeneration && session.token == restoreToken) {
                    cached?.let { _favorites.value = it }
                }
            }
        }
        viewModelScope.launch {
            _active.collect { ensureWatch() }
        }
        // A record restored from disk is a bookmark, not a fact: the machine or
        // another client may have ended it while the app was closed, and a start
        // that never took effect must not keep showing 「使用中」.
        viewModelScope.launch { _pendingStart.collect { ensureWatch() } }
        viewModelScope.launch {
            session.tokenRejected.collect { count ->
                if (count > 0 && session.isCurrentTokenRejected()) {
                    session.clear()
                    resetAccountState()
                    _message.value = "登录状态已过期，请重新登录"
                }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun notify(text: String) {
        _message.value = text
    }

    data class NfcOpen(val event: String, val deviceId: String, val autoStartTicket: String?)
    private val nfcAutoStart = com.smartlife798.app.core.NfcAutoStart()
    private val _nfcDevice = MutableStateFlow<NfcOpen?>(null)
    val nfcDevice: StateFlow<NfcOpen?> = _nfcDevice.asStateFlow()
    private var nfcResolveJob: Job? = null
    private var scannedEntry: Device? = null

    /** Resolve first. Only a verified card event can issue one volatile automatic-start ticket. */
    fun acceptNfcUris(uris: List<String>, autoStart: Boolean = false) {
        viewModelScope.launch {
            if (!session.isLoggedIn || !appResumed || nfcResolveJob?.isActive == true) return@launch
            when (val route = com.smartlife798.app.core.NfcRoutes.parseAll(uris)) {
                is com.smartlife798.app.core.NfcRoute.Qr -> {
                    val generation = accountGeneration
                    val token = session.token
                    nfcResolveJob = viewModelScope.launch {
                        val response = resolveQr(route.id)
                        if (!appResumed || accountGeneration != generation || session.token != token)
                            return@launch
                        when (response) {
                            is Res.Ok -> when (val resolved = com.smartlife798.app.core.resolveQr(
                                response.data?.dev, response.data?.qr?.type, com.smartlife798.app.core.QR_TYPE_DEVICE)) {
                                is com.smartlife798.app.core.QrResolution.Resolved -> {
                                    publishNfcDevice(resolved.device, autoStart)
                                }
                                else -> notify("卡片未返回可用设备，请移开后重新碰卡")
                            }
                            is Res.Fail -> notify("NFC 设备识别失败：${errorText(response.code, response.msg)}")
                        }
                    }
                }
                is com.smartlife798.app.core.NfcRoute.Device -> {
                    val generation = accountGeneration
                    val token = session.token
                    nfcResolveJob = viewModelScope.launch {
                        val response = loadDevice(route.id)
                        if (!appResumed || accountGeneration != generation || session.token != token)
                            return@launch
                        val device = (response as? Res.Ok)?.data?.device
                        val resolved = com.smartlife798.app.core.resolveQr(device,
                            com.smartlife798.app.core.QR_TYPE_DEVICE, com.smartlife798.app.core.QR_TYPE_DEVICE)
                        if (device?.id == route.id && resolved is com.smartlife798.app.core.QrResolution.Resolved)
                            publishNfcDevice(resolved.device, autoStart)
                        else notify("NFC 入口未返回可用设备，请刷新后重试")
                    }
                }
                is com.smartlife798.app.core.NfcRoute.Alipay ->
                    notify("该卡使用支付宝平台中转，当前尚未取得对应设备入口")
                com.smartlife798.app.core.NfcRoute.Unsupported -> notify("暂不支持这张卡的设备链接")
            }
        }
    }

    private fun publishNfcDevice(device: Device, autoStart: Boolean) {
        rememberScan(device)
        val event = java.util.UUID.randomUUID().toString()
        nfcAutoStart.clear()
        if (autoStart) nfcAutoStart.issue(event, device.id)
        _nfcDevice.value = NfcOpen(event, device.id, event.takeIf { autoStart })
    }

    fun consumeNfcDevice(request: NfcOpen) {
        if (_nfcDevice.value == request) _nfcDevice.value = null
    }

    fun claimNfcAutoStart(ticket: String, deviceId: String): Boolean =
        session.isLoggedIn && appResumed && nfcAutoStart.claim(ticket, deviceId)

    // ------------------------------------------------------------------ auth

    suspend fun requestCaptcha(seed: String, ts: String): ByteArray? = withContext(Dispatchers.IO) {
        Api.captcha(seed, ts)
    }

    suspend fun sendSmsCode(phone: String, captcha: String, seed: Double): Res<Unit> =
        withContext(Dispatchers.IO) { Api.sendSms(phone, captcha, seed) }

    suspend fun login(phone: String, sms: String): Res<LoginData> {
        val loginGeneration = accountGeneration
        return withContext(Dispatchers.IO) {
            val res = Api.login(phone, sms)
            if (res is Res.Ok) {
                val info = res.data?.al
                    ?: return@withContext Res.Fail(-1, "登录响应缺少账号信息，请重试")
                info.let {
                    if (info.token.isNullOrBlank()) {
                        return@withContext Res.Fail(-1, "登录响应缺少令牌")
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (accountGeneration != loginGeneration) {
                            throw CancellationException("Login session changed")
                        }
                        // Invalidate old-account jobs before publishing a replacement
                        // session; saveLogin clears caches unless their uid matches.
                        resetAccountState()
                        session.saveLogin(info, phone)
                        val generation = accountGeneration
                        accountRestoreJob = viewModelScope.launch {
                            val cache = withContext(Dispatchers.IO) { readAccountCache() }
                            if (generation == accountGeneration) publishAccountCache(cache)
                        }
                        viewModelScope.launch {
                            accountRestoreJob?.join()
                            if (generation != accountGeneration) return@launch
                            refreshAll()
                            requestAutoCheckIn()
                        }
                        // Start account-owned initialization before removing the login composition.
                        notify("登录成功")
                        _loggedIn.value = true
                    }
                }
            }
            res
        }
    }

    fun logout() {
        val oldToken = session.token
        session.clear()
        resetAccountState()
        if (oldToken.isNullOrBlank()) return
        viewModelScope.launch {
            // A late response belongs to this captured token and must never
            // clear a newer login established while this request was pending.
            Api.logout(oldToken)
        }
    }

    private fun resetAccountState() {
        accountGeneration += 1
        accountRestoreJob?.cancel()
        accountRestoreJob = null
        recentSaveJob?.cancel()
        transactionJobs.toList().forEach { it.cancel() }
        _developerToolsEnabled.value = Store.developerToolsEnabled()
        meRefreshJob?.cancel()
        meRefreshKey = null
        avatarCache = null
        clearCheckInNotice()
        profileJob?.cancel()
        checkInJob?.cancel()
        checkInDoneDay = null
        checkInServerTime = 0L
        _signedInToday.value = null
        _availableScore.value = null
        _checkInStatus.value = "登录后检查签到"
        startJob?.cancel()
        watchJob?.cancel()
        masterJob?.cancel()
        cancelAccountReads()
        nfcResolveJob?.cancel()
        _nfcDevice.value = null
        nfcAutoStart.clear()
        scannedEntry = null
        detailPage.clear()
        deviceRevision += 1
        profileRevision += 1
        _pendingStart.value = null
        _wallet.value = null
        _loggedIn.value = false
        _profile.value = null
        publishFavorites(emptyList())
        _recents.value = emptyList()
        _pinned.value = emptySet()
        _active.value = null
        _liveStatus.value = null
        _liveUpdatedAt.value = 0L
        _unpaidCount.value = 0
        _payRequest.value = null
        _historySyncedAt.value = 0L
        _masterError.value = null
        profileReadAt = 0L
    }

    // ---------------------------------------------------------------- master

    private var profileReadAt = 0L
    fun ensureProfileFresh() {
        if (_profile.value == null || android.os.SystemClock.elapsedRealtime() - profileReadAt > 60_000L) {
            viewModelScope.launch { refreshMasterInternal() }
        }
    }

    fun refreshMaster(): Job {
        masterJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            refreshMasterInternal()
            refreshBillsDerived()
        }.also { masterJob = it }
    }

    /**
     * Suspending full refresh used by pull-to-refresh and page resumes.
     *
     * The usage record is reconciled here as well: without it a refresh left the
     * 「进行中」 card on screen for as long as the 2 s watch loop needed, so a
     * failed start looked like a running machine until the loop gave up.
     */
    suspend fun refreshAll() = coroutineScope {
        launch { refreshMasterInternal() }
        launch { refreshWallet() }
        launch { refreshBillsDerived() }
        launch { syncActiveStatus() }
        Unit
    }

    /** Account wallet of the enterprise; drives the recharge screen and payment defaults. */
    suspend fun refreshWallet() = accountRead("wallet") {

        if (!session.isLoggedIn) {
            _wallet.value = null
            return@accountRead
        }
        val tokenAtStart = session.token
        val generation = accountGeneration
        val res = withContext(Dispatchers.IO) { Api.wallet() }
        if (generation == accountGeneration && session.token == tokenAtStart && res is Res.Ok)
            _wallet.value = res.data
    }

    /**
     * 支付宝免密签约：`sign-pga?type=20` 返回 `AccountSignData`。
     *
     * 原版 `AgencyDialog.N` → `E(sign)` → `PayHelper.z(258, sign)` → `PayTask.payV2(sign, true)`，
     * 也就是说 **`sign` 才交给支付宝 SDK 打开协议页**；`code` 只是「小荷包」小程序入口
     * （原版仅在积分广告开关打开时才走 `h0(code)`），不能作为主路径。
     *
     * 客户端不判断「已签约」：该状态没有可靠的用户级查询，签约结果以支付宝页面/服务端为准。
     */
    suspend fun signAlipay(activity: android.app.Activity): Res<String> = accountTransaction(Res.Fail(-1, "请先登录或等待当前交易完成")) {
        when (val response = alipaySignPayload()) {
            is Res.Fail -> response
            is Res.Ok -> {
                val payload = response.data.orEmpty()
                if (!appResumed) Res.Fail(-1, "请返回前台后重新打开签约")
                else if (payload.startsWith("alipays://")) {
                    try {
                        activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(payload)))
                        Res.Ok("请在支付宝页面确认签约结果")
                    } catch (_: android.content.ActivityNotFoundException) { Res.Fail(-1, "未安装支付宝，无法完成签约") }
                    catch (_: SecurityException) { Res.Fail(-1, "系统拒绝打开签约页面") }
                } else {
                    Res.Ok(com.smartlife798.app.core.AlipaySdk.sign(activity, payload))
                }
            }
        }
    }

    private suspend fun alipaySignPayload(): Res<String> {
        val res = withContext(Dispatchers.IO) { Api.signPga(AGREEMENT_TYPE_ALIPAY) }
        return when (res) {
            is Res.Ok -> {
                val sign = res.data?.sign?.takeIf { it.isNotBlank() }
                val code = res.data?.code?.takeIf { it.isNotBlank() }
                Diag.event(
                    "sign.pga",
                    "hasSign" to (sign != null),
                    "hasCode" to (code != null),
                    "status" to res.data?.status,
                )
                when {
                    sign != null -> Res.Ok(sign)
                    code != null -> Res.Ok(com.smartlife798.app.core.AlipaySign.link(code))
                    else -> Res.Fail(-1, "服务端未返回签约参数，请稍后重试")
                }
            }
            is Res.Fail -> res
        }
    }

    /** Recharge products (`prd/lst type=1`); the enterprise id comes from the wallet. */
    suspend fun rechargeProducts(): Res<List<Product>> {
        if (_wallet.value?.ep?.id.isNullOrBlank()) refreshWallet()
        val eid = _wallet.value?.ep?.id
            ?: return Res.Fail(-1, "未能读取企业钱包，请下拉刷新后重试")
        return withContext(Dispatchers.IO) { Api.rechargeProducts(eid) }
    }

    /**
     * Creates the recharge bill (`POST /bill/save`) and returns its id.
     *
     * Body mirrors the original `RechargeBillParm`: enterprise + product line +
     * owner/contact user refs, `cata=1` (payment direction), note = product name.
     * The server computes the amount; the client never sends a price.
     */
    private suspend fun <T> accountTransaction(busy: T, run: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        if (!session.isLoggedIn || !transaction.tryLock()) return@withContext busy
        val token = session.token
        val generation = accountGeneration
        val task = viewModelScope.async(start = CoroutineStart.LAZY) {
            val result = run()
            if (token != session.token || generation != accountGeneration) throw CancellationException("Account changed")
            result
        }
        transactionJobs.add(task)
        task.invokeOnCompletion {
            transaction.unlock()
            viewModelScope.launch { transactionJobs.remove(task) }
        }
        task.start()
        task.await()
    }

    suspend fun createRechargeBill(product: Product): Res<String> = accountTransaction(Res.Fail(-1, "请先登录或等待当前交易完成")) {
        createRechargeBillInternal(product)
    }
    private suspend fun createRechargeBillInternal(product: Product): Res<String> {
        if (_wallet.value?.ep?.id.isNullOrBlank()) refreshWallet()
        val wallet = _wallet.value ?: return Res.Fail(-1, "未能读取企业钱包，请下拉刷新后重试")
        val epId = wallet.ep?.id ?: return Res.Fail(-1, "未能读取企业信息，请下拉刷新后重试")
        val ownerId = wallet.owner?.id ?: _profile.value?.id
            ?: return Res.Fail(-1, "登录状态异常，请重新登录")
        val body = RechargeBillBody(
            ep = IdCata(epId, 1),
            prds = listOf(IdCount(product.id, 1)),
            owner = IdCata(ownerId, 1),
            contact = IdCata(ownerId, 1),
            cata = 1,
            note = product.name,
        )
        val res = withContext(Dispatchers.IO) { Api.saveRechargeBill(body) }
        return when (res) {
            is Res.Ok -> res.data?.takeIf { it > 0L }?.let { Res.Ok(it.toString()) }
                ?: Res.Fail(-1, "服务端未返回有效账单，请先查询订单，勿重复充值")
            is Res.Fail -> res
        }
    }

    /**
     * The original app never kept a "recent devices" table locally. The closest
     * cloud source is the owner's bill history (each bill carries the device), so
     * history is derived from `GET /api/v1/bill/lst-owner` and merged with devices
     * that were only scanned (those never reach the server).
     *
     * One page-0 fetch feeds both the history index and the unpaid badge, which
     * used to cost two identical requests on every navigation.
     */
    private suspend fun refreshBillsDerived() = withContext(Dispatchers.Main.immediate) {

        if (!session.isLoggedIn) {
            _unpaidCount.value = 0
            return@withContext
        }
        val bills = fetchRecentBills() ?: return@withContext
        updateUnpaid(bills)
        applyCloudHistory(bills)
    }

    /** Kept for callers that only need the history index. */
    suspend fun syncCloudHistory() = withContext(Dispatchers.Main.immediate) {
        if (!session.isLoggedIn) return@withContext
        fetchRecentBills()?.let(::applyCloudHistory)
    }

    private suspend fun fetchRecentBills(): List<Bill>? = withContext(Dispatchers.Main.immediate) {
        val generation = accountGeneration
        val token = session.token
        if (token.isNullOrBlank()) return@withContext null
        val task = recentBillsRead?.takeIf { it.isActive } ?: viewModelScope.async {
            withContext(Dispatchers.IO) { Api.bills(page = 0, size = HISTORY_PAGE_SIZE) }.let { res ->
                when (res) {
                    is Res.Ok -> res.data.orEmpty()
                    is Res.Fail -> null
                }
            }
        }.also { recentBillsRead = it }
        try {
            task.await().takeIf { token == session.token && generation == accountGeneration }
        } finally {
            if (task.isCompleted && recentBillsRead === task) recentBillsRead = null
        }
    }

    private fun applyCloudHistory(bills: List<Bill>) {
        val grouped = LinkedHashMap<String, RecentDevice>()
        bills.forEach { bill ->
            val dev = bill.dev ?: return@forEach
            if (dev.id.isBlank()) return@forEach
            val existing = grouped[dev.id]
            grouped[dev.id] = RecentDevice(
                id = dev.id,
                name = dev.name.ifBlank { existing?.name ?: "未命名设备" },
                dtype = dev.dtype,
                lastUsedAt = maxOf(bill.ctime, existing?.lastUsedAt ?: 0L),
                uses = (existing?.uses ?: 0) + 1,
                scannedOnly = false,
            )
        }
        val cloud = grouped.values.sortedByDescending { it.lastUsedAt }
        val cloudIds = cloud.map { it.id }.toSet()
        val localOnly = _recents.value.filter { it.id !in cloudIds }
        val merged = (cloud + localOnly).take(60)
        if (_recents.value != merged) {
            saveRecentSnapshot(merged)
        }
        _historySyncedAt.value = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ bills

    private fun isUnpaidBill(bill: Bill): Boolean = bill.isUnpaid

    private fun updateUnpaid(list: List<Bill>) {
        _unpaidCount.value = list.count(::isUnpaidBill)
    }

    /**
     * One lightweight request that keeps the bottom-navigation badge honest.
     * The first page is enough for the badge because it is ordered by ctime;
     * the orders screen still pages through the complete cloud list.
     */
    suspend fun refreshOrderBadge() = withContext(Dispatchers.Main.immediate) {

        if (!session.isLoggedIn) {
            _unpaidCount.value = 0
            return@withContext
        }
        fetchRecentBills()?.let(::updateUnpaid)
    }

    /** Called when the server refuses `dev/start` with an unpaid-bill code. */
    fun requestPaymentForUnpaid(note: String) {
        val pending = _payRequest.value
        if (pending != null && System.currentTimeMillis() - pending.createdAt < PAY_REQUEST_TTL_MS) return
        viewModelScope.launch {
            val unpaid = run {
                withContext(Dispatchers.IO) {
                    when (val res = Api.bills(page = 0, size = HISTORY_PAGE_SIZE)) {
                        is Res.Ok -> res.data.orEmpty().filter(::isUnpaidBill).maxByOrNull { it.ctime }
                        is Res.Fail -> null
                    }
                }
            }
            if (unpaid == null) {
                _message.value = "$note 未查询到待支付账单，请稍后到订单页重试"
                return@launch
            }
            _payRequest.value = PayRequest(unpaid, note)
        }
    }

    fun consumePayRequest() {
        _payRequest.value = null
    }

    // --------------------------------------------------------------- payments

    private suspend fun <T> accountResponse(read: suspend () -> Res<T>): Res<T> {
        accountRestoreJob?.join()
        val token = session.token ?: return Res.Fail(-99, "请先登录")
        val generation = accountGeneration
        val result = read()
        currentCoroutineContext().ensureActive()
        if (token != session.token || generation != accountGeneration) throw CancellationException("Account changed")
        return result
    }

    /** Close once, then require an authoritative cancelled snapshot. Shares the payment lock. */
    suspend fun cancelBill(billId: String): Res<Unit> = accountTransaction(Res.Fail(-1, "请先登录或等待当前交易完成")) {
        withContext(Dispatchers.IO) {
            if (billId.isBlank()) return@withContext Res.Fail(-1, "订单编号为空")
            val before = accountResponse { Api.billDetail(billId) }
            if (before is Res.Fail) return@withContext before
            val bill = (before as Res.Ok).data?.bill
            if (bill?.id != billId || !bill.isUnpaid) return@withContext Res.Fail(-1, "当前订单不可取消，请刷新查看状态")
            val closed = accountResponse { Api.closePay(billId) }
            bumpRefresh()
            if (closed is Res.Fail) return@withContext Res.Fail(closed.code, "取消未确认：${closed.msg}，请刷新订单核对")
            val after = accountResponse { Api.billDetail(billId) }
            val updated = (after as? Res.Ok)?.data?.bill
            if (updated?.id == billId && updated.status == com.smartlife798.app.core.BillStatus.CANCELLED) Res.Ok(Unit)
            else Res.Fail(-1, "取消结果待确认，请刷新订单核对；请勿重复支付")
        }
    }

    suspend fun loadChannels(billId: String): Res<List<PayChannel>> = withContext(Dispatchers.IO) {
        run {
            when (val r = accountResponse { Api.payChannels(billId) }) {
                is Res.Ok -> Res.Ok(r.data?.channels.orEmpty())
                is Res.Fail -> r
            }
        }
    }

    /**
     * Runs the same sequence as the original client:
     * prepay -> (third-party SDK) -> trans/confirm. Never reports success locally.
     */
    suspend fun payBill(
        activity: android.app.Activity,
        billId: String,
        channel: PayChannel,
    ): PaymentFlow.Outcome = accountTransaction(PaymentFlow.Outcome.Unavailable("请先登录或等待当前交易完成")) {
        payBillInternal(activity, billId, channel)
    }
    private suspend fun payBillInternal(activity: android.app.Activity, billId: String, channel: PayChannel): PaymentFlow.Outcome = withContext(Dispatchers.IO) {

        val type = channel.type
        val alipay = type == com.smartlife798.app.core.PayType.ALIPAY
        val wechat = type == com.smartlife798.app.core.PayType.WECHAT
        val walletChannel = com.smartlife798.app.core.PayType.isWallet(type)
        if (!com.smartlife798.app.core.PayType.isSupportedHere(type)) {
            return@withContext PaymentFlow.Outcome.Unavailable(
                "「${PaymentFlow.channelLabel(type, channel.name)}」需要对应的官方支付 SDK，当前客户端未集成",
            )
        }
        Diag.event(
            "pay.begin",
            "billId" to billId,
            "payType" to type,
            "alipayInstalled" to if (alipay) com.smartlife798.app.core.AlipaySdk.isInstalled(activity) else null,
            "wechatInstalled" to if (wechat) com.smartlife798.app.core.WxPay.isInstalled(activity) else null,
        )
        var alipayStatus: String? = null
        if (wechat) {
            // WeChat returns the seven-field prepay object; the SDK result arrives in
            // `wxapi.WXPayEntryActivity`, and only `trans/confirm` decides success.
            val raw = Api.prepayJson(type, billId)
            if (raw is Res.Fail) {
                Diag.event("prepay.fail", "billId" to billId, "payType" to type, "code" to raw.code)
                return@withContext PaymentFlow.Outcome.Failed(raw.msg)
            }
            val order = com.smartlife798.app.core.WxPay.parse((raw as Res.Ok).data)
            if (order == null) {
                Diag.event("wxpay.bad_payload", "billId" to billId)
                return@withContext PaymentFlow.Outcome.Failed("服务端未返回完整的微信支付参数")
            }
            Diag.event("wxpay.begin", "billId" to billId)
            val wx = com.smartlife798.app.core.WxPay.pay(activity, order)
            Diag.event("wxpay.outcome", "billId" to billId, "errCode" to wx.code)
            if (!wx.success) {
                return@withContext PaymentFlow.Outcome.Failed(
                    "微信未完成支付（${wx.code}）${wx.message.orEmpty()}",
                )
            }
        } else {
            val prepay = Api.prepay(type, billId)
            if (prepay is Res.Fail) {
                Diag.event("prepay.fail", "billId" to billId, "payType" to type, "code" to prepay.code)
                return@withContext PaymentFlow.Outcome.Failed(prepay.msg)
            }
            Diag.event("prepay.ok", "billId" to billId, "payType" to type, "orderBytes" to ((prepay as Res.Ok).data?.length ?: 0))
            val orderInfo = (prepay as Res.Ok).data.orEmpty()
            if (alipay) {
                if (orderInfo.isBlank()) {
                    // 已签约「支付宝免密支付」时服务端直接扣款，`prepay` 不返回订单串：
                    // 这里不需要拉起 SDK，直接去 `trans/confirm` 确认服务端结果。
                    Diag.event("pay.free_pass", "billId" to billId)
                } else {
                    val result = com.smartlife798.app.core.AlipaySdk.pay(activity, orderInfo)
                    alipayStatus = result.status
                    Diag.event(
                        "alipay.outcome",
                        "billId" to billId,
                        "success" to result.success,
                        "resultStatus" to result.status,
                    )
                    if (!result.success) {
                        return@withContext PaymentFlow.Outcome.Failed(
                            "支付宝未完成支付（${result.status}）${result.memo}",
                        )
                    }
                }
            } else if (!walletChannel) {
                return@withContext PaymentFlow.Outcome.Unavailable(
                    "「${PaymentFlow.channelLabel(type, channel.name)}」暂不支持",
                )
            }
        }
        when (val confirm = Api.confirmPay(billId)) {
            is Res.Ok -> {
                Diag.event("confirm.ok", "billId" to billId, "payType" to type)
                PaymentFlow.Outcome.Paid(
                    "支付成功" + (alipayStatus?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "") +
                        "，账单已更新",
                )
            }
            is Res.Fail -> {
                Diag.event("confirm.fail", "billId" to billId, "code" to confirm.code)
                PaymentFlow.Outcome.Failed("支付结果待确认：${confirm.msg}")
            }
        }
    }

    private suspend fun refreshMasterInternal() = accountRead("master") {
        if (!session.isLoggedIn) return@accountRead
        _loadingMaster.value = true
        _masterError.value = null
        val tokenAtStart = session.token
        val revisionAtStart = profileRevision
        val favoritesAtStart = favoriteRevision
        val generation = accountGeneration
        try {
            val (res, favoritesJson) = withContext(Dispatchers.IO) {
                val response = Api.master()
                val favorites = (response as? Res.Ok)?.data?.favos.orEmpty()
                response to if (response is Res.Ok) gson.toJson(favorites) else null
            }
            if (session.token != tokenAtStart || generation != accountGeneration) return@accountRead
            when (res) {
                is Res.Ok -> {
                    profileReadAt = android.os.SystemClock.elapsedRealtime()
                    val master: Master? = res.data
                    val p = master?.storedProfile(session.phone)
                    if (p != null && revisionAtStart == profileRevision && !_savingProfile.value) {
                        if (_profile.value != p) {
                            Store.saveProfile(p)
                            _profile.value = p
                        }
                    }
                    val favorites = master?.favos.orEmpty()
                    // A fast empty response must replace stale disk data even before restoration finishes.
                    if (favoritesAtStart == favoriteRevision && !favoriteMutation.isLocked) {
                        val changed = _favorites.value != favorites || favoriteRestoreJob?.isActive == true
                        publishFavorites(favorites)
                        if (changed) favoritesJson?.let(Store::saveFavoritesCache)
                    }
                }
                is Res.Fail -> {
                    _masterError.value = res.msg
                }
            }
        } finally {
            if (generation == accountGeneration) _loadingMaster.value = false
        }
    }

    // ---------------------------------------------------------------- device



    /** Read-only diagnostics stay in the VM, including account/cancellation guards. */
    suspend fun checkConnection(): String = withContext(Dispatchers.IO) {
        val tokenAtStart = session.token
        if (!session.isLoggedIn) return@withContext "请先登录后再测试连通性"
        val result = try {
            Api.master()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext "连通测试失败，请稍后重试"
        }
        if (session.token != tokenAtStart) return@withContext "登录状态已变化，请重试"
        when (result) {
            is Res.Ok -> {
                val kind = if (result.data?.account != null) "已登录数据" else "匿名数据"
                "连通正常（code=0，$kind），收藏 ${result.data?.favos?.size ?: 0} 台"
            }
            is Res.Fail -> "返回 code=${result.code}，请稍后重试"
        }
    }

    /** Presentation-only first frame from memory; one QR seed is cleared with the account. */
    fun deviceEntry(did: String): Device? = deviceEntrySnapshot(
        did, _favorites.value, _recents.value, _active.value, _liveStatus.value, scannedEntry,
    )

    fun deviceAccess(detail: DeviceDetail): com.smartlife798.app.core.DeviceAccess = detail.access(
        session.uid,
        (_active.value ?: _pendingStart.value)?.deviceId,
    )

    suspend fun loadDevice(did: String): Res<DeviceDetail> = withContext(Dispatchers.IO) {
        accountRestoreJob?.join()
        run {
            val token = session.token ?: return@withContext Res.Fail(-99, "请先登录")
            val revision = deviceRevision
            val result = Api.deviceDetail(did)
            checkDeviceSession(token)
            val detail = (result as? Res.Ok)?.data
            if (result is Res.Ok && detail?.device?.id != did) {
                return@withContext Res.Fail(-1, if (detail?.device == null) "设备不存在或已停用" else "设备响应不匹配，请刷新后重试")
            }
            val uid = session.uid
            if (detail?.device?.id == did && !uid.isNullOrBlank() &&
                detail.user?.id?.takeIf { it.isNotBlank() }?.let { it != uid } == true) {
                withContext(Dispatchers.Main.immediate) {
                    checkDeviceSession(token)
                    if (revision == deviceRevision) dropForeignDeviceRecord(did)
                }
            }
            // Full detail state may lag command/status reads. Only an explicit
            // foreign owner invalidates a bookmark here; standby alone never does.
            result
        }
    }

    /** An explicit foreign owner invalidates only our bookmark, never sends dev/end. */
    private fun dropForeignDeviceRecord(id: String) {
        var removed = false
        if (_active.value?.deviceId == id) {
            Store.setActive(null)
            _active.value = null
            removed = true
        }
        if (_pendingStart.value?.deviceId == id) {
            Store.setPendingStart(null)
            _pendingStart.value = null
            removed = true
        }
        if (removed) {
            deviceRevision += 1
            ensureWatch()
            notify("设备正由他人使用，本机旧使用记录已清除；账单以服务端为准")
        }
    }

    /**
     * UI pages are 1-based; the production endpoint is 0-based. Converting here
     * fixes the "已付款账单明明存在但消费记录为空" symptom.
     */
    suspend fun bills(page: Int, size: Int = Paging.PAGE_SIZE): Res<List<Bill>> = withContext(Dispatchers.IO) {

        val token = session.token ?: return@withContext Res.Fail(-99, "请先登录")
        val generation = accountGeneration
        val res = Api.bills(page = Paging.toApiPage(page), size = size)
        withContext(Dispatchers.Main.immediate) {
            if (session.token != token || accountGeneration != generation) throw CancellationException("Account changed")
            if (res is Res.Ok && page <= 1) updateUnpaid(res.data.orEmpty())
        }
        if (res is Res.Ok && page <= 1) {
            val list = res.data.orEmpty()
            Diag.event(
                "bills.page",
                "page" to page,
                "count" to list.size,
                "unpaid" to list.count(::isUnpaidBill),
                "statuses" to list.joinToString(",") { it.status.toString() },
            )
        }
        res
    }

    suspend fun billDetail(id: String): Res<BillDetail> = withContext(Dispatchers.IO) {
        accountResponse { Api.billDetail(id) }
    }

    /** Resolves a scanned QR payload through the original service. */
    suspend fun resolveQr(id: String): Res<QrData> = withContext(Dispatchers.IO) {
        accountResponse { Api.qrUse(id) }
    }

    /**
     * One `dev/status` read for the detail page.
     *
     * The response only carries state ([com.smartlife798.app.core.DeviceStatus]).
     * [base] is the device already on screen; the returned device keeps its
     * `bm`/`name`/`ep` and only takes status/gene/subs/ext from the server.
     */
    suspend fun pollDevice(did: String, base: Device): Res<Device> = withContext(Dispatchers.IO) {

        val token = session.token
        val revision = deviceRevision
        when (val res = Api.deviceStatus(did, false)) {
            is Res.Ok -> {
                checkDeviceSession(token)
                val merged = base.mergeStatus(res.data?.device)
                if (revision == deviceRevision && !deviceCommand.isLocked) {
                    reconcileDevice(merged, StartWatch.classify(base, res.data?.device), token, revision)
                }
                Res.Ok(merged)
            }
            is Res.Fail -> res
        }
    }

    suspend fun toggleFavorite(device: Device): Res<Unit> = withContext(Dispatchers.Main.immediate) {
        accountRestoreJob?.join()
        favoriteRestoreJob?.join()
        val token = session.token
        val generation = accountGeneration
        if (token.isNullOrBlank()) return@withContext Res.Fail(-99, "请先登录")
        if (!favoriteMutation.tryLock()) return@withContext Res.Fail(-1, "收藏操作处理中，请稍候")
        favoriteRevision += 1
        try {
            val isFav = _favorites.value.any { it.id == device.id }
            val res = withContext(Dispatchers.IO) { Api.favorite(device.id, if (isFav) 1 else 0) }
            if (session.token != token || accountGeneration != generation) throw CancellationException("Account changed")
            if (res is Res.Ok) {
                val list = _favorites.value.toMutableList()
                if (isFav) list.removeAll { it.id == device.id }
                else if (list.none { it.id == device.id }) list.add(0, device)
                publishFavorites(list)
                withContext(Dispatchers.IO) {
                    val json = gson.toJson(list)
                    val job = currentCoroutineContext()
                    Store.saveFavoritesForAccount(json, token) {
                        job.isActive && accountGeneration == generation && _favorites.value == list
                    }
                }
            }
            res
        } finally {
            favoriteRevision += 1
            favoriteMutation.unlock()
        }
    }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun togglePinned(id: String) {
        val now = !_pinned.value.contains(id)
        Store.setPinned(id, now)
        _pinned.value = Store.pinned()
    }

    private fun touchRecent(device: Device, used: Boolean) {
        val old = _recents.value.firstOrNull { it.id == device.id }
        val row = RecentDevice(device.id, device.name.ifBlank { old?.name ?: "未命名设备" }, device.dtype,
            System.currentTimeMillis(), (old?.uses ?: 0) + if (used) 1 else 0,
            if (used) false else old?.scannedOnly ?: true)
        saveRecentSnapshot((listOf(row) + _recents.value.filterNot { it.id == device.id }).take(60))
    }
    private fun saveRecentSnapshot(rows: List<RecentDevice>) {
        _recents.value = rows
        val token = session.token
        recentSaveJob?.cancel()
        recentSaveJob = viewModelScope.launch(Dispatchers.IO) {
            val job = currentCoroutineContext()
            Store.saveRecentsForAccount(rows, token) { job.isActive && _recents.value == rows }
        }
    }
    fun rememberScan(device: Device) {
        scannedEntry = device
        touchRecent(device, used = false)
    }

    // One foreground watch for this install's confirmed usage or unresolved start.
    // The detail page yields no duplicate reads, and command confirmation owns its reads.
    fun setAppResumed(value: Boolean, allowAutoCheckIn: Boolean = true) {
        if (value && !appResumed) foregroundEpoch += 1
        if (!value) {
            meRefreshJob?.cancel()
            nfcResolveJob?.cancel()
            _nfcDevice.value = null
            nfcAutoStart.clear()
        }
        appResumed = value
        if (value && allowAutoCheckIn) requestAutoCheckIn() else checkInJob?.cancel()
        ensureWatch()
    }

    /** 设备详情页自己有 2s 轮询，这里让位，避免同一设备双倍请求。 */
    fun setDetailDeviceOpen(id: String, owner: Any) {
        detailPage.enter(owner, id)
        ensureWatch()
    }

    fun releaseDetailDevice(owner: Any) {
        if (detailPage.leave(owner)) ensureWatch()
    }

    private fun ensureWatch() {
        val usage = _active.value ?: _pendingStart.value
        if (usage == null || !appResumed || detailPage.deviceId == usage.deviceId ||
            deviceCommand.isLocked
        ) {
            watchJob?.cancel()
            watchJob = null
            return
        }
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            var failures = 0
            while (isActive && (_active.value != null || _pendingStart.value != null)) {
                failures = if (syncActiveStatus()) 0 else (failures + 1).coerceAtMost(4)
                delay((2_000L shl failures).coerceAtMost(30_000L))
            }
        }
    }

    /** A public device state alone cannot establish that this account started it. */
    private suspend fun reconcileDevice(
        device: Device, signal: StartSignal, token: String?, revision: Long? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        checkDeviceSession(token)
        if (revision != null && (revision != deviceRevision || deviceCommand.isLocked)) return@withContext
        val usage = _active.value ?: _pendingStart.value ?: return@withContext
        if (usage.deviceId != device.id || signal == StartSignal.UNKNOWN) return@withContext
        publishLive(device)
        when (signal) {
            StartSignal.STARTED -> if (_active.value == null) {
                Store.setActive(usage)
                _active.value = usage
                Store.setPendingStart(null)
                _pendingStart.value = null
                rememberUse(device)
            }
            StartSignal.STANDBY, StartSignal.ABNORMAL -> clearActiveByServer(device, device.isStandby)
            StartSignal.UNKNOWN -> Unit
        }
    }

    /**
     * Publishes the latest server snapshot and keeps the matching favourite tile
     * in sync, so Home does not keep showing 「启动中」 for a device that already
     * returned to standby (the list is otherwise only refreshed by `master`).
     */
    private fun publishLive(device: Device) {
        _liveStatus.value = device
        _liveUpdatedAt.value = System.currentTimeMillis()
        val favorites = _favorites.value
        if (favorites.any { it.id == device.id }) {
            val state = device.statusOnly()
            publishFavorites(favorites.map {
                if (it.id == device.id) it.mergeStatus(state) else it
            })
        }
    }

    /**
     * The device a `dev/status` snapshot is merged onto.
     *
     * Prefers the last published snapshot; otherwise rebuilds from the saved
     * bookmark. A bookmark written by an older build may not carry `dtype`, in
     * which case the full record is loaded once — classifying with dtype 0 would
     * read `gene.status = 1` as 「在线」 and end the session prematurely.
     */
    private suspend fun baseDeviceFor(id: String, name: String, dtype: Int): Device {
        _liveStatus.value?.takeIf { it.id == id }?.let { return it }
        if (dtype != 0) {
            return Device(id = id, name = name, bm = com.smartlife798.app.core.DeviceModel(dtype = dtype))
        }
        val full = (withContext(Dispatchers.IO) { Api.deviceDetail(id) } as? Res.Ok)?.data?.device
        return full ?: Device(id = id, name = name)
    }

    private fun clearActiveByServer(device: Device, ended: Boolean) {
        if ((_active.value ?: _pendingStart.value)?.deviceId != device.id) return
        Store.setPendingStart(null)
        _pendingStart.value = null
        Store.setActive(null)
        _active.value = null
        publishLive(device)
        val label = device.stateLabel().label
        // Neither branch claims a settlement: the machine may have ended the
        // session, or the start may never have taken effect, and only the server
        // knows which. Do not say 「已结束」 for a fault/offline transition either.
        notify(
            if (ended) "设备当前为「$label」，本机「进行中」记录已清除；账单以服务端为准"
            else "设备$label，已停止状态同步；账单以服务端为准",
        )
    }

    fun rememberUse(device: Device) {
        touchRecent(device, used = true)
    }

    fun clearRecent(id: String) {
        saveRecentSnapshot(_recents.value.filterNot { it.id == id })
    }

    fun clearAllRecents() {
        saveRecentSnapshot(emptyList())
    }

    /** Confirmation survives navigation; account reset or ViewModel disposal cancels it. */
    suspend fun startDevice(device: Device, payType: Int, args: String): Res<Unit> = withContext(Dispatchers.Main.immediate) {
        accountRestoreJob?.join()
        if (!deviceCommand.tryLock()) return@withContext Res.Fail(-1, "设备操作处理中，请勿重复操作")
        val task = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                deviceRevision += 1
                watchJob?.cancel()
                startDeviceInternal(device, payType, args)
            } finally {
                deviceCommand.unlock()
                ensureWatch()
            }
        }
        startJob = task
        task.await()
    }

    private suspend fun startDeviceInternal(device: Device, payType: Int, args: String): Res<Unit> = withContext(Dispatchers.Main.immediate) {
        if (_savingProfile.value) return@withContext Res.Fail(-1, "账号设置正在保存，请完成后再使用设备")

        // Defence in depth: the detail screen disables the button for these
        // states, but a stale recomposition must never queue a command on a
        // machine that cannot run (and must not start a polling loop for it).
        if (device.isAbnormal) {
            return@withContext Res.Fail(-1, "设备当前不可用（${device.stateLabel().label}），未发送启动请求")
        }
        if (device.isInUse) {
            return@withContext Res.Fail(-1, "设备已在使用中，请勿重复开始")
        }
        // This install keeps a single session bookmark; starting another machine
        // would silently orphan the still-running one (it would keep billing with
        // no card left to stop it).
        if (!session.isLoggedIn) return@withContext Res.Fail(ErrorCodes.TOKEN_EXPIRED, "请先登录")
        if (_pendingStart.value != null) return@withContext Res.Fail(-1, "上次启动结果待确认，请先刷新首页设备状态")
        val token = session.token
        val running = _active.value
        if (running != null) {
            val message = if (blockedByOtherSession(running.deviceId, device.id)) {
                "「${running.name}」正在使用中，请先在首页结束它再开始新设备"
            } else "本设备已有进行中记录，请先刷新状态，勿重复启动"
            return@withContext Res.Fail(-1, message)
        }
        // Details may have remained open while another account acquired the device.
        // Recheck ownership under the command mutex, before any billable request.
        when (val checked = checkedDeviceAccess(device.id)) {
            is Res.Fail -> return@withContext checked
            is Res.Ok -> if (checked.data?.access(session.uid, null)?.action != com.smartlife798.app.core.PrimaryAction.START) {
                return@withContext Res.Fail(ErrorCodes.DEVICE_ACCESS_CHANGED, "设备当前已在使用或不可用，请刷新设备状态")
            }
        }
        checkDeviceSession(token)
        val accepted = Api.deviceStart(device.id, payType, args, count = 1)
        checkDeviceSession(token)
        if (accepted is Res.Fail) {
            Diag.event(
                "dev.start.fail",
                "did" to device.id,
                "ptype" to payType,
                "code" to accepted.code,
            )
            return@withContext accepted
        }
        // `code >= 0` only means the server queued the command. The machine has
        // to report that it left standby before this client may call it running.
        val serverNote = (accepted as Res.Ok<String>).data.orEmpty()
        Diag.event(
            "dev.start.accepted",
            "did" to device.id,
            "ptype" to payType,
            "dtype" to device.dtype,
            "hasData" to serverNote.isNotBlank(),
        )
        // Water / washing style machines can prove the start through the state
        // machine, so their session is recorded only after `dev/status` leaves
        // standby. Families whose start is not observable (POS, lockers…) keep the
        // accepted code as their only evidence, as before.
        if (!StartWatch.isObservable(device)) {
            Diag.event("dev.start.accepted_unobserved", "did" to device.id, "dtype" to device.dtype)
            val usage = ActiveUsage(device.id, device.name, device.dtype, System.currentTimeMillis(), payType)
            Store.setActive(usage)
            _active.value = usage
            rememberUse(device)
            return@withContext Res.Ok(Unit)
        }
        val pending = ActiveUsage(device.id, device.name, device.dtype, System.currentTimeMillis(), payType)
        Store.setPendingStart(pending)
        _pendingStart.value = pending
        val signal = kotlinx.coroutines.withTimeoutOrNull(20_000L) { awaitStartSignal(device, token) } ?: StartSignal.UNKNOWN
        val snapshot = _liveStatus.value?.takeIf { it.id == device.id }
        val label = snapshot?.stateLabel()?.label ?: "未知"
        if (signal == StartSignal.STARTED) {
            Diag.event("dev.start.confirmed", "did" to device.id, "state" to label)
            reconcileDevice(snapshot ?: device, signal, token)
            return@withContext Res.Ok(Unit)
        }
        Diag.event(
            "dev.start.unconfirmed",
            "did" to device.id,
            "signal" to signal.name,
            "state" to label,
            "geneStatus" to (snapshot?.geneStatus ?: -1),
            "hasData" to serverNote.isNotBlank(),
        )
        if (signal == StartSignal.STANDBY || signal == StartSignal.ABNORMAL) {
            snapshot?.let { reconcileDevice(it, signal, token) }
        }
        // The server may still have opened a session that will be settled even
        // though the machine stayed idle, so surface a new bill on the badge.
        // Cancellation must propagate: swallowing it would keep this coroutine
        // alive after the screen that started it is gone.
        try {
            refreshOrderBadge()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Badge refresh is best-effort; the start verdict is already decided.
        }
        Res.Fail(ErrorCodes.START_UNCONFIRMED, startFailureText(signal, label, serverNote))
    }

    /**
     * Reads `dev/status` until the device leaves standby, reports a terminal
     * state, or [StartWatch] runs out of attempts. Every snapshot is published so
     * the detail page and the Home card show what the server actually says.
     */
    private suspend fun awaitStartSignal(device: Device, token: String?): StartSignal {
        var signal = StartSignal.UNKNOWN
        var failures = 0
        delay(StartWatch.FIRST_DELAY_MS)
        repeat(StartWatch.ATTEMPTS) { attempt ->
            if (attempt > 0) delay(StartWatch.INTERVAL_MS)
            val snapshot = when (val res = Api.deviceStatus(device.id, false)) {
                is Res.Ok -> res.data?.device
                is Res.Fail -> {
                    failures += 1
                    null
                }
            }
            checkDeviceSession(token)
            if (snapshot != null && snapshot.id == device.id) {
                failures = 0
                // `dev/status` is a partial DeviceStatus: merge it onto the
                // started device, otherwise dtype 0 turns `gene.status = 1`
                // into 「在线」 and a running machine looks idle.
                val merged = device.mergeStatus(snapshot)
                publishLive(merged)
                signal = StartWatch.classify(device, snapshot)
                if (signal != StartSignal.UNKNOWN && signal != StartSignal.STANDBY) return signal
            } else {
                signal = StartSignal.UNKNOWN
                if (failures >= 2) return signal
            }
        }
        return signal
    }

    /**
     * Explains an accepted-but-unconfirmed start.
     *
     * [serverNote] is whatever `dev/start` put in `data`; the command endpoints
     * are not documented, so quoting it is the only way to tell the user (and the
     * next debugging round) what the server actually answered.
     */
    private fun startFailureText(signal: StartSignal, label: String, serverNote: String): String {
        val note = serverNote.trim()
            .takeIf { it.isNotEmpty() && it != "null" }
            ?.let { " 服务端返回：${it.take(120)}" }
            .orEmpty()
        return when (signal) {
            StartSignal.STANDBY ->
                "服务端已受理启动，但设备在 ${StartWatch.ceilingSeconds} 秒内一直是「$label」，" +
                    "本次启动未生效。请确认设备通电/联网后重试；若服务端已生成订单，可在「订单」页查看。$note"
            StartSignal.ABNORMAL ->
                "服务端已受理启动，但设备回报「$label」，本次启动未生效。账单以服务端为准。$note"
            else ->
                "服务端已受理启动，但暂时读不到设备状态。请下拉刷新设备页确认是否已启动，" +
                    "首页会保留「启动待确认」查询入口，请勿重复启动；账单以服务端为准。$note"
        }
    }

    /**
     * Reconciles the locally remembered session with the server.
     *
     * The record is only a bookmark for this install. Called on every refresh,
     * on resume and once at startup so an ended session — or a start that never
     * took effect — cannot leave a 「使用中」 card behind.
     */
    suspend fun syncActiveStatus(): Boolean {
        val usage = _active.value ?: _pendingStart.value ?: return false
        if (!session.isLoggedIn || deviceCommand.isLocked) return false
        val token = session.token
        val revision = deviceRevision
        val res = withContext(Dispatchers.IO) { Api.deviceStatus(usage.deviceId, false) }
        checkDeviceSession(token)
        val fresh = (res as? Res.Ok)?.data?.device ?: return false
        val base = baseDeviceFor(usage.deviceId, usage.name, usage.dtype)
        checkDeviceSession(token)
        if (revision != deviceRevision || deviceCommand.isLocked ||
            (_active.value ?: _pendingStart.value) != usage
        ) return false
        reconcileDevice(base.mergeStatus(fresh), StartWatch.classify(base, fresh), token, revision)
        return true
    }

    /**
     * Ends the usage recorded on this install without requiring the detail
     * screen. Only a matching standby snapshot confirms completion.
     */
    suspend fun stopActiveDevice(): Res<Device> {
        val usage = _active.value ?: _pendingStart.value ?: return Res.Fail(-1, "当前没有进行中的使用")
        val live = _liveStatus.value?.takeIf { it.id == usage.deviceId }
        val device = live ?: Device(
            id = usage.deviceId,
            name = usage.name,
            bm = com.smartlife798.app.core.DeviceModel(dtype = usage.dtype),
        )
        return stopDevice(device)
    }

    suspend fun stopDevice(device: Device): Res<Device> = withContext(Dispatchers.Main.immediate) {
        accountRestoreJob?.join()
        if (!deviceCommand.tryLock()) return@withContext Res.Fail(-1, "设备操作处理中，请勿重复操作")
        try {
            deviceRevision += 1
            stopDeviceInternal(device)
        } finally {
            deviceCommand.unlock()
            ensureWatch()
        }
    }

    private suspend fun checkedDeviceAccess(id: String): Res<DeviceDetail> {
        val token = session.token
        val result = withContext(Dispatchers.IO) { Api.deviceDetail(id) }
        checkDeviceSession(token)
        if (result is Res.Fail) return result
        val detail = (result as Res.Ok).data
        if (detail?.device?.id != id || detail.device.gene == null) {
            return Res.Fail(ErrorCodes.DEVICE_ACCESS_CHANGED, "未能确认设备状态，请刷新后重试")
        }
        val access = detail.access(session.uid, (_active.value ?: _pendingStart.value)?.deviceId)
        if (!session.uid.isNullOrBlank() && detail.user?.id?.takeIf { it.isNotBlank() }?.let { it != session.uid } == true)
            dropForeignDeviceRecord(id)
        return if (access.notice != null) Res.Fail(ErrorCodes.DEVICE_ACCESS_CHANGED, access.notice) else Res.Ok(detail)
    }

    private suspend fun stopDeviceInternal(device: Device): Res<Device> = withContext(Dispatchers.Main.immediate) {

        if (!session.isLoggedIn) return@withContext Res.Fail(ErrorCodes.TOKEN_EXPIRED, "请先登录")
        val token = session.token
        when (val checked = checkedDeviceAccess(device.id)) {
            is Res.Fail -> return@withContext checked
            is Res.Ok -> if (checked.data?.access(session.uid, (_active.value ?: _pendingStart.value)?.deviceId)?.action != com.smartlife798.app.core.PrimaryAction.STOP) {
                return@withContext Res.Fail(ErrorCodes.DEVICE_ACCESS_CHANGED, "当前没有可结束的本账号使用，请刷新设备状态")
            }
        }
        checkDeviceSession(token)
        watchJob?.cancel()
        val res = endCommand.execute(
            device = device,
            send = {
                checkDeviceSession(token)
                Api.deviceEnd(device.id).also {
                    checkDeviceSession(token)
                    if (it is Res.Ok) Diag.event("dev.end.accepted", "did" to device.id)
                }
            },
            read = {
                checkDeviceSession(token)
                when (val status = Api.deviceStatus(device.id, false)) {
                    is Res.Ok -> Res.Ok(status.data?.device)
                    is Res.Fail -> status
                }.also { checkDeviceSession(token) }
            },
            observed = {
                checkDeviceSession(token)
                publishLive(it)
            },
        )
        checkDeviceSession(token)
        when (res) {
            is Res.Ok -> {
                Diag.event("dev.end.ok", "did" to device.id)
                // Only drop the bookmark for the device that was actually ended:
                // ending device B must not orphan a running session on A.
                if (activeBelongsTo(_pendingStart.value?.deviceId, device.id)) {
                    Store.setPendingStart(null)
                    _pendingStart.value = null
                }
                if (activeBelongsTo(_active.value?.deviceId, device.id)) {
                    Store.setActive(null)
                    _active.value = null
                }
                // A badge failure must not turn confirmed completion into failure.
                viewModelScope.launch { if (session.token == token) refreshOrderBadge() }
            }
            is Res.Fail -> Diag.event(
                "dev.end.fail",
                "did" to device.id,
                "code" to res.code,
            )
        }
        res
    }

    suspend fun changeMode(device: Device, mode: Int, args: String): Res<Unit> = withContext(Dispatchers.IO) {
        Api.setMode(device.id, mode, args)
    }

    fun errorText(code: Int, msg: String): String = when (code) {
        ErrorCodes.NETWORK -> "网络不可用，请检查网络连接"
        ErrorCodes.INSUFFICIENT_BALANCE -> "账户余额不足：$msg"
        ErrorCodes.PAYMENT_REQUIRED -> "需要完成支付后才能使用：$msg"
        ErrorCodes.UNPAID_BILL -> "存在待付款账单：$msg"
        ErrorCodes.TOKEN_EXPIRED -> "登录状态已过期，请重新登录"
        ErrorCodes.DEVICE_RUNNING -> "设备正在运行，请在设备上按「结算」键结束；App 会继续同步状态"
        else -> msg
    }

    private companion object {
        /** Shared by automatic/manual paths and overlapping activity ViewModels. */
        val CHECK_IN_COMMAND = Mutex()

        /** Coalescing window for [bumpRefresh]; shorter than a screen transition. */
        const val REFRESH_DEBOUNCE_MS = 200L

        /** A stale "go pay" request must not pop up again minutes later. */
        const val PAY_REQUEST_TTL_MS = 5 * 60 * 1000L

        /** Page-0 fetch that feeds the history index and the unpaid badge. */
        const val HISTORY_PAGE_SIZE = 50

        /** `PGAgreementType.ALIPAY`（协议类型 20，不是支付渠道 21）。 */
        const val AGREEMENT_TYPE_ALIPAY = 20
    }
}
