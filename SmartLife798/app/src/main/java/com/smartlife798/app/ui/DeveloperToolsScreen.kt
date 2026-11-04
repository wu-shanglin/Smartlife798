@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartlife798.app.BuildConfig
import com.smartlife798.app.core.NfcCaptureLog
import com.smartlife798.app.core.NfcDiagnosticReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(
    enabled: Boolean,
    onOpenNfc: () -> Unit,
    onCopyToken: () -> Unit,
    onResetGuide: () -> Unit,
    onDisable: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("重置引导卡片？") },
        text = { Text("清除本机所有引导的已读进度，立即从基础卡片重新体验。账号、外观和 NFC 记录不受影响。") },
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = {
            if (!enabled) return@TextButton
            confirmReset = false
            onResetGuide()
        }) { Text("重置并查看") } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmReset = false }) { Text("取消") } },
    )
    Scaffold(topBar = {
        TopAppBar(title = { Text("开发者工具") }, navigationIcon = {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回我的")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, null, Modifier.size(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("探索与调试", style = MaterialTheme.typography.headlineSmall)
                        Text("查看本机诊断，调试引导体验。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("界面与引导", style = MaterialTheme.typography.titleMedium)
            DeveloperToolCard(Icons.Filled.Refresh, "重置引导卡片", "清除已读进度，重新体验基础与新增功能卡片。",
                enabled = enabled, onClick = { if (enabled) confirmReset = true })
            Text("账号诊断", style = MaterialTheme.typography.titleMedium)
            DeveloperToolCard(Icons.Filled.Info, "导出登录令牌", "点击复制当前登录令牌，请妥善保管。",
                enabled = enabled, onClick = onCopyToken)
            Text("设备诊断", style = MaterialTheme.typography.titleMedium)
            DeveloperToolCard(Icons.Filled.Info, "NFC 采集", "读取卡片原始 NDEF 内容，查看或导出本机记录。",
                enabled = enabled, onClick = onOpenNfc)
            OutlinedButton(shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = enabled, onClick = { if (enabled) { onDisable(); onBack() } }) { Text("关闭开发者工具") }
        }
    }
}

@Composable
private fun DeveloperToolCard(icon: ImageVector, title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcCaptureScreen(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("采集已停止") }
    var records by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<Job?>(null) }
    val activity = remember(context) { context.findActivity() }
    val reader = remember(activity) {
        activity?.let { owner ->
            NfcDiagnosticReader(owner) { tag ->
                // Reader callbacks arrive on a binder thread; serialize state changes on Main.
                scope.launch {
                    if (!active || busy) return@launch
                    busy = true
                    capture = scope.launch {
                        try {
                            status = NfcCaptureLog.save(context, tag, BuildConfig.VERSION_NAME) { active }
                            records = NfcCaptureLog.read(context)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "本次未能保存，请重新贴卡" }
                        finally { busy = false }
                    }
                }
            }
        }
    }
    fun stop() {
        active = false
        reader?.stop()
        capture?.cancel()
        status = "采集已停止"
    }
    DisposableEffect(reader, lifecycle, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    LaunchedEffect(enabled) {
        if (enabled) {
            try { records = NfcCaptureLog.read(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "暂时无法读取记录" }
        } else stop()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("NFC 采集") }, navigationIcon = {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { stop(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回开发者工具")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("点击开始后保持本页前台再碰卡。离开本页或锁屏会停止采集；返回后需重新开始。")
            Text("原始 NDEF 内容只保存在本机，不自动打开链接或启停设备。导出文件包含卡片原始链接与参数。")
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("采集状态", style = MaterialTheme.typography.labelLarge)
                    Text(if (!enabled) "开发者工具已关闭" else status, style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled && reader != null && !active,
                onClick = { status = reader?.start().orEmpty(); active = reader?.isActive() == true }) {
                Text("开始采集")
            }
            OutlinedButton(shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth(), enabled = active, onClick = ::stop) { Text("停止采集") }
            OutlinedButton(shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth(), enabled = enabled && records.isNotBlank() && !busy,
                onClick = {
                    stop()
                    scope.launch {
                        try {
                            val file = NfcCaptureLog.shareFile(context) ?: return@launch
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享原始 NFC 记录"))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "暂时无法分享，请稍后重试" }
                    }
                }) { Text("导出原始 NFC 记录") }
            TextButton(shapes = ButtonDefaults.shapes(), enabled = enabled && records.isNotBlank() && !busy, onClick = {
                stop()
                scope.launch {
                    try { NfcCaptureLog.clear(context); records = ""; status = "记录已清空" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "暂时无法清空记录" }
                }
            }) { Text("清空采集记录") }
            Text("采集记录", style = MaterialTheme.typography.titleMedium)
            if (records.length > 16_384) {
                Text("本页显示记录末尾；导出可查看完整内容。", style = MaterialTheme.typography.bodySmall)
            }
            Text(records.takeLast(16_384).ifBlank { "暂无记录" }, modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
