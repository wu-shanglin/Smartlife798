@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.smartlife798.app.BuildConfig
import com.smartlife798.app.core.UpdatePackage
import com.smartlife798.app.core.UpdatePolicy
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun AppUpdatePrompt(
    vm: MainViewModel,
    policy: UpdatePolicy?,
    visible: Boolean,
    checking: Boolean,
    download: MainViewModel.UpdateDownload,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onError: (String) -> Unit,
) {
    if (!visible || policy == null || !policy.available(BuildConfig.VERSION_CODE)) return
    val required = policy.required(BuildConfig.VERSION_CODE)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var installing by rememberSaveable { mutableStateOf(false) }
    var autoOpened by rememberSaveable(policy.sha256) { mutableStateOf(false) }

    fun install() {
        if (installing) return
        installing = true
        scope.launch {
            try {
                val intent = vm.updateInstallIntent() ?: return@launch
                lifecycle.withResumed { context.startActivity(intent) }
            } catch (_: ActivityNotFoundException) {
                onError("系统安装器不可用，请检查系统设置")
            } catch (_: SecurityException) {
                onError("系统拒绝安装，请允许本应用安装未知来源应用后重试")
            } finally {
                installing = false
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (UpdatePackage.canInstall(context)) install()
        else onError("尚未允许安装，请允许本应用安装未知来源应用后重试")
    }
    fun beginInstall() {
        if (UpdatePackage.canInstall(context)) install()
        else {
            try {
                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: ActivityNotFoundException) {
                onError("无法打开安装权限设置，请在系统设置中允许安装未知来源应用")
            } catch (_: SecurityException) {
                onError("无法打开安装权限设置，请检查系统限制")
            }
        }
    }
    LaunchedEffect(download.ready) {
        if (download.ready != null && !autoOpened) {
            lifecycle.withResumed { }
            autoOpened = true
            beginInstall()
        }
    }
    AlertDialog(
        onDismissRequest = {
            if (required) onExit()
            else if (!download.busy) onDismiss()
        },
        properties = DialogProperties(
            // Mandatory updates block business access, not leaving the app.
            // Finishing the task also cancels its ViewModel-owned download.
            dismissOnBackPress = required || !download.busy,
            dismissOnClickOutside = !required && !download.busy,
        ),
        title = { Text(if (required) "需要更新至 ${policy.versionName}" else "发现新版本 ${policy.versionName}") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Only release notes scroll; download status stays above the actions.
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Text(
                            (if (required) "当前版本已停止支持，请安装新版后继续使用。"
                            else "下载后将打开系统安装界面，也可以稍后更新。") +
                                "安装需您确认，首次可能需要允许安装未知来源应用。" +
                                policy.notesSince(BuildConfig.VERSION_NAME).let {
                                    if (it.isBlank()) "" else "\n\n" +
                                        (if (policy.releases.isNotEmpty()) "" else if (policy.historyAvailable)
                                            "跨版本日志暂不可用，以下为最新版本说明。\n\n"
                                        else "本次发布未提供跨版本日志，以下为最新版本说明。\n\n") + it
                                },
                        )
                    }
                }
                UpdateDownloadStatus(download)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !download.busy && !installing,
                onClick = {
                    if (download.ready != null) beginInstall()
                    else {
                        autoOpened = false
                        vm.downloadAppUpdate()
                    }
                },
            ) { Text(if (download.ready != null) "安装新版" else if (download.error != null) "重新下载" else "立即更新") }
        },
        dismissButton = {
            if (download.busy) {
                TextButton(onClick = vm::cancelUpdateDownload) { Text("取消下载") }
            } else if (required) {
                TextButton(onClick = onRetry, enabled = !checking) {
                    Text(if (checking) "正在检查…" else "重新检查")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("稍后更新") }
            }
        },
    )
}

@Composable
private fun UpdateDownloadStatus(download: MainViewModel.UpdateDownload) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                download.busy -> {
                    Text(
                        if (download.percent < 0) "正在下载…" else "下载进度 ${download.percent}%",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val progressModifier = Modifier.fillMaxWidth().height(8.dp)
                    if (download.percent < 0) LinearWavyProgressIndicator(modifier = progressModifier)
                    else LinearWavyProgressIndicator(
                        progress = { download.percent / 100f },
                        modifier = progressModifier,
                    )
                }
                download.ready != null -> Text("安装包已校验，请完成系统安装；取消后可再次点击安装。")
                download.error != null -> Text(download.error, color = MaterialTheme.colorScheme.error)
                else -> Text("点击「立即更新」开始下载")
            }
        }
    }
}
