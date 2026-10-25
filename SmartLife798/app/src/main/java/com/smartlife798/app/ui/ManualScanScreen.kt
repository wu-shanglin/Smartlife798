@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.QrParser
import com.smartlife798.app.core.QrResolution
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.resolveQr
import com.smartlife798.app.ui.components.HintBanner
import androidx.compose.material3.LinearWavyProgressIndicator
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScanScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var manualText by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (resolving || manualText.isBlank()) return
        resolving = true
        error = null
        status = "正在向服务端查询…"
        scope.launch {
            val parsed = QrParser.parse(manualText)
            if (parsed.type == -1 || parsed.id.isBlank()) {
                status = null
                error = "无法识别的内容：${manualText.take(60)}"
                resolving = false
                return@launch
            }
            when (val res = vm.resolveQr(parsed.id)) {
                is Res.Ok -> {
                    val data = res.data
                    when (val outcome = resolveQr(data?.dev, data?.qr?.type, parsed.type)) {
                        is QrResolution.Resolved -> {
                            vm.rememberScan(outcome.device)
                            status = "已识别：${outcome.device.name}"
                            resolving = false
                            onOpenDevice(outcome.device.id)
                        }
                        QrResolution.NoDevice -> {
                            status = null
                            error = "二维码有效，但服务端未返回设备信息（可能已被停用）"
                            resolving = false
                        }
                        is QrResolution.OtherType -> {
                            status = null
                            error = "该二维码类型为「${QrParser.qrTypeName(outcome.type)}」，" +
                                "本客户端当前只支持设备类二维码"
                            resolving = false
                        }
                    }
                }
                is Res.Fail -> {
                    status = null
                    error = res.msg
                    resolving = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("输入设备编号") },
                navigationIcon = {
                    IconButton(shapes = androidx.compose.material3.IconButtonDefaults.shapes(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回扫一扫")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "输入设备贴纸上的编号，或二维码链接 /q/1/b?id= 后面的那段字符。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it.trim().take(120) },
                label = { Text("设备编号 / 二维码内容") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = manualText.isNotBlank() && !resolving,
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shapes = androidx.compose.material3.ButtonDefaults.shapes(shape = MaterialTheme.shapes.large),
            ) {
                if (resolving) {
                    LinearWavyProgressIndicator(modifier = Modifier.width(60.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (resolving) "查询中…" else "查询设备")
            }
            if (status != null) {
                Text(
                    status!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (error != null) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HintBanner(
                "编号会通过服务端 /api/v1/qr/use 解析，只有服务端确认是设备码才会进入设备页；" +
                    "手动输入不会在本地伪造设备信息。查询成功后本页和扫一扫都会关闭。",
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
