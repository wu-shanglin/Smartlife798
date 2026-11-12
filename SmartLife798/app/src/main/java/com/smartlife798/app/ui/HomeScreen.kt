@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartlife798.app.QrGlyph
import com.smartlife798.app.R
import com.smartlife798.app.core.ActiveUsage
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.DeviceState
import com.smartlife798.app.core.DeviceStateKind
import com.smartlife798.app.core.DeviceType
import com.smartlife798.app.core.DevicesShelf
import com.smartlife798.app.core.RecentDevice
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.mergeStatus
import com.smartlife798.app.core.stateLabel
import com.smartlife798.app.core.statusOnly
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import com.smartlife798.app.ui.components.LoadingBlock
import com.smartlife798.app.ui.components.PinBadge
import com.smartlife798.app.ui.components.SectionHeader
import com.smartlife798.app.ui.components.StatusPill
import com.smartlife798.app.ui.components.UsageDuration
import com.smartlife798.app.ui.components.deviceSubtitle
import com.smartlife798.app.ui.components.usagePillState
import com.smartlife798.app.ui.theme.scanCardColors
import com.smartlife798.app.vm.MainViewModel
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
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val pending by vm.pendingStart.collectAsStateWithLifecycle()
    val live by vm.liveStatus.collectAsStateWithLifecycle()
    val liveAt by vm.liveUpdatedAt.collectAsStateWithLifecycle()
    val loading by vm.loadingMaster.collectAsStateWithLifecycle()
    val error = vm.masterError.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()
    var ending by remember { mutableStateOf(false) }

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
                    is Res.Ok -> toast = "设备已恢复待机，账单以服务端结算为准"
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
    var lastActive by remember { mutableStateOf<ActiveUsage?>(null) }
    LaunchedEffect(active, pending) { (active ?: pending)?.let { lastActive = it } }
    val cardActive = active ?: pending ?: lastActive?.takeIf { ending }

    // Full refresh (not just master): the 「进行中」 card is reconciled with
    // `dev/status` here, so a pull-to-refresh can never leave a stale session.
    PageRefreshEffect(vm) { vm.refreshAll() }

    val ordered = remember(favorites, pinned, recents) {
        val recentById = recents.associateBy { it.id }
        favorites.sortedWith(
            compareByDescending<Device> { pinned.contains(it.id) }
                .thenByDescending { d -> recentById[d.id]?.lastUsedAt ?: 0L },
        )
    }

    // `liveStatus` is a single global snapshot; it may belong to another device
    // (e.g. one opened from the list while a session runs). Showing it on this
    // card would attribute B's state to A's session.
    val liveForActive = live?.takeIf { it.id == cardActive?.deviceId }

    ExpressiveRefreshBox(
        onRefresh = { vm.refreshAll() },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AppLayout.Page, vertical = AppLayout.Gap),
            verticalArrangement = Arrangement.spacedBy(AppLayout.Gap),
        ) {
            item(key = "title") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_artwork),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(colorResource(R.color.launcher_background))
                            .scale(1.35f),
                    )
                    Text(
                        "慧生活 798",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (cardActive != null) {
                item(key = "active") {
                    ActiveUsageCard(
                        active = cardActive,
                        pending = active == null && pending != null,
                        live = liveForActive,
                        liveAt = if (liveForActive != null) liveAt else 0L,
                        ending = ending,
                        onOpenDevice = { id -> onOpenDevice(id, CardOrigin.HOME_ACTIVE) },
                        onOpenOrders = onOpenOrders,
                        onEnd = { endUsage() },
                    )
                }
            }
            item(key = "scan") {
                ScanHeroCard(onScan, modifier = Modifier.padding(vertical = 4.dp))
            }
            item(key = "favorites") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader(
                        title = "常用设备",
                        action = if (favorites.isNotEmpty()) "全部收藏" else null,
                        onAction = { onOpenDevices(DevicesShelf.FAVORITE) },
                    )
                    when {
                        loading && favorites.isEmpty() -> LoadingBlock("正在同步收藏设备…")
                        error != null && favorites.isEmpty() -> ErrorState(error) { vm.refreshMaster() }
                        favorites.isEmpty() -> HomeEmptyState("在设备详情中收藏，常用设备就会显示在这里。")
                        else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val gap = 10.dp
                            val pairWidth = (maxWidth - gap) / 2
                            val readableWidth = 174.dp * LocalDensity.current.fontScale
                            val tileWidth = when {
                                ordered.size == 1 -> maxWidth
                                pairWidth >= readableWidth -> pairWidth
                                else -> maxOf(184.dp, readableWidth).coerceAtMost(maxWidth)
                            }
                            val rowState = rememberLazyListState()
                            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(gap)) {
                                items(ordered, key = { it.id }) { device ->
                                    val shown = live?.takeIf { it.id == device.id }
                                        ?.let { device.mergeStatus(it.statusOnly()) } ?: device
                                    FavoriteTile(
                                        device = shown,
                                        pinned = pinned.contains(device.id),
                                        width = tileWidth,
                                        isScrolling = { rowState.isScrollInProgress },
                                        onClick = { onOpenDevice(device.id, CardOrigin.HOME_FAVORITE) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "recents") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader(
                        title = "最近使用",
                        action = if (recents.isNotEmpty()) "更多记录" else null,
                        onAction = { onOpenDevices(DevicesShelf.RECENT) },
                    )
                    if (recents.isEmpty()) {
                        HomeEmptyState("暂无使用记录")
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column {
                                recents.take(4).forEachIndexed { index, recent ->
                                    key(recent.id) {
                                        if (index > 0) {
                                            HorizontalDivider(
                                                Modifier.padding(horizontal = 12.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                        }
                                        RecentDeviceRow(recent) {
                                            onOpenDevice(recent.id, CardOrigin.HOME_RECENT)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanHeroCard(onScan: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme.scanCardColors()
    Card(
        onClick = onScan,
        modifier = modifier.sharedCard(CardOrigin.HOME_SCAN).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = palette.container, contentColor = palette.content),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 144.dp)
                .padding(horizontal = 20.dp, vertical = AppLayout.Card),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(palette.content.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                QrGlyph(palette.content, Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "扫码使用",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("扫描设备二维码", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun RecentDeviceRow(recent: RecentDevice, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = DeviceType.nameOrNull(recent.dtype)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .sharedCard(CardOrigin.HOME_RECENT, recent.id)
            .background(colors.surfaceContainerLow)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (type ?: "设备").take(1),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                recent.name.ifBlank { type ?: "设备" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    if (type != null) append("$type · ")
                    append(Fmt.shortDate(recent.lastUsedAt))
                    append(" ")
                    append(Fmt.hhmm(recent.lastUsedAt))
                    if (recent.uses > 0) append(" · 使用 ${recent.uses} 次")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActiveUsageCard(
    active: ActiveUsage,
    live: Device?,
    liveAt: Long,
    pending: Boolean,
    ending: Boolean,
    onOpenDevice: (String) -> Unit,
    onOpenOrders: () -> Unit,
    onEnd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .clickable { onOpenDevice(active.deviceId) }
            .sharedCard(CardOrigin.HOME_ACTIVE, active.deviceId)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(AppLayout.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                if (pending) DeviceState(DeviceStateKind.STARTING, "启动待确认") else usagePillState(live),
            )
            Text(
                active.name.ifBlank { DeviceType.nameOrNull(active.dtype) ?: "设备" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!pending) {
                UsageDuration(
                    startedAt = active.startedAt,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (liveAt > 0) "状态更新于 " + Fmt.clock(liveAt) + " · 本机计时" else "本机计时",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                when {
                    pending || live == null -> "正在向服务端确认设备状态"
                    DeviceType.isWaterLike(active.dtype) ->
                        "请在设备上按「启动」取水，用完按「结算」或在下方结束。"
                    else -> "计费以服务端结算为准"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onEnd,
                enabled = !ending,
                modifier = Modifier.fillMaxWidth().heightIn(min = AppLayout.ActionHeight),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onTertiaryContainer,
                    contentColor = colors.tertiaryContainer,
                ),
            ) {
                if (ending) {
                    LoadingIndicator(
                        modifier = Modifier.size(16.dp),
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (ending) "正在结束…" else "结束使用")
            }
            val linkColors = ButtonDefaults.textButtonColors(contentColor = colors.onTertiaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpenDevice(active.deviceId) }, colors = linkColors) {
                    Text("查看设备")
                }
                TextButton(onClick = onOpenOrders, colors = linkColors) { Text("使用记录") }
            }
        }
    }
}

@Composable
private fun FavoriteTile(
    device: Device,
    pinned: Boolean,
    width: Dp,
    isScrolling: () -> Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = DeviceType.nameOrNull(device.dtype) ?: "设备"
    Card(
        modifier = Modifier
            .width(width)
            .tapDuringFling(isScrolling, onClick)
            .clickable(onClick = onClick)
            .sharedCard(CardOrigin.HOME_FAVORITE, device.id),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pinned) colors.surfaceContainer else colors.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    type,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PinBadge(visible = pinned)
            }
            Text(
                device.name.ifBlank { type },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deviceSubtitle(device, includeType = false),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(device.stateLabel())
        }
    }
}

@Composable
private fun HomeEmptyState(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
