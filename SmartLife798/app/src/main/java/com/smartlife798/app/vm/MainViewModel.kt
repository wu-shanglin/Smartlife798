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
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.QrData
import com.smartlife798.app.core.RecentDevice
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.StartSignal
import com.smartlife798.app.core.StartWatch
import com.smartlife798.app.core.StoredProfile
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.activeBelongsTo
import com.smartlife798.app.core.blockedByOtherSession
import com.smartlife798.app.core.isAbnormal
import com.smartlife798.app.core.isInUse
import com.smartlife798.app.core.isStandby
import com.smartlife798.app.core.isUnpaid
import com.smartlife798.app.core.mergeStatus
import com.smartlife798.app.core.statusOnly
import com.smartlife798.app.core.session
import com.smartlife798.app.core.stateLabel
import kotlinx.coroutines.CancellationException
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

    private val gson = Gson()

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
        if (Preview.enabled || protocolJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && lastProtocolCheck != 0L && now - lastProtocolCheck < protocolCheckInterval) return
        lastProtocolCheck = now
        protocolCheckInterval = 300_000L
        protocolJob = viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { com.smartlife798.app.core.OriginalAppVersion.fetch() }
                if (Preview.enabled) return@launch
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
        if (Preview.enabled || downloadJob?.isActive == true) return
        val policy = _updatePolicy.value ?: return
        if (!policy.available(BuildConfig.VERSION_CODE)) return
        downloadJob = viewModelScope.launch {
            _updateDownload.value = UpdateDownload(busy = true)
            try {
                val file = com.smartlife798.app.core.UpdatePackage.download(getApplication(), policy) { percent ->
                    _updateDownload.value = UpdateDownload(busy = true, percent = percent)
                }
                if (!Preview.enabled && _updatePolicy.value == policy) {
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
        if (Preview.enabled) return null
        val policy = _updatePolicy.value ?: return null
        val file = _updateDownload.value.ready ?: return null
        return try {
            withContext(Dispatchers.IO) { com.smartlife798.app.core.UpdatePackage.verify(getApplication(), file, policy) }
            if (Preview.enabled || policy != _updatePolicy.value) null
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
        if (Preview.enabled) {
            if (manual) notify("演示模式下不检查更新")
            return
        }
        refreshProtocolVersion(manual)
        if (updateJob?.isActive == true || downloadJob?.isActive == true) return
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
                val fetched = withContext(Dispatchers.IO) { com.smartlife798.app.core.AppUpdate.fetch() }
                if (Preview.enabled) return@launch
                val json = com.smartlife798.app.core.AppUpdate.retainHistory(fetched, Store.updatePolicyJson)
                val policy = com.smartlife798.app.core.UpdatePolicy.parse(json)
                if (policy != _updatePolicy.value) _updateDownload.value = UpdateDownload()
                Store.updatePolicyJson = json
                _updatePolicy.value = policy
                _showUpdate.value = policy.available(BuildConfig.VERSION_CODE) &&
                    (manual || dismissedUpdateCode != policy.versionCode)
                if (manual && !policy.available(BuildConfig.VERSION_CODE)) notify("当前已是最新版本")
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

    private val _profile = MutableStateFlow(Store.profile())
    val profile: StateFlow<StoredProfile?> = _profile.asStateFlow()

    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()

    private val _favorites = MutableStateFlow<List<Device>>(emptyList())
    val favorites: StateFlow<List<Device>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentDevice>>(Store.recents())
    val recents: StateFlow<List<RecentDevice>> = _recents.asStateFlow()

    private val _pinned = MutableStateFlow<Set<String>>(Store.pinned())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _active = MutableStateFlow(Store.active())
    val active: StateFlow<ActiveUsage?> = _active.asStateFlow()

    private val _pendingStart: MutableStateFlow<ActiveUsage?> = MutableStateFlow(Store.pendingStart())
    val pendingStart: StateFlow<ActiveUsage?> = _pendingStart.asStateFlow()
    private val deviceCommand = Mutex()
    private var startJob: Job? = null
    private var deviceRevision = 0L

    private fun checkDeviceSession(token: String?) {
        if (Preview.enabled || !session.isLoggedIn || session.token != token) {
            throw CancellationException("Device session changed")
        }
    }

    private val _liveStatus = MutableStateFlow<Device?>(null)
    val liveStatus: StateFlow<Device?> = _liveStatus.asStateFlow()

    private val _liveUpdatedAt = MutableStateFlow(0L)
    val liveUpdatedAt: StateFlow<Long> = _liveUpdatedAt.asStateFlow()

    @Volatile private var appResumed = true
    @Volatile private var detailDevice: String? = null
    private var watchJob: Job? = null
    private var masterJob: Job? = null
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

    init {
        // Restore the cached favorite list so the UI has content while the server responds.
        runCatching {
            Store.favoritesCacheJson()?.let {
                val cached: List<Device> = gson.fromJson(it, object : TypeToken<List<Device>>() {}.type)
                _favorites.value = cached
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
                if (count > 0) {
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

    // ------------------------------------------------------------------ auth

    suspend fun requestCaptcha(seed: String, ts: String): ByteArray? = withContext(Dispatchers.IO) {
        Api.captcha(seed, ts)
    }

    suspend fun sendSmsCode(phone: String, captcha: String, seed: Double): Res<Unit> =
        withContext(Dispatchers.IO) { Api.sendSms(phone, captcha, seed) }

    suspend fun login(phone: String, sms: String): Res<LoginData> = withContext(Dispatchers.IO) {
        val res = Api.login(phone, sms)
        if (res is Res.Ok) {
            res.data?.al?.let { info ->
                if (info.token.isNullOrBlank()) {
                    return@withContext Res.Fail(-1, "登录响应缺少令牌")
                }
                session.saveLogin(info, phone)
                _loggedIn.value = true
                refreshMasterInternal()
                refreshBillsDerived()
            }
        }
        res
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.logout() } }
            Preview.enabled = false
            // session.clear() drops the account-scoped store keys; appearance
            // preferences are intentionally kept.
            session.clear()
            resetAccountState()
        }
    }

    private fun resetAccountState() {
        startJob?.cancel()
        watchJob?.cancel()
        masterJob?.cancel()
        deviceRevision += 1
        _pendingStart.value = null
        _wallet.value = null
        _loggedIn.value = false
        _profile.value = null
        _favorites.value = emptyList()
        _recents.value = emptyList()
        _pinned.value = emptySet()
        _active.value = null
        _liveStatus.value = null
        _liveUpdatedAt.value = 0L
        _unpaidCount.value = 0
        _payRequest.value = null
        _historySyncedAt.value = 0L
        _masterError.value = null
    }

    // ---------------------------------------------------------------- master

    fun refreshMaster() {
        if (masterJob?.isActive == true) return
        masterJob = viewModelScope.launch {
            refreshMasterInternal()
            refreshBillsDerived()
        }
    }

    /**
     * Suspending full refresh used by pull-to-refresh and page resumes.
     *
     * The usage record is reconciled here as well: without it a refresh left the
     * 「进行中」 card on screen for as long as the 2 s watch loop needed, so a
     * failed start looked like a running machine until the loop gave up.
     */
    suspend fun refreshAll() {
        refreshMasterInternal()
        refreshWallet()
        refreshBillsDerived()
        syncActiveStatus()
    }

    /** Account wallet of the enterprise; drives the recharge screen and payment defaults. */
    suspend fun refreshWallet() {
        if (Preview.enabled) {
            _wallet.value = Preview.wallet()
            return
        }
        if (!session.isLoggedIn) {
            _wallet.value = null
            return
        }
        val tokenAtStart = session.token
        val res = withContext(Dispatchers.IO) { Api.wallet() }
        if (!Preview.enabled && session.token == tokenAtStart && res is Res.Ok) _wallet.value = res.data
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
    suspend fun alipaySignPayload(): Res<String> {
        if (Preview.enabled) return Res.Fail(-1, "演示模式下不执行签约")
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
        if (Preview.enabled) return Res.Ok(Preview.rechargeProducts())
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
    suspend fun createRechargeBill(product: Product): Res<String> {
        if (Preview.enabled) return Res.Ok("preview-recharge-bill")
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
            is Res.Ok -> Res.Ok(res.data.toString())
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
    private suspend fun refreshBillsDerived() {
        if (Preview.enabled) {
            _unpaidCount.value = Preview.bills().count(::isUnpaidBill)
            return
        }
        if (!session.isLoggedIn) {
            _unpaidCount.value = 0
            return
        }
        val bills = fetchRecentBills() ?: return
        updateUnpaid(bills)
        applyCloudHistory(bills)
    }

    /** Kept for callers that only need the history index. */
    suspend fun syncCloudHistory() {
        if (Preview.enabled || !session.isLoggedIn) return
        fetchRecentBills()?.let(::applyCloudHistory)
    }

    private suspend fun fetchRecentBills(): List<Bill>? =
        withContext(Dispatchers.IO) { Api.bills(page = 0, size = HISTORY_PAGE_SIZE) }.let { res ->
            when (res) {
                is Res.Ok -> res.data.orEmpty()
                is Res.Fail -> null
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
        val localOnly = Store.recents().filter { it.id !in cloudIds }
        val merged = (cloud + localOnly).take(60)
        Store.saveRecents(merged)
        _recents.value = merged
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
    suspend fun refreshOrderBadge() {
        if (Preview.enabled) {
            _unpaidCount.value = Preview.bills().count(::isUnpaidBill)
            return
        }
        if (!session.isLoggedIn) {
            _unpaidCount.value = 0
            return
        }
        fetchRecentBills()?.let(::updateUnpaid)
    }

    /** Called when the server refuses `dev/start` with an unpaid-bill code. */
    fun requestPaymentForUnpaid(note: String) {
        val pending = _payRequest.value
        if (pending != null && System.currentTimeMillis() - pending.createdAt < PAY_REQUEST_TTL_MS) return
        viewModelScope.launch {
            val unpaid = if (Preview.enabled) {
                Preview.bills().filter(::isUnpaidBill).maxByOrNull { it.ctime }
            } else {
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

    suspend fun loadChannels(billId: String): Res<List<PayChannel>> = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            Res.Ok(
                listOf(
                    PayChannel(21, "支付宝", "需真实账号验证"),
                    PayChannel(92, "账户余额", "服务端扣款"),
                ),
            )
        } else {
            when (val r = Api.payChannels(billId)) {
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
    ): PaymentFlow.Outcome = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            return@withContext PaymentFlow.Outcome.Unavailable("演示模式下不执行任何支付操作")
        }
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
                Diag.event("prepay.fail", "billId" to billId, "payType" to type, "code" to raw.code, "msg" to raw.msg)
                return@withContext PaymentFlow.Outcome.Failed(raw.msg)
            }
            val order = com.smartlife798.app.core.WxPay.parse((raw as Res.Ok).data)
            if (order == null) {
                Diag.event("wxpay.bad_payload", "billId" to billId)
                return@withContext PaymentFlow.Outcome.Failed("服务端未返回完整的微信支付参数")
            }
            Diag.event("wxpay.begin", "billId" to billId)
            val wx = com.smartlife798.app.core.WxPay.pay(activity, order)
            Diag.event("wxpay.outcome", "billId" to billId, "errCode" to wx.code, "errStr" to wx.message)
            if (!wx.success) {
                return@withContext PaymentFlow.Outcome.Failed(
                    "微信未完成支付（${wx.code}）${wx.message.orEmpty()}",
                )
            }
        } else {
            val prepay = Api.prepay(type, billId)
            if (prepay is Res.Fail) {
                Diag.event("prepay.fail", "billId" to billId, "payType" to type, "code" to prepay.code, "msg" to prepay.msg)
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
                        "memo" to result.memo,
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
                Diag.event("confirm.fail", "billId" to billId, "code" to confirm.code, "msg" to confirm.msg)
                PaymentFlow.Outcome.Failed("支付结果待确认：${confirm.msg}")
            }
        }
    }

    private suspend fun refreshMasterInternal() {
        // 预览模式只用本地示例数据，不请求服务端，避免“演示模式”里混入真实账号数据。
        if (Preview.enabled) return
        if (!session.isLoggedIn) return
        _loadingMaster.value = true
        _masterError.value = null
        val tokenAtStart = session.token
        try {
            val res = withContext(Dispatchers.IO) { Api.master() }
            if (Preview.enabled || session.token != tokenAtStart) return
            when (res) {
                is Res.Ok -> {
                    val master: Master? = res.data
                    val account = master?.account
                    if (account != null) {
                        val p = StoredProfile(
                            id = account.id,
                            name = account.name.ifBlank { account.phone },
                            phone = account.phone.ifBlank { session.phone ?: "" },
                            no = account.no,
                            arrear = account.arrear,
                            score = account.useScore,
                        )
                        Store.saveProfile(p)
                        _profile.value = p
                    }
                    _favorites.value = master?.favos ?: emptyList()
                    Store.saveFavoritesCache(gson.toJson(_favorites.value))
                }
                is Res.Fail -> {
                    _masterError.value = res.msg
                }
            }
        } finally {
            _loadingMaster.value = false
        }
    }

    // ---------------------------------------------------------------- device

    /** Enables the clearly-labelled UI preview (static sample data, no network). */
    fun startPreview() {
        startJob?.cancel()
        watchJob?.cancel()
        masterJob?.cancel()
        deviceRevision += 1
        _pendingStart.value = null
        protocolJob?.cancel()
        updateJob?.cancel()
        downloadJob?.cancel()
        _updateDownload.value = UpdateDownload()
        _checkingUpdate.value = false
        _showUpdate.value = false
        Preview.enabled = true
        // 预览模式不携带任何真实会话：即使某条路径漏判 Preview，也不会打到真实账号。
        session.suspendForPreview()
        _loggedIn.value = true
        _profile.value = Preview.profile
        _favorites.value = Preview.filterFavorites()
        _recents.value = Preview.devices.mapIndexed { i, d ->
            RecentDevice(d.id, d.name, d.dtype, System.currentTimeMillis() - i * 3_600_000L, uses = 3 - i)
        }
        _active.value = null
        _liveStatus.value = null
        _liveUpdatedAt.value = 0L
        _masterError.value = null
        _unpaidCount.value = Preview.bills().count(::isUnpaidBill)
    }

    /** Read-only diagnostics stay in the VM, including preview and account/cancellation guards. */
    suspend fun checkConnection(): String = withContext(Dispatchers.IO) {
        if (Preview.enabled) return@withContext "演示模式下不发起网络请求"
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

    /** Synchronous memory read for the first frame; no disk, network or new account cache. */
    fun deviceEntry(did: String): Device? = deviceEntrySnapshot(
        did, _favorites.value, _recents.value, _active.value, _liveStatus.value,
    )

    suspend fun loadDevice(did: String): Res<DeviceDetail> = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            val detail = Preview.detail(did)
            Res.Ok(detail.copy(device = _liveStatus.value?.takeIf { it.id == did } ?: detail.device))
        } else {
            val token = session.token
            val result = Api.deviceDetail(did)
            checkDeviceSession(token)
            // Full detail can lag behind command/status reads. Reconciliation
            // only consumes dev/status; a cached detail must not erase a start.
            result
        }
    }

    /**
     * UI pages are 1-based; the production endpoint is 0-based. Converting here
     * fixes the "已付款账单明明存在但消费记录为空" symptom.
     */
    suspend fun bills(page: Int, size: Int = Paging.PAGE_SIZE): Res<List<Bill>> = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            return@withContext if (page <= 1) Res.Ok(Preview.bills()) else Res.Ok(emptyList())
        }
        val res = Api.bills(page = Paging.toApiPage(page), size = size)
        if (res is Res.Ok && page <= 1) {
            val list = res.data.orEmpty()
            updateUnpaid(list)
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
        if (Preview.enabled) {
            Res.Ok(BillDetail(bill = Preview.bills().firstOrNull { it.id == id }))
        } else {
            Api.billDetail(id)
        }
    }

    /** Resolves a scanned QR payload through the original service. */
    suspend fun resolveQr(id: String): Res<QrData> = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            Res.Ok(
                QrData(
                    qr = com.smartlife798.app.core.QrInfo(id = id, type = 3),
                    dev = Preview.devices.first(),
                ),
            )
        } else {
            Api.qrUse(id)
        }
    }

    /**
     * One `dev/status` read for the detail page.
     *
     * The response only carries state ([com.smartlife798.app.core.DeviceStatus]).
     * [base] is the device already on screen; the returned device keeps its
     * `bm`/`name`/`ep` and only takes status/gene/subs/ext from the server.
     */
    suspend fun pollDevice(did: String, base: Device): Res<Device> = withContext(Dispatchers.IO) {
        if (Preview.enabled) {
            return@withContext Res.Ok(_liveStatus.value?.takeIf { it.id == did } ?: base)
        }
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

    suspend fun toggleFavorite(device: Device): Res<Unit> = withContext(Dispatchers.IO) {
        val isFav = _favorites.value.any { it.id == device.id }
        if (Preview.enabled) {
            val list = _favorites.value.toMutableList()
            if (isFav) list.removeAll { it.id == device.id } else list.add(0, device)
            _favorites.value = list
            return@withContext Res.Ok(Unit)
        }
        val res = Api.favorite(device.id, if (isFav) 1 else 0)
        if (res is Res.Ok) {
            val list = _favorites.value.toMutableList()
            if (isFav) list.removeAll { it.id == device.id }
            else if (list.none { it.id == device.id }) list.add(0, device)
            _favorites.value = list
            Store.saveFavoritesCache(gson.toJson(list))
        }
        res
    }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun togglePinned(id: String) {
        val now = !_pinned.value.contains(id)
        Store.setPinned(id, now)
        _pinned.value = Store.pinned()
    }

    fun rememberScan(device: Device) {
        Store.touchRecent(device, used = false)
        _recents.value = Store.recents()
    }

    // One foreground watch for this install's confirmed usage or unresolved start.
    // The detail page yields no duplicate reads, and command confirmation owns its reads.
    fun setAppResumed(value: Boolean) {
        appResumed = value
        ensureWatch()
    }

    /** 设备详情页自己有 2s 轮询，这里让位，避免同一设备双倍请求。 */
    fun setDetailDeviceOpen(id: String?) {
        detailDevice = id
        ensureWatch()
    }

    private fun ensureWatch() {
        val usage = _active.value ?: _pendingStart.value
        if (usage == null || !appResumed || detailDevice == usage.deviceId || Preview.enabled ||
            deviceCommand.isLocked
        ) {
            watchJob?.cancel()
            watchJob = null
            return
        }
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            while (isActive && (_active.value != null || _pendingStart.value != null)) {
                syncActiveStatus()
                delay(2_000L)
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
            _favorites.value = favorites.map {
                if (it.id == device.id) it.mergeStatus(state) else it
            }
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
        Store.touchRecent(device, used = true)
        _recents.value = Store.recents()
    }

    fun clearRecent(id: String) {
        Store.clearRecent(id)
        _recents.value = Store.recents()
    }

    fun clearAllRecents() {
        Store.clearRecents()
        _recents.value = emptyList()
    }

    /** Confirmation survives navigation; account reset or ViewModel disposal cancels it. */
    suspend fun startDevice(device: Device, payType: Int, args: String): Res<Unit> = withContext(Dispatchers.Main.immediate) {
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
        if (Preview.enabled) {
            // 预览模式只在内存里模拟，不落盘：避免演示设备的“进行中”记录
            // 在退出预览后继续出现在真实界面里。预览不轮询，所以这里直接给出
            // 一个“运行中”的服务端快照，卡片才有状态可显示。
            _active.value = ActiveUsage(device.id, device.name, device.dtype, System.currentTimeMillis(), payType)
            _liveStatus.value = device.copy(
                gene = (device.gene ?: com.smartlife798.app.core.Gene())
                    .copy(status = com.smartlife798.app.core.DeviceType.Status.WORK),
            )
            _liveUpdatedAt.value = System.currentTimeMillis()
            return@withContext Res.Ok(Unit)
        }
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
        val signal = awaitStartSignal(device, token)
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
    suspend fun syncActiveStatus() {
        val usage = _active.value ?: _pendingStart.value ?: return
        if (Preview.enabled || !session.isLoggedIn || deviceCommand.isLocked) return
        val token = session.token
        val revision = deviceRevision
        val res = withContext(Dispatchers.IO) { Api.deviceStatus(usage.deviceId, false) }
        checkDeviceSession(token)
        val fresh = (res as? Res.Ok)?.data?.device ?: return
        val base = baseDeviceFor(usage.deviceId, usage.name, usage.dtype)
        checkDeviceSession(token)
        if (revision != deviceRevision || deviceCommand.isLocked ||
            (_active.value ?: _pendingStart.value) != usage
        ) return
        reconcileDevice(base.mergeStatus(fresh), StartWatch.classify(base, fresh), token, revision)
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
        if (!deviceCommand.tryLock()) return@withContext Res.Fail(-1, "设备操作处理中，请勿重复操作")
        try {
            deviceRevision += 1
            stopDeviceInternal(device)
        } finally {
            deviceCommand.unlock()
            ensureWatch()
        }
    }

    private suspend fun stopDeviceInternal(device: Device): Res<Device> = withContext(Dispatchers.Main.immediate) {
        if (Preview.enabled) {
            // 预览模式只在内存里结束，不写任何本地状态。
            val standby = device.copy(gene = (device.gene ?: com.smartlife798.app.core.Gene())
                .copy(status = com.smartlife798.app.core.DeviceType.Status.LOCKED))
            if (activeBelongsTo(_active.value?.deviceId, device.id)) _active.value = null
            publishLive(standby)
            return@withContext Res.Ok(standby)
        }
        if (!session.isLoggedIn) return@withContext Res.Fail(ErrorCodes.TOKEN_EXPIRED, "请先登录")
        val token = session.token
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
                "msg" to res.msg,
            )
        }
        res
    }

    suspend fun changeMode(device: Device, mode: Int, args: String): Res<Unit> = withContext(Dispatchers.IO) {
        if (Preview.enabled) Res.Ok(Unit) else Api.setMode(device.id, mode, args)
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
