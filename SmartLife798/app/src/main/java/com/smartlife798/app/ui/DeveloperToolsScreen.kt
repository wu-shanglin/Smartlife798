package com.smartlife798.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.smartlife798.app.core.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(enabled: Boolean, onOpenNfc: () -> Unit, onDisable: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("开发者工具") }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回我的")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("诊断工具", style = MaterialTheme.typography.titleLarge)
            Text("选择需要的工具。采集功能只有在对应页面主动开启后才会运行。")
            OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = onOpenNfc) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("NFC 采集", style = MaterialTheme.typography.titleMedium)
                    Text("读取卡片的原始 NDEF 内容，查看或导出本机记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { onDisable(); onBack() }) { Text("关闭开发者工具") }
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
                    if (!active || busy || Preview.enabled) return@launch
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
        if (enabled && !Preview.enabled) {
            try { records = NfcCaptureLog.read(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "暂时无法读取记录" }
        } else stop()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("NFC 采集") }, navigationIcon = {
            IconButton(onClick = { stop(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回开发者工具")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("点击开始后保持本页前台再碰卡。离开本页或锁屏会停止采集；返回后需重新开始。")
            Text("原始 NDEF 内容只保存在本机，不自动打开链接或启停设备。导出文件包含卡片原始链接与参数。")
            Text(if (Preview.enabled) "演示模式不读取或保存 NFC" else if (!enabled) "开发者工具已关闭" else status)
            Button(enabled = enabled && !Preview.enabled && reader != null && !active,
                onClick = { status = reader?.start().orEmpty(); active = reader?.isActive() == true }) {
                Text("开始采集")
            }
            OutlinedButton(enabled = active, onClick = ::stop) { Text("停止采集") }
            OutlinedButton(enabled = enabled && !Preview.enabled && records.isNotBlank() && !busy,
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
            TextButton(enabled = enabled && !Preview.enabled && records.isNotBlank() && !busy, onClick = {
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
