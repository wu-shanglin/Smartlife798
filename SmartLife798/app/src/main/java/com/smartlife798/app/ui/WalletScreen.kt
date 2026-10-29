@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Bill
import com.smartlife798.app.core.Product
import com.smartlife798.app.core.Res
import com.smartlife798.app.ui.components.EmptyState
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.ui.components.LoadingBlock
import com.smartlife798.app.ui.components.PaySheet
import com.smartlife798.app.core.collectRefreshRequests
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

/**
 * 钱包：余额概览 + 充值商品。
 *
 * 充值走原应用的 `prd/lst(type=1)` → `POST /bill/save` → 账单支付（余额或支付宝免密），
 * 客户端不上送价格、也不在本地判定成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val wallet by vm.wallet.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<List<Product>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<Bill?>(null) }

    LaunchedEffect(vm) {
        collectRefreshRequests(vm.refreshTick) {
            kotlinx.coroutines.coroutineScope {
                launch { vm.refreshWallet() }
                when (val res = vm.rechargeProducts()) {
                    is Res.Ok -> { products = res.data.orEmpty(); error = null }
                    is Res.Fail -> error = res.msg
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("钱包", modifier = Modifier.sharedCardPageElement()) },
                navigationIcon = {
                    IconButton(shapes = androidx.compose.material3.IconButtonDefaults.shapes(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "钱包余额",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        Text(
                            Fmt.money(wallet?.total ?: 0.0),
                            modifier = Modifier.sharedCardPageElement(CardPart.AMOUNT),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            wallet?.ep?.name?.takeIf { it.isNotBlank() } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(
                                "现金 " + Fmt.money(wallet?.ofCash ?: 0.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                            Text(
                                "赠送 " + Fmt.money(wallet?.ofGift ?: 0.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
            item {
                Text("充值金额", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            when {
                error != null -> item { ErrorState(error!!) { scope.launch { vm.refreshWallet() } } }
                products == null -> item { LoadingBlock("正在获取充值商品…") }
                products!!.isEmpty() -> item { EmptyState("暂无可充值商品", "服务端没有返回 type=1 的充值商品。") }
                else -> items(products!!, key = { it.id }) { product ->
                    Card(
                        onClick = {
                            if (creating) return@Card
                            creating = true
                            scope.launch {
                                try {
                                    when (val res = vm.createRechargeBill(product)) {
                                        is Res.Ok -> {
                                            payTarget = Bill(
                                                id = res.data ?: "",
                                                ctime = System.currentTimeMillis(),
                                                payment = product.curPrice,
                                                status = com.smartlife798.app.core.BillStatus.UNPAID,
                                                note = product.name,
                                            )
                                        }
                                        is Res.Fail -> vm.notify("创建充值订单失败：${vm.errorText(res.code, res.msg)}")
                                    }
                                } finally {
                                    creating = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "到账 " + Fmt.money(product.curPrice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (creating) {
                                LoadingIndicator(modifier = Modifier.size(18.dp),)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                "充值",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item {
                HintBanner(
                    "充值会创建一笔真实的充值账单，金额由服务端商品决定；支付时优先使用钱包余额，" +
                        "余额不足则使用支付宝（已签约免密时由服务端直接扣款）。",
                )
            }
            if (payTarget != null) {
                item { Spacer(Modifier.height(1.dp)) }
            }
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
                scope.launch { vm.refreshWallet() }
            },
        )
    }
}
