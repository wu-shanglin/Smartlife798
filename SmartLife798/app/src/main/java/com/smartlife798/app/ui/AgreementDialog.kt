package com.smartlife798.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartlife798.app.core.UserAgreement
import com.smartlife798.app.vm.MainViewModel

@Composable
internal fun AgreementDialog(onClose: () -> Unit, onAccept: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(UserAgreement.TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UserAgreement.sections.forEach { (heading, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { uriHandler.openUri(UserAgreement.CONTACT_URL) }) { Text("联系项目维护者") }
            }
        },
        confirmButton = {
            if (onAccept != null) TextButton(onClick = onAccept) { Text("同意协议") }
            else TextButton(onClick = onClose) { Text("关闭") }
        },
        dismissButton = {
            if (onAccept != null) TextButton(onClick = onClose) { Text("暂不同意") }
        },
    )
}

@Composable
internal fun UsageStatsToggle(vm: MainViewModel, agreementAccepted: Boolean) {
    if (!vm.usageStatsAvailable) return
    val enabled by vm.usageStatsEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("匿名用量统计", style = MaterialTheme.typography.titleSmall)
            Text(
                if (agreementAccepted) "统计每日活跃安装和设备启动受理，可随时关闭"
                else "同意协议后才会发送，关闭也可以登录",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = vm::setUsageStatsEnabled,
            modifier = Modifier.semantics { contentDescription = "匿名用量统计开关" },
        )
    }
}
