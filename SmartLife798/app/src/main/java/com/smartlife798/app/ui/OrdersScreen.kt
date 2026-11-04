@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Badge
import com.smartlife798.app.ui.components.AppBottomSheet
import com.smartlife798.app.ui.components.ChoiceButtonGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.sync.Mutex
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
import androidx.compose.material3.LinearWavyProgressIndicator
import com.smartlife798.app.ui.components.usagePillState
import com.smartlife798.app.core.collectRefreshRequests
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
    val active = vm.active.collectAsStateWithLifecycle().value
    val live by vm.liveStatus.collectAsStateWithLifecycle()
    val liveAt by vm.liveUpdatedAt.collectAsStateWithLifecycle()
    // `liveStatus` is global; only this device's snapshot may describe this session.
    val liveForActive = live?.takeIf { it.id == active?.deviceId }
    val unpaidBadge by vm.unpaidCount.collectAsStateWithLifecycle()
    val payRequest by vm.payRequest.collectAsStateWithLifecycle()

    // Land on the cloud records; "使用中" is one tap away when a session exists.
    var tab by rememberSaveable { mutableStateOf(1) }
    var filter by rememberSaveable { mutableStateOf(OrderFilter.ALL) }
    var bills by remember { mutableStateOf<List<Bill>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<BillDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<Bill?>(null) }

    val billLoad = remember { Mutex() }
    suspend fun loadBills(reset: Boolean) {
        if (reset) billLoad.lock() else if (!billLoad.tryLock()) return
        try {
            val requestedPage = if (reset) 1 else page + 1
            if (reset) loading = bills.isEmpty() else loadingMore = true
            error = null
            when (val res = vm.bills(page = requestedPage, size = com.smartlife798.app.core.Paging.RECENT_BILLS_SIZE)) {
                is Res.Ok -> {
                    val list = res.data.orEmpty()
                    bills = (if (reset) list else bills + list).distinctBy { it.id }
                    page = requestedPage
                    endReached = list.size < com.smartlife798.app.core.Paging.RECENT_BILLS_SIZE
                }
                is Res.Fail -> {
                    error = res.msg
                    if (bills.isNotEmpty()) vm.notify("加载失败：${res.msg}")
                }
            }
        } finally {
            loading = false
            loadingMore = false
            billLoad.unlock()
        }
    }

    // Bills plus the local session: the 「进行中」 card must not survive a refresh
    // once the server reports the machine idle or gone.
    LaunchedEffect(vm) {
        collectRefreshRequests(vm.refreshTick) {
            loadBills(true)
            vm.syncActiveStatus()
        }
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

    val unpaid = remember(bills) { bills.count { it.isUnpaid } }
    val done = remember(bills) { bills.count { it.status == BillStatus.PAID } }
    val visibleBills = remember(bills, filter) {
        when (filter) {
            OrderFilter.ALL -> bills
            OrderFilter.UNPAID -> bills.filter { it.isUnpaid }
            OrderFilter.DONE -> bills.filter { it.status == BillStatus.PAID }
        }
    }

    ExpressiveRefreshBox(
        onRefresh = {
            // loadBills also refreshes the unpaid badge from page 0.
            loadBills(true)
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = AppLayout.PagePadding,
            verticalArrangement = Arrangement.spacedBy(AppLayout.Gap),
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
                                Text("已发现 $unpaidBadge 笔待付")
                            }
                        }
                    }
                    ChoiceButtonGroup(
                        labels = listOf("使用中", if (unpaidBadge > 0) "消费记录 · $unpaidBadge 待付" else "消费记录"),
                        selectedIndex = tab,
                        onSelect = { tab = it },
                    )
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
                            modifier = Modifier.clickable { onOpenDevice(active.deviceId, CardOrigin.ORDERS_ACTIVE) }.sharedCard(CardOrigin.ORDERS_ACTIVE, active.deviceId).fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(usagePillState(liveForActive))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        active.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                UsageDuration(
                                    startedAt = active.startedAt,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    "设备类型：${DeviceType.name(active.dtype)} · 开始于 ${Fmt.clock(active.startedAt)}" +
                                    if (liveForActive != null && liveAt > 0) " · 服务端 ${Fmt.clock(liveAt)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Button(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = { onOpenDevice(active.deviceId, CardOrigin.ORDERS_ACTIVE) }) { Text("打开设备") }
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
                    ChoiceButtonGroup(
                        secondary = true,
                        labels = OrderFilter.entries.map { option ->
                            val count = when (option) {
                                OrderFilter.ALL -> bills.size
                                OrderFilter.UNPAID -> unpaid
                                OrderFilter.DONE -> done
                            }
                            "${option.label} $count"
                        },
                        selectedIndex = filter.ordinal,
                        onSelect = { filter = OrderFilter.entries[it] },
                    )
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
                                OrderFilter.DONE -> "已加载记录中暂无已完成订单"
                                OrderFilter.ALL -> "暂无消费记录"
                            },
                            when (filter) {
                                OrderFilter.UNPAID -> if (endReached) "已加载账单中没有待付款记录。" else "已加载账单中暂无匹配，请继续加载更多记录。"
                                OrderFilter.DONE -> if (endReached) "已加载账单中没有已完成记录。" else "请继续加载查看更早记录。"
                                OrderFilter.ALL -> "使用设备并完成结算后，云端订单会显示在这里。"
                            },
                        )
                    }
                    else -> {
                        items(visibleBills, key = { it.id }) { bill ->
                            BillRow(
                                bill = bill,
                                onClick = openDetail@{
                                    if (detailLoading || detail != null || payTarget != null) return@openDetail
                                    detailLoading = true
                                    scope.launch {
                                        try {
                                            when (val res = vm.billDetail(bill.id)) {
                                                is Res.Ok -> {
                                                    // A newer payment request takes precedence over this read.
                                                    if (payTarget == null) {
                                                        val loaded = res.data
                                                        detail = if (loaded?.bill != null) loaded
                                                        else BillDetail(bill = bill, cnt = loaded?.cnt)
                                                    }
                                                }
                                                is Res.Fail -> vm.notify(res.msg)
                                            }
                                        } finally {
                                            detailLoading = false
                                        }
                                    }
                                },
                                onPay = { payTarget = bill },
                            )
                        }

                    }
                }
                if (bills.isNotEmpty()) {
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
                                        shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                                        enabled = !loadingMore,
                                        onClick = {
                                            scope.launch { loadBills(false) }
                                        },
                                    ) { Text(if (loadingMore) "加载中…" else "加载更多") }
                                }
                            }
                        }
                        if (loadingMore) {
                            item {
                                LinearWavyProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 40.dp)
                                )
                            }
                        }
                        item {
                            Text(
                                "以下统计仅覆盖已加载记录 · " +
                                    "已加载 ${bills.size} 条（未付款 $unpaid / 已完成 $done）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }

            }
        }
    }

    val detailValue = detail
    if (detailValue != null && payTarget == null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var closing by remember(detailValue.bill?.id) { mutableStateOf(false) }
        var cancelling by remember(detailValue.bill?.id) { mutableStateOf(false) }
        var confirmCancel by remember(detailValue.bill?.id) { mutableStateOf(false) }
        var cancelError by remember(detailValue.bill?.id) { mutableStateOf<String?>(null) }
        if (confirmCancel) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消这笔未付订单？") },
            text = { Text("取消结果以服务端为准。若刚完成付款，请先刷新订单核对。") },
            confirmButton = { TextButton(onClick = {
                if (!cancelling) {
                    cancelling = true
                    confirmCancel = false
                    scope.launch {
                        try {
                            when (val response = vm.cancelBill(detailValue.bill?.id.orEmpty())) {
                                is Res.Ok -> { vm.notify("订单已取消"); detail = null; loadBills(true) }
                                is Res.Fail -> { cancelError = response.msg; loadBills(true) }
                            }
                        } finally { cancelling = false }
                    }
                }
            }) { Text("确认取消") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("保留订单") } },
        )
        fun closeDetail(next: (() -> Unit)? = null) {
            if (closing || cancelling) return
            closing = true
            scope.launch {
                try {
                    sheetState.hide()
                    detail = null
                    next?.invoke()
                } finally {
                    closing = false
                }
            }
        }
        AppBottomSheet(onDismissRequest = { if (!cancelling) detail = null }, sheetState = sheetState, dismissEnabled = !cancelling) {
            BillDetailSheet(
                detail = detailValue,
                enabled = !closing && !cancelling && cancelError == null,
                cancelError = cancelError,
                cancelling = cancelling,
                onCancel = { confirmCancel = true },
                onDismiss = { closeDetail() },
                onPay = { bill -> closeDetail { payTarget = bill } },
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
                .padding(AppLayout.Card),
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
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                        onClick = onPay,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("去支付") }
                }
            }
        }
    }
}

