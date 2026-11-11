@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import com.smartlife798.app.R
import com.smartlife798.app.core.Res
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun LoginScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val checkingUpdate by vm.checkingUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var captchaInput by remember { mutableStateOf("") }
    var smsInput by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf(newSeed()) }
    var captchaBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captchaLoading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadCaptcha() {
        if (captchaLoading) return
        captchaLoading = true
        try {
            val s = newSeed()
            seed = s
            val ts = System.currentTimeMillis().toString()
            val bytes = vm.requestCaptcha(s.toString(), ts)
            captchaBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            captchaInput = ""
        } finally { captchaLoading = false }
    }

    LaunchedEffect(Unit) { loadCaptcha() }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppLayout.Page, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoginBrand()

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "手机号登录",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).semantics { heading() },
                    )
                    LoginField(
                        value = phone,
                        // Filter first, then clamp: checking the raw length dropped
                        // pasted numbers containing spaces entirely.
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        label = "手机号",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumber },
                    )
                    LoginActionRow(
                        field = { fieldModifier ->
                            LoginField(
                                value = captchaInput,
                                onValueChange = { captchaInput = it.take(6) },
                                label = "图形码",
                                modifier = fieldModifier,
                            )
                        },
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.width(LoginActionWidth),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 88.dp, height = 48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val bitmap = captchaBitmap
                                    when {
                                        captchaLoading -> LoadingIndicator(Modifier.size(22.dp))
                                        bitmap != null -> Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "图形验证码",
                                            modifier = Modifier
                                                .size(width = 84.dp, height = 38.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                        )
                                        else -> TextButton(
                                            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                                            onClick = { scope.launch { loadCaptcha() } },
                                        ) { Text("重试") }
                                    }
                                }
                                IconButton(
                                    shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
                                    enabled = !captchaLoading,
                                    onClick = { scope.launch { loadCaptcha() } },
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "换一张")
                                }
                            }
                        }
                    }
                    LoginActionRow(
                        field = { fieldModifier ->
                            LoginField(
                                value = smsInput,
                                onValueChange = { smsInput = it.filter(Char::isDigit).take(8) },
                                label = "短信码",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = fieldModifier.semantics { contentType = ContentType.SmsOtpCode },
                            )
                        },
                    ) {
                        FilledTonalButton(
                            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                            modifier = Modifier.widthIn(min = LoginActionWidth).heightIn(min = AppLayout.ActionHeight),
                            enabled = countdown == 0 && !sending && !captchaLoading &&
                                phone.length == 11 && captchaInput.isNotBlank(),
                            onClick = {
                                if (sending || captchaLoading || countdown > 0) return@FilledTonalButton
                                sending = true
                                scope.launch {
                                    try {
                                        error = null
                                        when (val res = vm.sendSmsCode(phone, captchaInput, seed)) {
                                            is Res.Ok -> countdown = 60
                                            is Res.Fail -> {
                                                error = res.msg
                                                // The captcha is single-use on the server side;
                                                // refresh it so the retry is not guaranteed to fail.
                                                captchaInput = ""
                                                loadCaptcha()
                                            }
                                        }
                                    } finally { sending = false }
                                }
                            },
                        ) {
                            Text(if (countdown > 0) "${countdown}s" else if (sending) "发送中" else "获取验证码")
                        }
                    }
                    error?.let { message ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Button(
                        enabled = phone.length == 11 && smsInput.isNotBlank() && !submitting,
                        onClick = {
                            if (submitting) return@Button
                            submitting = true
                            scope.launch {
                                try {
                                    error = null
                                    // On success MainViewModel flips `loggedIn`, which swaps
                                    // the root composable over to the main UI.
                                    when (val res = vm.login(phone, smsInput)) {
                                        is Res.Ok -> Unit
                                        is Res.Fail -> {
                                            error = res.msg.ifBlank { "登录失败，请稍后重试" }
                                            vm.notify(error.orEmpty())
                                        }
                                    }
                                } finally { submitting = false }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .heightIn(min = 56.dp),
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    ) {
                        if (submitting) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            if (submitting) "登录中…" else "登录",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (!submitting) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "登录即表示同意\n《慧生活798 服务协议》与《隐私政策》",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                TextButton(
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                    onClick = { vm.checkAppUpdate(manual = true) },
                    enabled = !checkingUpdate,
                    modifier = Modifier.heightIn(min = AppLayout.ActionHeight),
                ) { Text(if (checkingUpdate) "正在检查更新…" else "检查客户端更新") }
            }
        }
    }
}

@Composable
private fun LoginBrand() {
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = accent,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 32.dp, bottomEnd = 64.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width, size.height * 0.22f)
                for (radius in listOf(48.dp, 72.dp, 96.dp)) {
                    drawCircle(accent.copy(alpha = 0.12f), radius.toPx(), center, style = Stroke(2.dp.toPx()))
                }
                drawCircle(accent.copy(alpha = 0.07f), 48.dp.toPx(), center)
            }
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_artwork),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(14.dp)),
                )
                Text("慧生活 798", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("直饮水 · 热水淋浴 · 智能洗衣", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val LoginActionWidth = 144.dp

@Composable
private fun LoginActionRow(
    field: @Composable (Modifier) -> Unit,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumInlineWidth = 96.dp * LocalDensity.current.fontScale
        if (maxWidth < minimumInlineWidth + LoginActionWidth + 8.dp) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field(Modifier.fillMaxWidth())
                action()
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                field(Modifier.weight(1f))
                action()
            }
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

private fun newSeed(): Double = Random.nextDouble(0.0, 1.0)
