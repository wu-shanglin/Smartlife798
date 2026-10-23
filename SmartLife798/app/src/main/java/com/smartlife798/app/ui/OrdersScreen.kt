package com.smartlife798.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.BillDetail
import com.smartlife798.app.core.BillStatus
import com.smartlife798.app.core.DeviceType
import com.smartlife798.app.core.PayChannel
import com.smartlife798.app.core.PaymentFlow
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.isUnpaid
import com.smartlife798.app.core.statusLabel
import com.smartlife798.app.ui.components.EmptyState
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.ui.components.InfoRow
import com.smartlife798.app.ui.components.PaySheet
import com.smartlife798.app.ui.components.LoadingBlock
import com.smartlife798.app.ui.components.UsageDuration
import com.smartlife798.app.ui.components.StatusPill
import com.smartlife798.app.ui.components.WavyLinearProgress
import com.smartlife798.app.ui.components.usagePillState
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

private enum class OrderFilter(val label: String) {
    ALL("全部"),
    UNPAID("待付款"),
    DONE("已完成"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(vm: MainViewModel, onOpenDevice: (String, CardOrigin) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val active by vm.active.collectAsStateWithLifecycle()
    val live by vm.liveStatus.collectAsStateWithLifecycle()
    val liveAt by vm.liveUpdatedAt.collectAsStateWithLifecycle()
    // `liveStatus` is global; only this device's snapshot may describe this session.
    val liveForActive = live?.takeIf { it.id == active?.deviceId }
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    val unpaidBadge by vm.unpaidCount.collectAsStateWithLifecycle()
    val payRequest by vm.payRequest.collectAsStateWithLifecycle()

    // Land on the cloud records; "使用中" is one tap away when a session exists.
    var tab by remember { mutableStateOf(1) }
    var filter by remember { mutableStateOf(OrderFilter.ALL) }
    var bills by remember { mutableStateOf<List<Bill>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<BillDetail?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<Bill?>(null) }

    suspend fun loadBills(reset: Boolean) {
        if (reset) {
            page = 1
            endReached = false
            loading = bills.isEmpty()
        } else {
            loadingMore = true
        }
        error = null
        when (val res = vm.bills(page = page, size = 20)) {
            is Res.Ok -> {
                val list = res.data.orEmpty()
                bills = if (reset) list else bills + list
                if (list.size < 20) endReached = true
            }
            is Res.Fail -> {
                error = res.msg
                // Keep showing the cached rows, but say the refresh failed instead
                // of silently presenting stale data as current.
                if (bills.isNotEmpty()) vm.notify("刷新失败：${res.msg}")
            }
        }
        loading = false
        loadingMore = false
    }

    // Bills plus the local session: the 「进行中」 card must not survive a refresh
    // once the server reports the machine idle or gone.
    LaunchedEffect(refreshTick) {
        loadBills(true)
        vm.syncActiveStatus()
    }

    // A failed dev/start raises this request; land directly on the outstanding bill.
    LaunchedEffect(payRequest) {
        val request = payRequest ?: return@LaunchedEffect
        tab = 1
        filter = OrderFilter.UNPAID
        request.bill?.let { payTarget = it }
        vm.notify(request.note)
        vm.consumePayRequest()
    }

    val unpaid = bills.count { it.isUnpaid }
    val done = bills.count { it.status == BillStatus.PAID }
    val visibleBills = when (filter) {
        OrderFilter.ALL -> bills
        OrderFilter.UNPAID -> bills.filter { it.isUnpaid }
        OrderFilter.DONE -> bills.filter { it.status == BillStatus.PAID }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        // loadBills() already refreshes the unpaid badge from page 0.
                        loadBills(true)
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "订单与记录",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (unpaidBadge > 0) {
                            Spacer(Modifier.width(8.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                Text("$unpaidBadge 笔待付")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            label = { Text("使用中") },
                        )
                        FilterChip(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            label = {
                                Text(
                                    if (unpaidBadge > 0) "消费记录 · $unpaidBadge 待付" else "消费记录",
                                )
                            },
                        )
                    }
                }
            }

            if (tab == 0) {
                if (active == null) {
                    item {
                        EmptyState(
                            "当前没有进行中的使用",
                            "在设备详情页点击「立即使用」后，这里会显示正在使用的设备与实时用时。",
                        )
                    }
                    item {
                        HintBanner(
                            "只有服务端确认设备已离开待机后，本客户端才会记录「进行中」；状态与计费仍以服务端为准。",
                        )
                    }
                } else {
                    item {
                        Card(
                            onClick = { onOpenDevice(active!!.deviceId, CardOrigin.ORDERS_ACTIVE) },
                            modifier = Modifier.sharedCard(CardOrigin.ORDERS_ACTIVE, active!!.deviceId).fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(usagePillState(liveForActive))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        active!!.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                UsageDuration(
                                    startedAt = active!!.startedAt,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    "设备类型：${DeviceType.name(active!!.dtype)} · 开始于 ${Fmt.clock(active!!.startedAt)}" +
                                    if (liveForActive != null && liveAt > 0) " · 服务端 ${Fmt.clock(liveAt)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Button(onClick = { onOpenDevice(active!!.deviceId, CardOrigin.ORDERS_ACTIVE) }) { Text("打开设备") }
                            }
                        }
                    }
                    item {
                        HintBanner(
                            "结束使用请进入设备详情页操作，结束动作会调用服务端结算接口并生成账单。",
                        )
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrderFilter.entries.forEach { option ->
                            val count = when (option) {
                                OrderFilter.ALL -> bills.size
                                OrderFilter.UNPAID -> unpaid
                                OrderFilter.DONE -> done
                            }
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text("${option.label} $count") },
                            )
                        }
                    }
                }

                when {
                    loading && bills.isEmpty() -> item { LoadingBlock("正在获取云端订单…") }
                    error != null && bills.isEmpty() -> item {
                        ErrorState(error!!) { scope.launch { loadBills(true) } }
                    }
                    visibleBills.isEmpty() -> item {
                        EmptyState(
                            when (filter) {
                                OrderFilter.UNPAID -> "没有待付款账单"
                                OrderFilter.DONE -> "还没有已完成的订单"
                                OrderFilter.ALL -> "暂无消费记录"
                            },
                            when (filter) {
                                OrderFilter.UNPAID -> "当前账号没有未付款或付款失败的订单。"
                                OrderFilter.DONE -> "设备结算完成后，已付款订单会显示在这里。"
                                OrderFilter.ALL -> "使用设备并完成结算后，云端订单会显示在这里。"
                            },
                        )
                    }
                    else -> {
                        items(visibleBills, key = { it.id }) { bill ->
                            BillRow(
                                bill = bill,
                                onClick = {
                                    scope.launch {
                                        when (val res = vm.billDetail(bill.id)) {
                                            is Res.Ok -> {
                                                // `view-full` may omit the bill
                                                // object; keep the row we already
                                                // have so the pay CTA still works.
                                                val loaded = res.data
                                                detail = if (loaded?.bill != null) {
                                                    loaded
                                                } else {
                                                    BillDetail(bill = bill, cnt = loaded?.cnt)
                                                }
                                            }
                                            is Res.Fail -> vm.notify(res.msg)
                                        }
                                    }
                                },
                                onPay = { payTarget = bill },
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (endReached) {
                                    Text(
                                        "没有更多记录了",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    TextButton(
                                        enabled = !loadingMore,
                                        onClick = {
                                            page += 1
                                            scope.launch { loadBills(false) }
                                        },
                                    ) { Text(if (loadingMore) "加载中…" else "加载更多") }
                                }
                            }
                        }
                        if (loadingMore) {
                            item {
                                WavyLinearProgress(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 40.dp),
                                    heightDp = 4,
                                )
                            }
                        }
                        item {
                            Text(
                                "云端订单：${Api.BASE_URL}/api/v1/bill/lst-owner · " +
                                    "已加载 ${bills.size} 条（未付款 $unpaid / 已完成 $done）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    val detailValue = detail
    if (detailValue != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheetState) {
            BillDetailSheet(
                detail = detailValue,
                onPay = { bill ->
                    // Close the detail sheet first: two ModalBottomSheets at once
                    // stack their scrims and only the top one can be dismissed.
                    detail = null
                    payTarget = bill
                },
            )
        }
    }

    val target = payTarget
    if (target != null) {
        PaySheet(
            vm = vm,
            activity = activity,
            bill = target,
            onDismiss = { payTarget = null },
            onPaid = {
                payTarget = null
                detail = null
                scope.launch { loadBills(true) }
            },
        )
    }
}

@Composable
private fun BillRow(bill: Bill, onClick: () -> Unit, onPay: () -> Unit) {
    val title = bill.prds.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        ?: bill.dev?.let { d -> DeviceType.nameOrNull(d.dtype)?.let { "${it}消费" } }
        ?: "设备消费"
    val subtitle = bill.msg?.takeIf { it.isNotBlank() }
        ?: bill.dev?.let { d ->
            val type = DeviceType.nameOrNull(d.dtype)
            val name = d.name.ifBlank { d.id.takeLast(6) }
            if (type != null) "$type · $name" else name
        }
    val unpaid = bill.isUnpaid
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    Fmt.dateTime(bill.ctime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    Fmt.money(bill.payment ?: 0.0),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    bill.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bill.status == BillStatus.PAID) MaterialTheme.colorScheme.primary
                    else if (unpaid) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unpaid) {
                    TextButton(
                        onClick = onPay,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("去支付") }
                }
            }
        }
    }
}