@Composable
private fun BillDetailSheet(
    detail: BillDetail,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onPay: (Bill) -> Unit,
    onCancel: () -> Unit,
    cancelError: String?,
    cancelling: Boolean,
) {
    val bill = detail.bill
    val payableBill = bill?.takeIf { it.isUnpaid }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = AppLayout.Page).padding(bottom = AppLayout.Page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("账单详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = onDismiss,
                enabled = true,
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Filled.Close, contentDescription = "关闭账单详情") }
        }
        if (bill != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(bill.statusLabel, style = MaterialTheme.typography.labelLarge)
                    Text(Fmt.money(bill.payment ?: 0.0), style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bill == null) {
                Text("未获取到账单详情", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (payableBill != null) {
                    Text(
                        "该账单尚未支付，完成支付后服务端才会更新状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                bill.msg?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                InfoRow("账单编号", bill.id, mono = true)
                InfoRow("创建时间", Fmt.dateTime(bill.ctime))
                InfoRow("更新时间", Fmt.dateTime(bill.utime))
                InfoRow("优惠金额", Fmt.money(bill.discount))
                InfoRow("支付状态", bill.statusLabel)
                bill.dev?.let { device ->
                    InfoRow("设备名称", device.name.ifBlank { "-" })
                    InfoRow("设备编号", device.id, mono = true)
                    InfoRow("设备类型", DeviceType.nameOrNull(device.dtype) ?: "-")
                }
                bill.ep?.let { InfoRow("服务单位", it.name) }
                bill.note?.takeIf { it.isNotBlank() }?.let { InfoRow("备注", it) }
                if (bill.prds.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("商品明细", style = MaterialTheme.typography.titleSmall)
                    bill.prds.forEach { product ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "${product.name} × ${product.count ?: 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(Fmt.money(product.curPrice * (product.count ?: 1)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "账单状态与金额以服务端为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cancelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (payableBill != null) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    enabled = enabled,
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = AppLayout.ActionHeight),
                ) { Text(if (cancelling) "正在核对…" else "取消订单") }
                Button(
                    onClick = { onPay(payableBill) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = AppLayout.ActionHeight),
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                ) { Text("去支付") }
            }
        }
    }
}
