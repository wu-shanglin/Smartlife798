@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.PayChannel
import com.smartlife798.app.core.PayChoice
import com.smartlife798.app.core.PaymentFlow
import com.smartlife798.app.core.Res
import com.smartlife798.app.ui.Fmt
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

/** One bill, one explicit payment; server confirmation remains the only success authority. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaySheet(
    vm: MainViewModel,
    activity: Activity?,
    bill: Bill,
    onDismiss: () -> Unit,
    onPaid: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var channels by remember(bill.id) { mutableStateOf<List<PayChannel>?>(null) }
    var error by remember(bill.id) { mutableStateOf<String?>(null) }
    var selected by remember(bill.id) { mutableStateOf<PayChannel?>(null) }
    var paying by remember(bill.id) { mutableStateOf(false) }
    var result by remember(bill.id) { mutableStateOf<String?>(null) }
    var retry by remember(bill.id) { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val wallet by vm.wallet.collectAsState()

    LaunchedEffect(bill.id, retry) {
        channels = null
        error = null
        selected = null
        when (val res = vm.loadChannels(bill.id)) {
            is Res.Ok -> {
                val list = res.data.orEmpty()
                channels = list
                selected = PayChoice.forBill(list, wallet?.total ?: 0.0, bill.payment ?: 0.0)
            }
            is Res.Fail -> error = res.msg
        }
    }

    AppBottomSheet(onDismissRequest = { if (!paying) onDismiss() }, sheetState = sheetState, dismissEnabled = !paying) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("支付账单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { if (!paying) scope.launch { sheetState.hide(); onDismiss() } },
                    enabled = !paying,
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Filled.Close, contentDescription = "关闭支付面板") }
            }
            Text("应付金额", style = MaterialTheme.typography.labelLarge)
            Text(Fmt.money(bill.payment ?: 0.0), style = MaterialTheme.typography.headlineLarge)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val balance = wallet?.total ?: 0.0
                Text(
                    "钱包余额 ${Fmt.money(balance)}" +
                        if (balance + 1e-9 >= (bill.payment ?: 0.0)) " · 默认使用钱包" else " · 余额不足时使用支付宝免密",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = channels
                when {
                    error != null -> {
                        Text("加载失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                    list == null -> LoadingBlock("正在获取支付渠道…")
                    list.isEmpty() -> Text("服务端未返回可用的支付渠道", style = MaterialTheme.typography.bodyMedium)
                    else -> list.forEach { channel ->
                        TonalToggleButton(
                            checked = selected?.type == channel.type,
                            enabled = !paying,
                            onCheckedChange = {
                                if (!paying) {
                                    selected = channel
                                    result = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(PaymentFlow.channelLabel(channel.type, channel.name) +
                                (channel.desc?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                        }
                    }
                }
                HintBanner(
                    "余额/钱包由服务端直接扣款；支付宝已签约免密时同样由服务端扣款，不再拉起 SDK。" +
                        "未签约的支付宝与微信支付会拉起各自 SDK。结果只以 trans/confirm 为准。",
                )
            }
            result?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
                )
            }
            val channel = selected
            Button(
                enabled = !paying && (error != null || (channel != null && activity != null)),
                onClick = {
                    if (error != null) {
                        retry += 1
                        return@Button
                    }
                    // Guard the entry itself; two taps can arrive before recomposition.
                    if (channel == null || activity == null || paying) return@Button
                    paying = true
                    result = null
                    scope.launch {
                        try {
                            when (val outcome = vm.payBill(activity, bill.id, channel)) {
                                is PaymentFlow.Outcome.Paid -> {
                                    result = outcome.message
                                    vm.notify(outcome.message)
                                    onPaid()
                                }
                                is PaymentFlow.Outcome.Failed -> result = outcome.message
                                is PaymentFlow.Outcome.Unavailable -> result = outcome.message
                            }
                        } finally {
                            paying = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (paying) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (paying) "正在支付…" else if (error != null) "重试" else "确认支付")
            }
        }
    }
}
