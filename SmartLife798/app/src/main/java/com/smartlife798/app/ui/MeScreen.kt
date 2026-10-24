package com.smartlife798.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartlife798.app.BuildConfig
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Appearance
import com.smartlife798.app.core.ThemeMode
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.ui.components.InfoRow
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(vm: MainViewModel, onOpenWallet: () -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val wallet by vm.wallet.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val syncedAt by vm.historySyncedAt.collectAsStateWithLifecycle()
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    val themeMode by Appearance.mode.collectAsStateWithLifecycle()
    val dynamicColor by Appearance.dynamicColor.collectAsStateWithLifecycle()
    val checkingUpdate by vm.checkingUpdate.collectAsStateWithLifecycle()
    val protocolVersion by vm.protocolVersion.collectAsStateWithLifecycle()
    val startupPage by vm.startupPage.collectAsStateWithLifecycle()

    LaunchedEffect(refreshTick) {
        vm.refreshMaster()
        vm.refreshWallet()
    }
    val coroutineScope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmClearRecents by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                coroutineScope.launch {
                    try {
                        vm.refreshAll()
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 20.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (profile?.name?.take(1) ?: "慧"),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile?.name ?: "未登录用户",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            Fmt.phoneMasked(profile?.phone ?: session.phone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "用户编号 ${profile?.no ?: profile?.id?.take(8) ?: "-"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (syncedAt > 0) {
                            Text(
                                "资料更新于 " + Fmt.hhmm(syncedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().sharedCard(CardOrigin.WALLET).clickable(onClick = onOpenWallet).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "钱包",
                                modifier = Modifier.sharedCardElement(CardOrigin.WALLET),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MetricValue(
                                wallet?.let { Fmt.money(it.total) } ?: "—",
                                modifier = Modifier.sharedCardElement(CardOrigin.WALLET, part = CardPart.AMOUNT),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("积分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MetricValue("${profile?.score ?: 0}")
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("收藏", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MetricValue("${favorites.size}")
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsHeading(Icons.Filled.Home, "启动页面", "选择下次打开时显示的页面")
                        Row(
                            modifier = Modifier.fillMaxWidth().selectableGroup().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StartupPage.entries.forEach { page ->
                                SettingsChoice(
                                    label = page.label,
                                    selected = startupPage == page,
                                    onClick = { vm.setStartupPage(page) },
                                    icon = if (page == StartupPage.HOME) Icons.Filled.Home else Icons.AutoMirrored.Filled.List,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsHeading(Icons.Filled.Settings, "外观", "选择适合你的显示方式")
                        Row(
                            modifier = Modifier.fillMaxWidth().selectableGroup().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                ThemeMode.SYSTEM to "跟随系统",
                                ThemeMode.LIGHT to "浅色",
                                ThemeMode.DARK to "深色",
                            ).forEach { (mode, label) ->
                                SettingsChoice(
                                    label = label,
                                    selected = themeMode == mode,
                                    onClick = { Appearance.setMode(mode) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("动态取色", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "跟随壁纸配色，关闭用品牌色",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = dynamicColor,
                                        onCheckedChange = { Appearance.setDynamicColor(it) },
                                        modifier = Modifier.semantics { contentDescription = "动态取色开关" },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("服务端连接", style = MaterialTheme.typography.titleSmall)
                        InfoRow("接口地址", Api.BASE_URL)
                        InfoRow("客户端标识", "Android_ilife798_$protocolVersion")
                        InfoRow("登录令牌", if (session.isLoggedIn) "已持有" else "无")
                        InfoRow("本机最近设备", "${recents.size} 台")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !checking,
                                onClick = {
                                    if (checking) return@Button
                                    checking = true
                                    checkResult = null
                                    coroutineScope.launch {
                                        try {
                                            checkResult = vm.checkConnection()
                                        } finally {
                                            checking = false
                                        }
                                    }
                                },
                            ) {
                                if (checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("测试接口连通性")
                            }
                            if (recents.isNotEmpty()) {
                                OutlinedButton(onClick = { confirmClearRecents = true }) {
                                    Text("清空本机记录")
                                }
                            }
                        }
                        if (checkResult != null) {
                            Text(
                                checkResult!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("关于本客户端", style = MaterialTheme.typography.titleSmall)
                        Text("客户端版本 ${BuildConfig.VERSION_NAME}")
                        OutlinedButton(
                            onClick = { vm.checkAppUpdate(manual = true) },
                            enabled = !checkingUpdate,
                        ) { Text(if (checkingUpdate) "正在检查更新…" else "检查更新") }
                        Text(
                            "本应用是「慧生活798」服务端的独立重制客户端：登录、扫码、设备状态、启用/停用设备、" +
                                "计费与账单全部调用官方接口，界面与交互完全重新设计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(
                            "已实现：手机号+图形验证码+短信登录、收藏同步、扫码解析、设备详情与实时状态、" +
                                "启动/结束使用、消费记录与账单详情、支付宝与余额/钱包支付。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "微信 SDK 已集成，平台配置与真实支付待验；未集成云闪付/银行支付、门禁蓝牙与 IC 卡、E 袋洗与驾考模块。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                HintBanner(
                    "收藏数据保存在服务端账号内；常用设备、最近使用与置顶仅保存在本机，卸载应用后会丢失。",
                )
            }

            item {
                OutlinedButton(
                    onClick = { vm.logout() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
            }

            item {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} · 数据来源 ${Api.BASE_URL}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClearRecents) {
        AlertDialog(
            onDismissRequest = { confirmClearRecents = false },
            title = { Text("清空本机最近记录？") },
            text = { Text("只会删除本机保存的设备索引，不影响服务端账号、收藏与账单。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearRecents = false
                    vm.clearAllRecents()
                    checkResult = "已清除本机最近记录"
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearRecents = false }) { Text("取消") }
            },
        )
    }
}

/** Equal-width metrics keep values on one line at narrow widths and larger font scales. */
@Composable
private fun MetricValue(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    BasicText(
        text = value,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineSmall.copy(color = color, textAlign = TextAlign.Center),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 24.sp),
    )
}

/** Settings use the same tinted icon blocks and rounded containers as the device cards. */
@Composable
private fun SettingsHeading(icon: ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
