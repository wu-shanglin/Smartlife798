@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.smartlife798.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
    val phone = remember { TextFieldState() }
    val captchaInput = remember { TextFieldState() }
    val smsInput = remember { TextFieldState() }
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
        captchaInput.clearText()
        try {
            val s = newSeed()
            seed = s
            val ts = System.currentTimeMillis().toString()
            val bytes = vm.requestCaptcha(s.toString(), ts)
            captchaBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } finally { captchaLoading = false }
    }

    LaunchedEffect(Unit) { loadCaptcha() }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    val fieldShape = MaterialTheme.shapes.medium
    val fieldLabelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true)
    val sideAction = Modifier.width(112.dp).heightIn(min = AppLayout.ActionHeight)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_artwork),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(fieldShape)
                        .background(colorResource(R.color.launcher_background))
                        .scale(1.35f),
                )
                Text("慧生活 798", style = MaterialTheme.typography.titleMedium)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "手机号登录",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    state = phone,
                    inputTransformation = PhoneInput,
                    label = { Text("手机号") },
                    labelPosition = fieldLabelPosition,
                    shape = fieldShape,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumber },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        state = captchaInput,
                        inputTransformation = CaptchaInput,
                        label = { Text("图形验证码") },
                        labelPosition = fieldLabelPosition,
                        shape = fieldShape,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.weight(1f),
                    )
                    CaptchaAction(
                        bitmap = captchaBitmap,
                        loading = captchaLoading,
                        enabled = !sending,
                        onRefresh = { if (!sending) scope.launch { loadCaptcha() } },
                        modifier = sideAction,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        state = smsInput,
                        inputTransformation = SmsInput,
                        label = { Text("短信验证码") },
                        labelPosition = fieldLabelPosition,
                        shape = fieldShape,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).semantics { contentType = ContentType.SmsOtpCode },
                    )
                    TextButton(
                        shapes = ButtonDefaults.shapes(shape = fieldShape),
                        modifier = sideAction,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        enabled = countdown == 0 && !sending && !captchaLoading && captchaBitmap != null &&
                            phone.text.length == 11 && captchaInput.text.isNotBlank(),
                        onClick = {
                            val number = phone.text.toString()
                            val code = captchaInput.text.toString()
                            if (sending || captchaLoading || captchaBitmap == null ||
                                countdown > 0 || number.length != 11 || code.isBlank()) {
                                return@TextButton
                            }
                            val challengeSeed = seed
                            sending = true
                            scope.launch {
                                try {
                                    error = null
                                    when (val res = vm.sendSmsCode(number, code, challengeSeed)) {
                                        is Res.Ok -> countdown = 60
                                        is Res.Fail -> {
                                            error = res.msg
                                            // The captcha is single-use on the server side;
                                            // refresh it so the retry is not guaranteed to fail.
                                            loadCaptcha()
                                        }
                                    }
                                } finally { sending = false }
                            }
                        },
                    ) {
                        Text(
                            if (countdown > 0) "${countdown}s" else if (sending) "发送中" else "获取验证码",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                Button(
                    enabled = phone.text.length == 11 && smsInput.text.isNotBlank() && !submitting,
                    onClick = {
                        val number = phone.text.toString()
                        val code = smsInput.text.toString()
                        if (submitting || number.length != 11 || code.isBlank()) return@Button
                        submitting = true
                        scope.launch {
                            try {
                                error = null
                                // On success MainViewModel flips `loggedIn`, which swaps
                                // the root composable over to the main UI.
                                when (val res = vm.login(number, code)) {
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
                        .heightIn(min = AppLayout.ActionHeight),
                    shapes = ButtonDefaults.shapes(shape = fieldShape),
                ) {
                    if (submitting) {
                        LoadingIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (submitting) "登录中…" else "登录")
                }
            }

            if (BuildConfig.RELEASE_UPDATES_ENABLED) {
                TextButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = { vm.checkAppUpdate(manual = true) },
                    enabled = !checkingUpdate,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .heightIn(min = 48.dp),
                ) { Text(if (checkingUpdate) "正在检查更新…" else "检查客户端更新") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    val footerColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("登录即表示同意《慧生活798 服务协议》与《隐私政策》", color = footerColor)
                    Text("独立客户端，连接慧生活 798 服务端", color = footerColor)
                }
            }
        }
    }
}

@Composable
private fun CaptchaAction(
    bitmap: android.graphics.Bitmap?,
    loading: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClickLabel = "更换图形验证码", onClick = onRefresh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> LoadingIndicator(modifier = Modifier.size(18.dp))
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "图形验证码",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
            )
            else -> Text(
                "重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun newSeed(): Double = Random.nextDouble(0.0, 1.0)

private val PhoneInput = InputTransformation.byValue { _, proposed -> proposed.filter(Char::isDigit).take(11) }
private val CaptchaInput = InputTransformation.byValue { _, proposed -> proposed.take(6) }
private val SmsInput = InputTransformation.byValue { _, proposed -> proposed.filter(Char::isDigit).take(8) }
