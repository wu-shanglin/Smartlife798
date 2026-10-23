package com.smartlife798.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.DeviceType
import com.smartlife798.app.core.DevicesShelf
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.stateLabel
import com.smartlife798.app.core.mergeStatus
import com.smartlife798.app.core.statusOnly
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.LoadingBlock
import com.smartlife798.app.ui.components.SectionHeader
import com.smartlife798.app.ui.components.StatusPill
import com.smartlife798.app.ui.components.deviceSubtitle
import com.smartlife798.app.ui.components.usagePillState
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onScan: () -> Unit,
    onOpenDevice: (String, CardOrigin) -> Unit,
    onOpenDevices: (DevicesShelf) -> Unit,
    onOpenOrders: () -> Unit,
) {
    val profile by vm.profile.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recents.collectAsState()
    val pinned by vm.pinned.collectAsState()
    val active by vm.active.collectAsState()
    val live by vm.liveStatus.collectAsState()
    val liveAt by vm.liveUpdatedAt.collectAsState()
    val loading by vm.loadingMaster.collectAsState()
    val error by vm.masterError.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun endUsage() {
        // The tap handler runs before recomposition, so guard the local flag as
        // well: a second tap must never issue a second dev/end (and a second
        // settlement) for the same session.
        if (ending) return
        ending = true
        scope.launch {
            var toast: String? = null
            try {
                when (val res = vm.stopActiveDevice()) {
                    is Res.Ok -> toast = "已结束使用，账单以服务端结算为准"
                    is Res.Fail -> toast = "结束失败：${vm.errorText(res.code, res.msg)}"
                }
            } finally {
                ending = false
                toast?.let(vm::notify)
            }
        }
    }

    // Keep the card on screen while the end request is in flight: the session
    // clears as soon as the server answers, and an instantly vanishing card
    // gives no visual confirmation that the tap was received.
    var lastActive by remember { mutableStateOf<com.smartlife798.app.core.ActiveUsage?>(null) }
    LaunchedEffect(active) { if (active != null) lastActive = active }
    val cardActive = active ?: lastActive?.takeIf { ending }

    val refreshTick by vm.refreshTick.collectAsState()
    val syncedAt by vm.historySyncedAt.collectAsState()
    // Full refresh (not just master): the 「进行中」 card is reconciled with
    // `dev/status` here, so a pull-to-refresh can never leave a stale session.
    LaunchedEffect(refreshTick) { vm.refreshAll() }
    LaunchedEffect(active) {
        while (active != null) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val ordered = remember(favorites, pinned, recents) {
        favorites.sortedWith(
            compareByDescending<Device> { pinned.contains(it.id) }
                .thenByDescending { d -> recents.firstOrNull { it.id == d.id }?.lastUsedAt ?: 0L },
        )
    }

    // `liveStatus` is a single global snapshot; it may belong to another device
    // (e.g. one opened from the list while a session runs). Showing it on this
    // card would attribute B's state to A's session.
    val liveForActive = live?.takeIf { it.id == cardActive?.deviceId }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                vm.refreshAll()
                refreshing = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 20.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        Fmt.greeting() + "，" + (profile?.name?.takeIf { it.isNotBlank() } ?: "同学"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "慧生活 798 · 数据自动同步" + if (syncedAt > 0) " · " + Fmt.clock(syncedAt).take(5) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (cardActive != null) {
            item {
                ActiveUsageCard(
                    active = cardActive,
                    tick = now,
                    live = liveForActive,
                    liveAt = if (liveForActive != null) liveAt else 0L,
                    ending = ending,
                    onOpenDevice = { id -> onOpenDevice(id, CardOrigin.HOME_ACTIVE) },
                    onOpenOrders = onOpenOrders,
                    onEnd = { endUsage() },
                )
            }
        }

        item { ScanHeroCard(onScan) }

        item {
            SectionHeader(
                title = "常用设备",
                action = if (favorites.isNotEmpty()) "全部" else null,
                onAction = { onOpenDevices(DevicesShelf.FAVORITE) },
            )
        }
        when {
            loading && favorites.isEmpty() -> item { LoadingBlock("正在同步收藏设备…") }
            error != null && favorites.isEmpty() -> item {
                ErrorState(error!!) { vm.refreshMaster() }
            }
            favorites.isEmpty() -> item {
                EmptyStateCard(
                    "还没有常用设备",
                    "扫码使用设备后，可在设备详情页点击收藏；收藏会同步到你的账号。",
                )
            }
            else -> item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ordered, key = { it.id }) { device ->
                        // A tile may come from a cached favourites list; prefer the
                        // fresh server snapshot for the same device.
                        val shown = live?.takeIf { it.id == device.id }
                            ?.let { device.mergeStatus(it.statusOnly()) } ?: device
                        FavoriteTile(
                            device = shown,
                            pinned = pinned.contains(device.id),
                            onClick = { onOpenDevice(device.id, CardOrigin.HOME_FAVORITE) },
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "最近使用",
                action = if (recents.isNotEmpty()) "更多" else null,
                onAction = { onOpenDevices(DevicesShelf.RECENT) },
            )
        }
        if (recents.isEmpty()) {
            item {
                EmptyStateCard(
                    "暂无使用记录",
                    "扫描设备二维码后，最近使用的设备会出现在这里，方便你快速再次使用。",
                )
            }
        } else {
            items(recents.take(4), key = { it.id }) { recent ->
                Card(
                    onClick = { onOpenDevice(recent.id, CardOrigin.HOME_RECENT) },
                    modifier = Modifier.sharedCard(CardOrigin.HOME_RECENT, recent.id).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (DeviceType.nameOrNull(recent.dtype) ?: "设备").take(1),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                recent.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (DeviceType.nameOrNull(recent.dtype)?.plus(" · ") ?: "") +
                                    Fmt.shortDate(recent.lastUsedAt) + " " +
                                    Fmt.clock(recent.lastUsedAt).take(5),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (recent.uses > 0) {
                            Text(
                                "使用 ${recent.uses} 次",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "数据来源：${Api.BASE_URL}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    }
}

@Composable
private fun ScanHeroCard(onScan: () -> Unit) {
    // Colours come from the scheme, not a fixed brand constant, so the dynamic
    // (Material You) palette actually applies to this card.
    val container = MaterialTheme.colorScheme.primary
    val onContainer = MaterialTheme.colorScheme.onPrimary
    Card(
        onClick = onScan,
        modifier = Modifier.sharedCard(CardOrigin.HOME_SCAN).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "扫码即刻使用",
                style = MaterialTheme.typography.titleLarge,
                color = onContainer,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "扫描设备上的二维码，自动识别水控、淋浴、洗衣等设备并进入使用页",
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                color = onContainer.copy(alpha = 0.18f),
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "打开相机扫码",
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("→", color = onContainer)
                }
            }
        }
    }
}

@Composable
private fun ActiveUsageCard(
    active: com.smartlife798.app.core.ActiveUsage?,
    tick: Long,
    live: com.smartlife798.app.core.Device?,
    liveAt: Long,
    ending: Boolean,
    onOpenDevice: (String) -> Unit,
    onOpenOrders: () -> Unit,
    onEnd: () -> Unit,
) {
    if (active == null) return
    Card(
        onClick = { onOpenDevice(active.deviceId) },
        modifier = Modifier.sharedCard(CardOrigin.HOME_ACTIVE, active.deviceId).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(usagePillState(live))
                Spacer(Modifier.width(10.dp))
                Text(
                    active.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Fmt.duration(active.startedAt, if (tick > 0) tick else System.currentTimeMillis()),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (liveAt > 0) "服务端 " + Fmt.clock(liveAt) else "本机计时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { onOpenDevice(active.deviceId) },
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "查看设备",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                }
                Surface(
                    onClick = onOpenOrders,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "使用记录",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (live == null) "正在向服务端确认状态，尚未确认「使用中」"
                    else if (DeviceType.isWaterLike(active.dtype)) {
                        "已授权取水 · 请在设备上按「启动」键；用完按「结算」或下方结束"
                    } else "每 2 秒同步服务端状态 · 计费以服务端为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onEnd,
                enabled = !ending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                if (ending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (ending) "正在结束…" else "结束使用")
            }
        }
    }
}

@Composable
private fun FavoriteTile(device: Device, pinned: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.sharedCard(CardOrigin.HOME_FAVORITE, device.id).width(176.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DeviceType.nameOrNull(device.dtype) ?: "设备",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (pinned) Text("📌", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                device.name.ifBlank { DeviceType.nameOrNull(device.dtype) ?: "设备" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deviceSubtitle(device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(device.stateLabel())
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
