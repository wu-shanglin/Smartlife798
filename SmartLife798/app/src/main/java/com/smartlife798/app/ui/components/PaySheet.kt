@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.PayChannel
import com.smartlife798.app.core.PayChoice
import com.smartlife798.app.core.PaymentFlow
import com.smartlife798.app.core.Res
import com.smartlife798.app.ui.Fmt
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Payment sheet for one bill, shared by the orders list and the wallet recharge.
 *
 * The default channel follows the product rule: wallet when the account balance
 * covers the bill, otherwise Alipay (免密/协议支付). Alipay only needs the SDK
 * when the server returns a signed order; with a signed agreement `prepay` is
 * empty and the server debits directly, so [MainViewModel.payBill] goes straight
 * to `trans/confirm`.
 */
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
    var channels by remember { mutableStateOf<List<PayChannel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<PayChannel?>(null) }
    var paying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val wallet by vm.wallet.collectAsState()

    LaunchedEffect(bill.id) {
        when (val res = vm.loadChannels(bill.id)) {
            is Res.Ok -> {
                val list = res.data.orEmpty()
                channels = list
                selected = PayChoice.forBill(
                    channels = list,
                    balance = wallet?.total ?: 0.0,
                    amount = bill.payment ?: 0.0,
                )
            }
            is Res.Fail -> error = res.msg
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!paying) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("支付账单", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("应付金额", style = MaterialTheme.typography.bodyMedium)
                Text(
                    Fmt.money(bill.payment ?: 0.0),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
            val balance = wallet?.total ?: 0.0
            Text(
                "钱包余额 ${Fmt.money(balance)}" +
                    if (balance + 1e-9 >= (bill.payment ?: 0.0)) " · 默认使用钱包" else " · 余额不足时使用支付宝免密",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val list = channels
            when {
                error != null -> ErrorState(error!!) { error = null }
                list == null -> LoadingBlock("正在获取支付渠道…")
                list.isEmpty() -> Text("服务端未返回可用的支付渠道", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { channel ->
                            TonalToggleButton(
                                checked = selected?.type == channel.type,
                                onCheckedChange = { selected = channel; result = null },
                                content = {
                                    Text(
                                        PaymentFlow.channelLabel(channel.type, channel.name) +
                                            (channel.desc?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    )
                                },
                            )
                        }
                    }
                    val channel = selected
                    Button(
                        enabled = channel != null && !paying && activity != null,
                        onClick = {
                            // A double tap can land before `paying` recomposes;
                            // without this guard the same bill is submitted twice.
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(shape = MaterialTheme.shapes.large),
                    ) {
                        if (paying) {
                            LinearWavyProgressIndicator(modifier = Modifier.width(60.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (paying) "正在支付…" else "确认支付")
                    }
                    if (result != null) {
                        Text(
                            result!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HintBanner(
                "余额/钱包由服务端直接扣款；支付宝已签约免密时同样由服务端扣款，不再拉起 SDK。" +
                    "未签约的支付宝与微信支付会拉起各自 SDK。结果只以 trans/confirm 为准。",
            )
        }
    }
}