@Composable
private fun BillDetailSheet(detail: BillDetail, onPay: (Bill) -> Unit) {
    val bill = detail.bill
    val payableBill = bill?.takeIf { it.isUnpaid }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The sheet can be taller than the screen once goods and device
            // rows are present; scrolling keeps the pay CTA reachable.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("账单详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (payableBill != null) {
                // Visible as soon as the sheet opens, before any long detail
                // rows: paying an outstanding bill must not require scrolling.
                Button(
                    onClick = { onPay(payableBill) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                ) { Text("去支付") }
            }
        }
        if (bill == null) {
            Text("未获取到账单详情", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (payableBill != null) {
            Text(
                "该账单尚未支付，完成支付后服务端才会更新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (bill.msg?.isNotBlank() == true) {
            Text(bill.msg!!, style = MaterialTheme.typography.bodyMedium)
        }
        InfoRow("账单编号", bill.id, mono = true)
        InfoRow("创建时间", Fmt.dateTime(bill.ctime))
        InfoRow("更新时间", Fmt.dateTime(bill.utime))
        InfoRow("订单金额", Fmt.money(bill.payment ?: 0.0))
        InfoRow("优惠金额", Fmt.money(bill.discount))
        InfoRow("支付状态", bill.statusLabel)
        if (bill.dev != null) {
            InfoRow("设备名称", bill.dev.name.ifBlank { "-" })
            InfoRow("设备编号", bill.dev.id, mono = true)
            InfoRow("设备类型", DeviceType.nameOrNull(bill.dev.dtype) ?: "-")
        }
        if (bill.ep != null) InfoRow("服务单位", bill.ep.name)
        if (!bill.note.isNullOrBlank()) InfoRow("备注", bill.note!!)
        if (bill.prds.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("商品明细", style = MaterialTheme.typography.titleSmall)
            bill.prds.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${p.name} × ${p.count ?: 1}", style = MaterialTheme.typography.bodyMedium)
                    Text(Fmt.money(p.curPrice * (p.count ?: 1)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            "以上数据来自服务端 /api/v1/bill/view-full",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
