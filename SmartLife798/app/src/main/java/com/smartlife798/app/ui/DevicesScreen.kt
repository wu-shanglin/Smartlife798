@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Device
import com.smartlife798.app.core.DeviceModel
import com.smartlife798.app.core.DeviceType
import com.smartlife798.app.core.DevicesShelf
import com.smartlife798.app.ui.components.ChoiceButtonGroup
import com.smartlife798.app.ui.components.DeviceCard
import com.smartlife798.app.ui.components.EmptyState
import com.smartlife798.app.ui.components.ErrorState
import com.smartlife798.app.ui.components.LoadingBlock
import com.smartlife798.app.ui.components.deviceSubtitle
import com.smartlife798.app.vm.MainViewModel

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun DevicesScreen(vm: MainViewModel, onOpenDevice: (String, CardOrigin) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val loading by vm.loadingMaster.collectAsStateWithLifecycle()
    val error by vm.masterError.collectAsStateWithLifecycle()

    var shelf by rememberSaveable { mutableStateOf(DevicesShelf.FAVORITE) }
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<Int?>(null) }

    // Home's "全部 / 更多" links arrive as a one-shot request for a specific shelf.
    LaunchedEffect(Unit) { vm.consumeShelf()?.let { shelf = it } }

    // Same silent auto-refresh contract as the other tabs; without it this page
    // only ever refreshed through pull-to-refresh.
    PageRefreshEffect(vm) { vm.refreshMaster().join() }

    val favoriteIds = remember(favorites) { favorites.mapTo(hashSetOf()) { it.id } }
    val favoritesById = remember(favorites) { favorites.associateBy { it.id } }
    val recentsById = remember(recents) { recents.associateBy { it.id } }
    val commons = remember(favorites, recents, pinned) {
        favorites.filter { d ->
            pinned.contains(d.id) || (recentsById[d.id]?.uses ?: 0) >= 2
        }
    }
    val recentDevices = remember(recents, favorites) {
        recents.map { r ->
            favoritesById[r.id] ?: Device(
                id = r.id,
                name = r.name,
                bm = DeviceModel(dtype = r.dtype),
            )
        }
    }

    val baseList: List<Device> = when (shelf) {
        DevicesShelf.FAVORITE -> favorites
        DevicesShelf.COMMON -> commons
        DevicesShelf.RECENT -> recentDevices
    }
    val list = remember(baseList, query, typeFilter, pinned) {
        val search = query.trim()
        baseList.filter { d ->
            (search.isEmpty() || d.name.contains(search, true) || d.id.contains(search, true) ||
                DeviceType.name(d.dtype).contains(search, true)) &&
                (typeFilter == null || d.dtype == typeFilter)
        }.sortedByDescending { it.id in pinned }
    }
    val types = remember(favorites, recentDevices) {
        (favorites + recentDevices).map { it.dtype }.filter { it != 0 }.distinct().sorted()
    }

    ExpressiveRefreshBox(
        onRefresh = {
            vm.refreshAll()
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
                    Text(
                        "我的设备",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    ChoiceButtonGroup(
                        labels = DevicesShelf.entries.map { it.label },
                        selectedIndex = shelf.ordinal,
                        onSelect = { shelf = DevicesShelf.entries[it] },
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索设备名称、编号或类型") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(shapes = androidx.compose.material3.IconButtonDefaults.shapes(), onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (types.isNotEmpty()) {
                        ChoiceButtonGroup(
                            secondary = true,
                            labels = listOf("全部类型") + types.map(DeviceType::name),
                            selectedIndex = if (typeFilter == null) 0
                                else types.indexOf(typeFilter).takeIf { it >= 0 }?.plus(1) ?: -1,
                            onSelect = { index ->
                                val type = types.getOrNull(index - 1)
                                typeFilter = if (typeFilter == type) null else type
                            },
                        )
                    }
                }
            }

            if (loading && baseList.isEmpty()) {
                item { LoadingBlock("正在同步设备…") }
            } else if (error != null) {
                item { ErrorState(error.orEmpty()) { vm.refreshMaster() } }
            }
            if (list.isEmpty() && !(baseList.isEmpty() && (loading || error != null))) {
                item {
                    if (query.isNotBlank() || typeFilter != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(AppLayout.Card),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("没有匹配的设备", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "试试其他名称、编号或类型。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                                    onClick = { query = ""; typeFilter = null },
                                ) { Text("清除筛选") }
                            }
                        }
                    } else when (shelf) {
                        DevicesShelf.FAVORITE -> EmptyState(
                            "收藏列表为空",
                            "在设备详情页点击「收藏」，设备会同步到服务端账号并展示在这里。",
                        )
                        DevicesShelf.COMMON -> EmptyState(
                            "还没有常用设备",
                            "多次使用或手动置顶的设备会进入常用列表，置顶顺序仅保存在本机。",
                        )
                        DevicesShelf.RECENT -> EmptyState(
                            "还没有最近使用记录",
                            "扫码成功或开始使用后，设备会记录在本地最近列表中（不上传服务端）。",
                        )
                    }
                }
            } else {
                items(list, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        favorite = favoriteIds.contains(device.id),
                        pinned = pinned.contains(device.id),
                        subtitle = deviceSubtitle(device),
                        modifier = Modifier.sharedCard(CardOrigin.DEVICES, device.id),
                        onClick = { onOpenDevice(device.id, CardOrigin.DEVICES) },
                    )
                }
            }

            if (recents.isNotEmpty()) {
                item {
                    Text(
                        "收藏随账号同步，最近使用保存在本机。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
