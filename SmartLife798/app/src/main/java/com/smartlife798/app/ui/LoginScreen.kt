@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
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
import com.smartlife798.app.BuildConfig
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
    var captchaBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var captchaLoading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var smsError by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }

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
                .widthIn(max = 424.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            LoginHeader()
            Spacer(Modifier.height(24.dp))
            LoginField(
                value = phone,
                onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                label = "手机号",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.semantics { contentType = ContentType.PhoneNumber },
            )
            Spacer(Modifier.height(10.dp))
            CaptchaPreview(
                bitmap = captchaBitmap,
                loading = captchaLoading,
                onRefresh = { scope.launch { loadCaptcha() } },
            )
            LoginField(
                value = captchaInput,
                onValueChange = { captchaInput = it.take(6) },
                label = "图形验证码",
            )
            smsError?.let { LoginError(it) }
            TextButton(
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = countdown == 0 && !sending && !captchaLoading &&
                    phone.length == 11 && captchaInput.isNotBlank(),
                onClick = {
                    if (sending || captchaLoading || countdown > 0) return@TextButton
                    sending = true
                    scope.launch {
                        try {
                            smsError = null
                            when (val res = vm.sendSmsCode(phone, captchaInput, seed)) {
                                is Res.Ok -> countdown = 60
                                is Res.Fail -> {
                                    smsError = res.msg
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
                Text(
                    when {
                        countdown > 0 -> "$countdown 秒后重新获取"
                        sending -> "正在发送短信…"
                        else -> "获取短信验证码"
                    },
                )
            }
            LoginField(
                value = smsInput,
                onValueChange = { smsInput = it.filter(Char::isDigit).take(8) },
                label = "短信验证码",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.semantics { contentType = ContentType.SmsOtpCode },
            )
            loginError?.let { LoginError(it) }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = phone.length == 11 && smsInput.isNotBlank() && !submitting,
                onClick = {
                    if (submitting) return@Button
                    submitting = true
                    scope.launch {
                        try {
                            smsError = null
                            loginError = null
                            // On success MainViewModel flips `loggedIn`, which swaps
                            // the root composable over to the main UI.
                            when (val res = vm.login(phone, smsInput)) {
                                is Res.Ok -> Unit
                                is Res.Fail -> {
                                    val message = res.msg.ifBlank { "登录失败，请稍后重试" }
                                    loginError = message
                                    vm.notify(message)
                                }
                            }
                        } finally { submitting = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(14.dp)),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                if (submitting) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (submitting) "登录中…" else "登录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "登录即表示同意\n《慧生活798 服务协议》与《隐私政策》",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (BuildConfig.RELEASE_UPDATES_ENABLED) {
                    TextButton(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { vm.checkAppUpdate(manual = true) },
                        enabled = !checkingUpdate,
                        modifier = Modifier.heightIn(min = AppLayout.ActionHeight),
                    ) { Text(if (checkingUpdate) "正在检查更新…" else "检查客户端更新") }
                }
            }
        }
    }
}

@Composable
private fun LoginHeader() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(32.dp),
        )
        Text(
            "慧生活 798",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "手机号登录",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun CaptchaPreview(bitmap: Bitmap?, loading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> {
                LoadingIndicator(Modifier.size(20.dp))
                Text(
                    "正在加载图形验证码…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            bitmap != null -> {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(width = 116.dp, height = 40.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "图形验证码图片",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> Text(
                "图形验证码加载失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!loading) {
            TextButton(
                shapes = ButtonDefaults.shapes(),
                onClick = onRefresh,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (bitmap == null) "重试" else "换一张") }
        }
    }
}

@Composable
private fun LoginError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun newSeed(): Double = Random.nextDouble(0.0, 1.0)
