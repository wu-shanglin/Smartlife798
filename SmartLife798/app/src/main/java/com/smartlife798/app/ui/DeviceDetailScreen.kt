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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.smartlife798.app.core.StartCommandLock
import com.smartlife798.app.core.primaryAction
import com.smartlife798.app.core.stateLabel
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.ui.components.InfoRow
import com.smartlife798.app.ui.components.LoadingBlock
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
    onNeedPayment: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val awaitCardEntry = rememberAwaitCardEntry()
    val initialDevice = remember(deviceId) { vm.deviceEntry(deviceId) ?: Device(id = deviceId) }
    var entryReady by remember(deviceId) { mutableStateOf(false) }
    LaunchedEffect(deviceId) {
        awaitCardEntry()
        entryReady = true
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val favorites by vm.favorites.collectAsState()
    val pinned by vm.pinned.collectAsState()
    var detail by remember { mutableStateOf<DeviceDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var autoStarted by rememberSaveable(deviceId) { mutableStateOf(false) }
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
    val active by vm.active.collectAsState()

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
    val favorite = favorites.any { it.id == deviceId }

    suspend fun reload(showLoading: Boolean) {
        if (showLoading) loading = true
        val res = vm.loadDevice(deviceId)
        // Preserve the already rendered seed and shared title throughout entry.
        // Also hold refresh results during a return/cancelled return transition.
        awaitCardEntry()
        when (res) {
            is Res.Ok -> {
                detail = res.data
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
        vm.setDetailDeviceOpen(deviceId)
        onDispose { vm.setDetailDeviceOpen(null) }
    }
    val refreshTick by vm.refreshTick.collectAsState()
    LaunchedEffect(refreshTick, deviceId) { reload(detail == null) }

    // 设备状态轮询：只在页面处于 RESUMED 时运行；仅“启动中/运行中”或刚启动的
    // 窗口内每 2s 一次，空闲与异常状态不轮询。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    suspend fun pollOnce() {
        val current = detail ?: return
        val device = current.device ?: return
        when (val res = vm.pollDevice(deviceId, device)) {
            is Res.Ok -> {
                // The VM merged the partial `dev/status` into `device`; this page
                // never receives a dtype-less object.
                detail = current.copy(device = res.data)
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

    LaunchedEffect(deviceId, lifecycleOwner, pendingStartUntil) {
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
                if (!locked && kind != DeviceStateKind.STARTING && kind != DeviceStateKind.RUNNING) {
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
        busy = true
        scope.launch {
            error = null
            var toast: String? = null
            try {
                when (dev.primaryAction()) {
                    PrimaryAction.START -> {
                        busyText = "正在启动设备，等待设备响应…"
                        val args = Args.forDevice(dev, mode)
                        when (val res = vm.startDevice(dev, payType, args)) {
                            is Res.Ok -> {
                                seenInUseSinceStart = false
                                pendingStartUntil = System.currentTimeMillis() + StartCommandLock.WINDOW_MS
                                toast = if (DeviceType.isWaterLike(dev.dtype)) {
                                    "已授权取水：请在设备上按「启动」键取水，用完按「结算」或点下方按钮结束"
                                } else {
                                    "服务端已确认设备启动，请按设备提示使用"
                                }
                                reload(false)
                            }
                            is Res.Fail -> {
                                // No pending window on failure: the command did
                                // not take effect, so no state polling starts.
                                if (res.code == ErrorCodes.START_UNCONFIRMED) {
                                    // The device may have started late; show
                                    // whatever the server reports now, then keep
                                    // the explanation on screen (reload clears it).
                                    reload(false)
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
                                toast = "已结束使用，可在订单页查看消费记录"
                                reload(false)
                            }
                            is Res.Fail -> error = vm.errorText(res.code, res.msg)
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
    LaunchedEffect(autoStart, detail?.device?.id, entryReady) {
        if (!autoStart || autoStarted || !entryReady) return@LaunchedEffect
        val dev = detail?.device ?: return@LaunchedEffect
        autoStarted = true
        when (dev.primaryAction()) {
            PrimaryAction.START -> {
                vm.notify("已识别设备，正在自动开始…")
                action()
            }
            PrimaryAction.OFFLINE -> vm.notify("设备当前离线，暂时无法开始使用")
            PrimaryAction.DISABLED -> vm.notify("设备已停用，暂时无法使用")
            PrimaryAction.LINKING -> vm.notify("该设备需要蓝牙连接，请使用原应用")
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shownDevice.name.ifBlank { "设备详情" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    reload(detail == null)
                    pollOnce()
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                    item {
                        HeroCard(
                            device = shownDevice,
                            confirmed = device != null,
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

                    if (device == null) {
                        item(key = "pending-detail") {
                            when {
                                !loading && error != null -> ErrorState(error!!) { scope.launch { reload(true) } }
                                entryReady -> LoadingBlock("正在获取设备信息…")
                                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    repeat(2) {
                                        Box(Modifier.fillMaxWidth().height(88.dp).background(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            RoundedCornerShape(18.dp),
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    if (error != null && device != null) {
                        item {
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

                    val parts = detail?.device?.bm?.parts.orEmpty().filter { it.mode > 0 }
                    if (parts.isNotEmpty()) {
                        item { ModeSection(parts, mode) { mode = it } }
                    }

                    val payItems = detail?.payItems.orEmpty().map { it.type }
                    if (payItems.isNotEmpty()) {
                item {
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
                        item { InfoCard(loaded) }
                    }

                    val wallet = detail?.wallet
                    if (wallet != null && (wallet.total > 0 || wallet.ofCash > 0)) {
                        item {
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

                    if (device != null) item {
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
    confirmed: Boolean,
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
    val state = device.stateLabel()
    val action = device.primaryAction()
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

            // The cached snapshot supplies identity only. No command, payment or
            // favourite action is exposed until the full response is published.
            if (!confirmed) {
                Text("正在更新设备信息…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            if (state.kind == DeviceStateKind.RUNNING || state.kind == DeviceStateKind.STARTING) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("当前模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(modeLabel(device), style = MaterialTheme.typography.titleMedium)
                    }
                    Column {
                        Text("更新时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Fmt.clock(lastUpdate), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Button(
                onClick = onAction,
                enabled = !busy && !startLocked &&
                    action in setOf(PrimaryAction.START, PrimaryAction.STOP),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(busyText.ifBlank { "处理中…" })
                } else {
                    Text(
                        when {
                            startLocked -> "正在启动，请稍候…"
                            else -> when (action) {
                                PrimaryAction.START -> "立即使用"
                                PrimaryAction.STOP -> "结束使用并结算"
                                PrimaryAction.OFFLINE -> "设备离线，暂不可用"
                                PrimaryAction.DISABLED -> "设备已停用"
                                PrimaryAction.LINKING -> "蓝牙设备，请使用原应用连接"
                                PrimaryAction.SETTLE -> "立即结算"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onFavorite,
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
private fun ModeSection(parts: List<com.smartlife798.app.core.DevicePart>, selected: Int?, onSelect: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择模式", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                parts.forEach { part ->
                    FilterChip(
                        selected = selected == part.mode,
                        onClick = { onSelect(part.mode) },
                        label = { Text(part.name.ifBlank { "模式 ${part.mode}" }) },
                    )
                }
            }
            Text(
                "费率来自服务端配置，实际扣费以服务端结算为准",
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
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                payItems.forEach { type ->
                    FilterChip(
                        selected = selected == type,
                        onClick = { onSelect(type) },
                        label = { Text(PayType.label(type)) },
                    )
                }
            }
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
