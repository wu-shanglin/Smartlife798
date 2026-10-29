@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.smartlife798.app.ui.components.ChoiceButtonGroup
import androidx.compose.material3.TopAppBar
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.withResumed
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Args
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.DeviceDetail
import com.smartlife798.app.core.Diag
import com.smartlife798.app.core.DeviceStateKind
import com.smartlife798.app.core.DeviceType
import com.smartlife798.app.core.ErrorCodes
import com.smartlife798.app.core.PayType
import com.smartlife798.app.core.PrimaryAction
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.collectRefreshRequests
import com.smartlife798.app.core.isInUse
import com.smartlife798.app.core.isStandby
import com.smartlife798.app.core.StartWatch
import com.smartlife798.app.core.StartCommandLock
import com.smartlife798.app.core.primaryAction
import com.smartlife798.app.core.stateLabel
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.ui.components.InfoRow
import com.smartlife798.app.ui.components.StatusPill
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    vm: MainViewModel,
    deviceId: String,
    onBack: () -> Unit,
    onOpenOrders: () -> Unit,
    autoStart: Boolean = false,
    nfcTicket: String = "",
    onNeedPayment: () -> Unit = {},
    listState: LazyListState? = null,
) {
    val scope = rememberCoroutineScope()
    val awaitCardEntry = rememberAwaitCardEntry()
    val initialDevice = remember(deviceId) { vm.deviceEntry(deviceId) ?: Device(id = deviceId) }
    var entryReady by remember(deviceId) { mutableStateOf(false) }
    LaunchedEffect(deviceId) {
        awaitCardEntry()
        entryReady = true
    }
    val detailListState = listState ?: rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val savingProfile by vm.savingProfile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshMaster() }
    var detail by remember { mutableStateOf<DeviceDetail?>(null) }
    var autoStartSnapshot by remember(deviceId, nfcTicket) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    // Invalidate reads started before a command; old responses cannot undo its result.
    var statusRevision by remember(deviceId) { mutableStateOf(0) }
    var autoStarted by rememberSaveable(deviceId, nfcTicket) { mutableStateOf(false) }
    var payType by remember { mutableStateOf(0) }
    var signingAli by remember(deviceId) { mutableStateOf(false) }
    var mode by remember { mutableStateOf<Int?>(null) }
    var lastUpdate by remember { mutableStateOf(0L) }
    // A successful dev/start is not reflected by the server instantly, so the
    // START button is locked while a command is in flight. The lock is a
    // fallback only: it is released as soon as the device reports an in-use
    // state, comes back to standby after being in use, or the usage record is
    // gone (ended from Home, logout…). It must never outlive the session.
    var pendingStartUntil by remember(deviceId) { mutableStateOf(0L) }
    var seenInUseSinceStart by remember(deviceId) { mutableStateOf(false) }
    val active by vm.active.collectAsStateWithLifecycle()

    LaunchedEffect(active, deviceId) {
        // `active` is only written after a successful start. If it disappears
        // while a pending window is still open, the session was ended
        // elsewhere: unlock immediately instead of waiting for the timeout.
        if (pendingStartUntil > 0 && active?.deviceId != deviceId) {
            pendingStartUntil = 0L
        }
    }

    val device = detail?.device
    val shownDevice = device ?: initialDevice
    val access = detail?.takeIf { it.device != null }?.let(vm::deviceAccess)
    val favorite = favorites.any { it.id == deviceId }

    suspend fun reload(showLoading: Boolean, duringCommand: Boolean = false) {
        if (busy && !duringCommand) return
        val revision = statusRevision
        if (showLoading) loading = true
        val res = vm.loadDevice(deviceId)
        // Publish immediately. The shared shell and fixed viewport isolate layout
        // changes from the animated bounds; only command execution waits for entry.
        if (revision != statusRevision || (busy && !duringCommand)) {
            loading = false
            return
        }
        when (res) {
            is Res.Ok -> {
                detail = res.data
                autoStartSnapshot = res.data?.device != null
                error = if (res.data?.device == null) "设备不存在或已停用" else null
                lastUpdate = System.currentTimeMillis()
                if (payType == 0) {
                    val items = res.data?.payItems.orEmpty().map { it.type }
                    // Balance first, then Alipay (免密/协议支付); a channel the
                    // client cannot settle is only picked when nothing else exists.
                    payType = com.smartlife798.app.core.PayChoice.devicePayType(
                        payItems = items,
                        balance = res.data?.wallet?.total ?: 0.0,
                    ) ?: -1
                }
                if (mode == null) {
                    // Only a mode the device actually exposes may be sent as
                    // `args`; water machines have no selectable parts and must
                    // get an empty args payload.
                    val parts = res.data?.device?.bm?.parts.orEmpty().filter { it.mode > 0 }
                    mode = res.data?.device?.gene?.mode?.takeIf { current -> parts.any { it.mode == current } }
                        ?: parts.firstOrNull()?.mode
                }
            }
            is Res.Fail -> error = res.msg
        }
        loading = false
    }

    // 详情页有自己的 2s 轮询；告知 ViewModel 让位，避免同一设备双倍请求。
    androidx.compose.runtime.DisposableEffect(deviceId) {
        val owner = Any()
        vm.setDetailDeviceOpen(deviceId, owner)
        onDispose { vm.releaseDetailDevice(owner) }
    }
    LaunchedEffect(deviceId, nfcTicket) {
        // Navigation emits another tick after entry settles. Keep the first
        // in-flight response instead of cancelling it and starting a second read.
        collectRefreshRequests(vm.refreshTick) { reload(detail == null) }
    }

    // 设备状态轮询：只在页面处于 RESUMED 时运行；仅“启动中/运行中”或刚启动的
    // 窗口内每 2s 一次，空闲与异常状态不轮询。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    suspend fun pollOnce() {
        if (busy) return
        val revision = statusRevision
        val current = detail ?: return
        val device = current.device ?: return
        // A partial status cannot tell whether another account released/acquired
        // the machine. While blocked, refresh the ownership envelope as well.
        if (vm.deviceAccess(current).notice != null) {
            when (val res = vm.loadDevice(deviceId)) {
                is Res.Ok -> if (revision == statusRevision && !busy && res.data?.device?.id == deviceId) {
                    if (res.data != current) detail = res.data
                    lastUpdate = System.currentTimeMillis()
                }
                is Res.Fail -> Unit
            }
            return
        }
        when (val res = vm.pollDevice(deviceId, device)) {
            is Res.Ok -> {
                if (revision != statusRevision || busy) return
                // The VM merged the partial `dev/status` into `device`; this page
                // never receives a dtype-less object.
                if (res.data != device) detail = current.copy(device = res.data,
                    user = if (res.data?.isStandby == true && device.isInUse) null else current.user)
                lastUpdate = System.currentTimeMillis()
            }
            is Res.Fail -> Unit
        }
    }

    /**
     * Releases the start lock based on what the device actually reports.
     *
     * The timeout is only a fallback for "server has not applied the command
     * yet". Once the machine is seen in use, or returns to standby after being
     * in use, there is no command left to wait for: a later "结束使用" (from
     * Home or the device itself) must unlock "立即使用" immediately instead of
     * leaving it disabled until the window expires.
     */
    fun noteObservedState() {
        when (detail?.device?.stateLabel()?.kind) {
            DeviceStateKind.STARTING, DeviceStateKind.RUNNING -> seenInUseSinceStart = true
            DeviceStateKind.ONLINE -> if (seenInUseSinceStart && pendingStartUntil != 0L) {
                pendingStartUntil = 0L
            }
            else -> Unit
        }
    }

    fun startLocked(now: Long = System.currentTimeMillis()): Boolean =
        StartCommandLock.isLocked(
            state = detail?.device?.stateLabel()?.kind ?: DeviceStateKind.OFFLINE,
            seenInUse = seenInUseSinceStart,
            pendingUntil = pendingStartUntil,
            now = now,
            activeDeviceId = active?.deviceId,
            deviceId = deviceId,
        )

    LaunchedEffect(deviceId, lifecycleOwner, pendingStartUntil, detail?.device?.isInUse, access?.notice, busy) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            pollOnce()
            noteObservedState()
            while (true) {
                val dev = detail?.device ?: break
                val kind = dev.stateLabel().kind
                // Offline / fault / disabled: polling cannot help and must not
                // be (re)started, not even inside a pending-start window.
                if (kind == DeviceStateKind.OFFLINE || kind == DeviceStateKind.DISABLED ||
                    kind == DeviceStateKind.BLUETOOTH
                ) {
                    if (pendingStartUntil != 0L) pendingStartUntil = 0L
                    break
                }
                val locked = startLocked()
                // Same rule as the original client: only a machine that is
                // starting/running is polled. A failed start, an ended session
                // or a stale lock therefore leaves no loop behind.
                if (!locked && kind != DeviceStateKind.STARTING && kind != DeviceStateKind.RUNNING &&
                    detail?.let(vm::deviceAccess)?.notice == null) {
                    if (pendingStartUntil != 0L) pendingStartUntil = 0L
                    break
                }
                delay(2_000L)
                pollOnce()
                noteObservedState()
                if (pendingStartUntil > 0 && System.currentTimeMillis() >= pendingStartUntil) {
                    pendingStartUntil = 0L
                }
            }
        }
    }

    fun action() {
        // Guard against a second tap that lands before `busy` triggers recomposition:
        // both would otherwise call dev/start (or dev/end) and be billed twice.
        if (busy || !entryReady) return
        val dev = detail?.device ?: return
        val currentAccess = detail?.let(vm::deviceAccess) ?: return
        if (currentAccess.action == null) {
            currentAccess.notice?.let(vm::notify)
            return
        }
        statusRevision += 1
        busy = true
        scope.launch {
            error = null
            var toast: String? = null
            try {
                when (currentAccess.action) {
                    PrimaryAction.START -> {
                        busyText = "正在启动设备，等待设备响应…"
                        val args = Args.forDevice(dev, mode)
                        when (val res = vm.startDevice(dev, payType, args)) {
                            is Res.Ok -> {
                                seenInUseSinceStart = false
                                pendingStartUntil = System.currentTimeMillis() + StartCommandLock.WINDOW_MS
                                toast = if (!StartWatch.isObservable(dev)) {
                                    "启动请求已受理，执行结果以设备和服务端为准"
                                } else if (DeviceType.isWaterLike(dev.dtype)) {
                                    "已授权取水：请在设备上按「启动」键取水，用完按「结算」或点下方按钮结束"
                                } else {
                                    "服务端已确认设备启动，请按设备提示使用"
                                }
                                reload(false, duringCommand = true)
                            }
                            is Res.Fail -> {
                                if (res.code == ErrorCodes.DEVICE_ACCESS_CHANGED) reload(false, duringCommand = true)
                                // No pending window on failure: the command did
                                // not take effect, so no state polling starts.
                                if (res.code == ErrorCodes.START_UNCONFIRMED) {
                                    // The device may have started late; show
                                    // whatever the server reports now, then keep
                                    // the explanation on screen (reload clears it).
                                    reload(false, duringCommand = true)
                                    error = vm.errorText(res.code, res.msg)
                                    toast = res.msg
                                } else {
                                    error = vm.errorText(res.code, res.msg)
                                }
                                if (res.code == ErrorCodes.UNPAID_BILL || res.code == ErrorCodes.PAYMENT_REQUIRED) {
                                    toast = "上一笔订单尚未支付，正在打开付款…"
                                    vm.requestPaymentForUnpaid("上一笔订单尚未支付")
                                    onNeedPayment()
                                }
                            }
                        }
                    }
                    PrimaryAction.STOP -> {
                        busyText = "正在结束使用并结算…"
                        when (val res = vm.stopDevice(dev)) {
                            is Res.Ok -> {
                                seenInUseSinceStart = false
                                pendingStartUntil = 0L
                                // Use the confirmed snapshot, not an immediate full
                                // reload that may still carry the pre-end state.
                                detail = detail?.copy(device = res.data)
                                lastUpdate = System.currentTimeMillis()
                                toast = "设备已恢复待机，账单以服务端结算为准"
                            }
                            is Res.Fail -> {
                                if (res.code == ErrorCodes.DEVICE_ACCESS_CHANGED) reload(false, duringCommand = true)
                                error = vm.errorText(res.code, res.msg)
                            }
                        }
                    }
                    else -> Unit
                }
            } finally {
                busy = false
                busyText = ""
                toast?.let(vm::notify)
            }
        }
    }

    // 扫码进入详情时自动点一次「立即使用」，只执行一次；离线/禁用设备只提示。
    LaunchedEffect(autoStart, detail?.device?.id, entryReady, autoStartSnapshot, nfcTicket, lifecycleOwner) {
        if (!autoStart || autoStarted || !entryReady || !autoStartSnapshot) return@LaunchedEffect
        val dev = detail?.device ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed {
            if (autoStarted) return@withResumed
            autoStarted = true
            if (nfcTicket.isNotEmpty() && !vm.claimNfcAutoStart(nfcTicket, deviceId)) return@withResumed
            val currentAccess = detail?.let(vm::deviceAccess) ?: return@withResumed
            when (currentAccess.action) {
                PrimaryAction.START -> {
                    if (payType < 0) {
                        vm.notify("暂无可用支付方式，请选择后手动开始")
                        return@withResumed
                    }
                    vm.notify("已识别设备，正在自动开始…")
                    action()
                }
                PrimaryAction.OFFLINE -> vm.notify("设备当前离线，暂时无法开始使用")
                PrimaryAction.DISABLED -> vm.notify("设备已停用，暂时无法使用")
                PrimaryAction.LINKING -> vm.notify("该设备需要蓝牙连接，请使用原应用")
                null -> currentAccess.notice?.let(vm::notify)
                else -> Unit
            }
        }
    }

    val parts = remember(shownDevice.bm) { shownDevice.bm?.parts.orEmpty().filter { it.mode > 0 } }
    val payItems = remember(detail?.payItems) { detail?.payItems.orEmpty().map { it.type } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shownDevice.name.ifBlank { "设备详情" }) },
                navigationIcon = {
                    IconButton(shapes = androidx.compose.material3.IconButtonDefaults.shapes(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ExpressiveRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        try {
                            reload(detail == null)
                            pollOnce()
                        } finally {
                            refreshing = false
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hero", contentType = "hero") {
                    HeroCard(
                        device = shownDevice,
                        access = access,
                        confirmed = device != null,
                        loading = loading,
                        actionsReady = entryReady,
                        favorite = favorite,
                        pinned = pinned.contains(deviceId),
                        lastUpdate = lastUpdate,
                        busy = busy,
                        busyText = busyText,
                        startLocked = startLocked(),
                        onAction = { action() },
                        onFavorite = {
                            val confirmedDevice = detail?.device ?: return@HeroCard
                            scope.launch {
                                when (val res = vm.toggleFavorite(confirmedDevice)) {
                                    is Res.Ok -> vm.notify(if (favorite) "已取消收藏" else "已收藏")
                                    is Res.Fail -> error = vm.errorText(res.code, res.msg)
                                }
                            }
                        },
                        onPin = { vm.togglePinned(deviceId) },
                        onOpenOrders = onOpenOrders,
                    )
                }

                if (device == null && !loading && error != null) {
                    item(key = "pending-detail", contentType = "pending-detail") {
                        ErrorState(error!!) { scope.launch { reload(true) } }
                    }
                }

                if (error != null && device != null) {
                    item(key = "error", contentType = "error") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                error!!,
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                if ((device == null && loading) || (device != null && parts.isNotEmpty())) {
                    item(key = "modes", contentType = "modes") {
                        ModeSection(parts, mode ?: initialDevice.gene?.mode, loading = device == null) { mode = it }
                    }
                }

                if (payItems.isNotEmpty()) {
                    item(key = "use-score", contentType = "use-score") {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("使用积分", style = MaterialTheme.typography.titleSmall)
                                    Text(if (savingProfile) "正在保存账号设置…"
                                        else if (profile?.useScore == null) "请刷新以读取账号积分设置"
                                        else "账号自动记住选择，抵扣规则以服务端为准",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = profile?.useScore == 1,
                                    enabled = !busy && !savingProfile && profile?.useScore != null,
                                    modifier = Modifier.semantics { contentDescription = "使用积分开关" },
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            when (val result = vm.setUseScore(enabled)) {
                                                is Res.Ok -> Unit
                                                is Res.Fail -> vm.notify(result.msg)
                                            }
                                        }
                                    })
                            }
                        }
                    }
                    item(key = "payment", contentType = "payment") {
                        PaySection(
                            payItems = payItems,
                            selected = payType,
                            onSelect = { payType = it },
                            signing = signingAli,
                            onSignAlipay = {
                                if (!signingAli) {
                                    signingAli = true
                                    scope.launch {
                                        try {
                                            when (val res = vm.alipaySignPayload()) {
                                                is Res.Ok -> {
                                                    val payload = res.data.orEmpty()
                                                    if (payload.startsWith("alipays://")) {
                                                        try {
                                                            context.startActivity(
                                                                android.content.Intent(
                                                                    android.content.Intent.ACTION_VIEW,
                                                                    android.net.Uri.parse(payload),
                                                                ),
                                                            )
                                                        } catch (e: android.content.ActivityNotFoundException) {
                                                            vm.notify("未安装支付宝，无法完成签约")
                                                        }
                                                    } else {
                                                        val host = context as? android.app.Activity
                                                        if (host == null) {
                                                            vm.notify("无法打开支付宝签约页")
                                                        } else {
                                                            val outcome = com.smartlife798.app.core.AlipaySdk.pay(host, payload)
                                                            Diag.event(
                                                                "sign.alipay",
                                                                "success" to outcome.success,
                                                                "resultStatus" to outcome.status,
                                                                "memo" to outcome.memo,
                                                            )
                                                            vm.notify(
                                                                if (outcome.success) "已从支付宝返回，请在支付宝页面确认签约结果"
                                                                else "签约未完成（${outcome.status}）${outcome.memo}",
                                                            )
                                                        }
                                                    }
                                                }
                                                is Res.Fail -> vm.notify(res.msg)
                                            }
                                        } finally {
                                            signingAli = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                detail?.takeIf { it.device != null }?.let { loaded ->
                    item(key = "info", contentType = "info") { InfoCard(loaded) }
                }

                val wallet = detail?.wallet
                if (wallet != null && (wallet.total > 0 || wallet.ofCash > 0)) {
                    item(key = "wallet", contentType = "wallet") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("账户余额", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    Fmt.money(wallet.total),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "现金 ${Fmt.money(wallet.ofCash)} · 赠送 ${Fmt.money(wallet.ofGift)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (device != null) item(key = "settlement", contentType = "settlement") {
                    HintBanner(
                        "设备状态、计费与订单均由官方服务端下发；结束使用后由服务端结算并生成消费记录。" +
                            "如设备提示余额不足，请先在原应用中充值，本客户端不代收任何款项。",
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    device: Device,
    access: com.smartlife798.app.core.DeviceAccess?,
    confirmed: Boolean,
    loading: Boolean,
    actionsReady: Boolean,
    favorite: Boolean,
    pinned: Boolean,
    lastUpdate: Long,
    busy: Boolean,
    busyText: String,
    startLocked: Boolean,
    onAction: () -> Unit,
    onFavorite: () -> Unit,
    onPin: () -> Unit,
    onOpenOrders: () -> Unit,
) {
    val state = if (access?.notice != null) com.smartlife798.app.core.DeviceState(
        DeviceStateKind.RUNNING, access.label ?: "状态待确认",
    ) else device.stateLabel()
    val action = access?.action
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // A partial `dev/status` snapshot has no `bm`: never let the
                    // known type/name collapse to 「智能设备」.
                    val typeName = if (device.dtype != 0) DeviceType.name(device.dtype) else "设备"
                    Text(
                        typeName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        device.name.ifBlank { typeName },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (confirmed) StatusPill(state)
                else Text("待更新", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "编号 ${device.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            device.ep?.name?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Keep the same text/layout slots before and after the response. Building
            // the entire controls/metadata block mid-flight causes a cold composition spike.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(if (confirmed) "当前模式" else "模式信息", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (confirmed) modeLabel(device) else "等待服务端", style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    Text("更新时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (confirmed) Fmt.clock(lastUpdate) else "更新中…", style = MaterialTheme.typography.titleMedium)
                }
            }

            Button(
                onClick = onAction,
                enabled = confirmed && actionsReady && !busy && !startLocked &&
                    action in setOf(PrimaryAction.START, PrimaryAction.STOP),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = androidx.compose.material3.ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
            ) {
                if (busy) {
                    LoadingIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(busyText.ifBlank { "处理中…" })
                } else {
                    Text(
                        when {
                            !confirmed -> if (loading) "正在更新设备信息…" else "设备信息不可用"
                            startLocked -> "正在启动，请稍候…"
                            else -> when (action) {
                                PrimaryAction.START -> "立即使用"
                                PrimaryAction.STOP -> "结束使用并结算"
                                PrimaryAction.OFFLINE -> "设备离线，暂不可用"
                                PrimaryAction.DISABLED -> "设备已停用"
                                PrimaryAction.LINKING -> "蓝牙设备，请使用原应用连接"
                                PrimaryAction.SETTLE -> "立即结算"
                                null -> "${access?.label ?: "设备状态待确认"}，暂不可用"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            access?.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    onClick = onFavorite,
                    enabled = confirmed && actionsReady,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (favorite) "已收藏" else "收藏", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    onClick = onPin,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (pinned) "已置顶" else "置顶", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    onClick = onOpenOrders,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text("订单", maxLines = 1, softWrap = false)
                }
            }
            Text(
                "收藏会同步到服务端账号；置顶仅保存在本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modeLabel(device: Device): String {
    val mode = device.gene?.mode
    val part = device.bm?.parts?.firstOrNull { it.mode == mode }
    return part?.name?.takeIf { it.isNotBlank() } ?: (mode?.let { "模式 $it" } ?: "-")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeSection(
    parts: List<com.smartlife798.app.core.DevicePart>,
    selected: Int?,
    loading: Boolean,
    onSelect: (Int) -> Unit,
) {
    val placeholders = remember { List(2) { com.smartlife798.app.core.DevicePart() } }
    val knownParts = parts.isNotEmpty()
    val options = if (!knownParts && loading) placeholders else parts
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (loading) "正在获取设备信息…" else "选择模式", style = MaterialTheme.typography.titleSmall)
            ChoiceButtonGroup(
                labels = options.map { if (loading && !knownParts) "正在获取…" else it.name.ifBlank { "模式 ${it.mode}" } },
                selectedIndex = options.indexOfFirst { selected == it.mode },
                onSelect = { if (!loading) onSelect(options[it].mode) }, enabled = !loading,
            )
            Text(
                if (loading) "完整响应到达前不能选择模式或操作设备"
                else "费率来自服务端配置，实际扣费以服务端结算为准",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaySection(
    payItems: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    signing: Boolean,
    onSignAlipay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("支付方式", style = MaterialTheme.typography.titleSmall)
            ChoiceButtonGroup(labels = payItems.map { PayType.label(it) },
                selectedIndex = payItems.indexOf(selected), onSelect = { onSelect(payItems[it]) })
            // Only warn for channels this build genuinely cannot complete; Alipay
            // (21) and the server-side wallets are integrated.
            if (selected != 0 && !PayType.isSupportedHere(selected)) {
                Text(
                    "「${PayType.label(selected)}」需要对应官方 SDK 的授权，当前客户端未集成；" +
                        "请改选账户余额 / 商家钱包 / 支付宝，或先在原应用中完成支付。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (payItems.contains(PayType.ALIPAY)) {
                androidx.compose.material3.HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("支付宝免密签约", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "在支付宝页面完成签约；签约后由服务端直接扣款，无需每次拉起支付宝",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.TextButton(
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                        onClick = onSignAlipay,
                        enabled = !signing,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (signing) "正在获取…" else "去支付宝签约")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(detail: DeviceDetail) {
    val device = detail.device ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("设备信息", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            InfoRow("设备名称", device.name.ifBlank { "-" })
            InfoRow("设备编号", device.id, mono = true)
            InfoRow("设备类型", if (device.dtype != 0) DeviceType.name(device.dtype) else "-")
            val place = device.addr?.display()?.takeIf { it.isNotBlank() }
                ?: device.ep?.addr?.display()?.takeIf { it.isNotBlank() } ?: "-"
            InfoRow("所在位置", place)
            InfoRow("服务单位", device.ep?.name ?: "-")
            InfoRow("固件版本", device.fmv ?: "-")
            if (!device.memo.isNullOrBlank()) InfoRow("使用说明", device.memo!!)
            InfoRow("收藏状态", if (detail.deviceShare != null) "已收藏" else "未收藏")
        }
    }
}
