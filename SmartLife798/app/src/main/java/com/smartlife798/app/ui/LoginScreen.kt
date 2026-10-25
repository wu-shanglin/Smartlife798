@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Api
import com.smartlife798.app.core.Res
import com.smartlife798.app.ui.components.HintBanner
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun LoginScreen(vm: MainViewModel) {
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
        captchaLoading = true
        val s = newSeed()
        seed = s
        val ts = System.currentTimeMillis().toString()
        val bytes = vm.requestCaptcha(s.toString(), ts)
        captchaBitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        captchaLoading = false
    }

    LaunchedEffect(Unit) { loadCaptcha() }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                    Text(
                        "慧生活 798",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "直饮水 · 热水淋浴 · 智能洗衣",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "全新设计 · 同一账号 · 真实服务端",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("手机号登录", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = phone,
                        // Filter first, then clamp: checking the raw length dropped
                        // pasted numbers containing spaces entirely.
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        label = { Text("手机号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = captchaInput,
                            onValueChange = { captchaInput = it.take(6) },
                            label = { Text("验证码") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 108.dp, height = 54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = captchaBitmap
                            when {
                                captchaLoading -> LoadingIndicator(
                                    modifier = Modifier.size(18.dp),
                                )
                                bmp != null -> Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "图形验证码",
                                    modifier = Modifier.size(width = 100.dp, height = 46.dp),
                                )
                                else -> TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = { scope.launch { loadCaptcha() } }) { Text("重试") }
                            }
                        }
                        IconButton(shapes = androidx.compose.material3.IconButtonDefaults.shapes(), onClick = { scope.launch { loadCaptcha() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "换一张")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = smsInput,
                            onValueChange = { smsInput = it.filter(Char::isDigit).take(8) },
                            label = { Text("短信验证码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        TextButton(
                            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                            enabled = countdown == 0 && !sending && phone.length == 11 && captchaInput.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    sending = true
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
                                    sending = false
                                }
                            },
                        ) {
                            Text(if (countdown > 0) "${countdown}s" else if (sending) "发送中" else "获取验证码")
                        }
                    }
                    Button(
                        enabled = phone.length == 11 && smsInput.isNotBlank() && !submitting,
                        onClick = {
                            scope.launch {
                                submitting = true
                                error = null
                                // On success MainViewModel flips `loggedIn`, which swaps
                                // the root composable over to the main UI.
                                when (val res = vm.login(phone, smsInput)) {
                                    is Res.Ok -> Unit
                                    is Res.Fail -> error = res.msg
                                }
                                submitting = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                    ) {
                        if (submitting) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("登录")
                    }
                    if (error != null) {
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            HintBanner(
                "本客户端直接连接「慧生活798」官方服务端（${Api.BASE_URL}），登录、设备与计费数据均来自真实接口，" +
                    "不会在本地伪造业务状态。",
            )

            TextButton(

                shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                onClick = { vm.startPreview() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "仅预览界面（演示数据，不连接服务端）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "登录即表示同意《慧生活798 服务协议》与《隐私政策》",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun newSeed(): Double = Random.nextDouble(0.0, 1.0)
