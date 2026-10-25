@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun DevicesScreen(vm: MainViewModel, onOpenDevice: (String, CardOrigin) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val refreshTick by vm.refreshTick.collectAsStateWithLifecycle()
    val loading by vm.loadingMaster.collectAsStateWithLifecycle()
    val error by vm.masterError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    var shelf by rememberSaveable { mutableStateOf(DevicesShelf.FAVORITE) }
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<Int?>(null) }

    // Home's "全部 / 更多" links arrive as a one-shot request for a specific shelf.
    LaunchedEffect(Unit) { vm.consumeShelf()?.let { shelf = it } }

    // Same silent auto-refresh contract as the other tabs; without it this page
    // only ever refreshed through pull-to-refresh.
    LaunchedEffect(refreshTick) { vm.refreshMaster() }

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
    val list = remember(baseList, query, typeFilter) {
        val search = query.trim()
        baseList.filter { d ->
            (search.isEmpty() || d.name.contains(search, true) || d.id.contains(search, true) ||
                DeviceType.name(d.dtype).contains(search, true)) &&
                (typeFilter == null || d.dtype == typeFilter)
        }
    }
    val types = remember(favorites, recentDevices) {
        (favorites + recentDevices).map { it.dtype }.filter { it != 0 }.distinct().sorted()
    }

    ExpressiveRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("我的设备", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChoiceButtonGroup(
                        labels = DevicesShelf.entries.map { it.label },
                        selectedIndex = shelf.ordinal,
                        onSelect = { shelf = DevicesShelf.entries[it] },
                    )
                    if (types.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item {
                                TonalToggleButton(
                                    checked = typeFilter == null,
                                    onCheckedChange = { typeFilter = null },
                                    content = { Text("全部类型") },
                                )
                            }
                            items(types) { t ->
                                TonalToggleButton(
                                    checked = typeFilter == t,
                                    onCheckedChange = { typeFilter = if (typeFilter == t) null else t },
                                    content = { Text(DeviceType.name(t)) },
                                )
                            }
                        }
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
                        EmptyState(
                            "没有匹配的设备",
                            "试试其他名称、编号或类型，也可以清除筛选查看全部设备。",
                            actionLabel = "清除筛选",
                            onAction = { query = ""; typeFilter = null },
                        )
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
                        "收藏与使用记录来自服务端；「最近」仅为设备索引，名称与状态始终以服务端返回为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
