@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.smartlife798.app.core.QrParser
import com.smartlife798.app.core.QrResolution
import com.smartlife798.app.core.Res
import com.smartlife798.app.core.resolveQr
import androidx.compose.material3.LinearWavyProgressIndicator
import com.smartlife798.app.vm.MainViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Composable
fun ScanScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
    onManualInput: () -> Unit,
    onOpenDevice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    var deniedOnce by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /**
     * A QR code held in front of the lens keeps producing the same payload, so a
     * failure must not be retried every frame: remember which payload failed and
     * only try again when the code changes or the user dismisses the banner.
     */
    var failedRaw by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            deniedOnce = true
            error = "未授予相机权限，可手动输入编号或在系统设置中开启。"
        }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun fail(message: String, raw: String? = null) {
        status = null
        error = message
        failedRaw = raw
        resolving = false
    }

    fun handle(raw: String) {
        if (resolving) return
        if (raw == failedRaw) return
        resolving = true
        error = null
        status = "正在识别…"
        scope.launch {
            val parsed = QrParser.parse(raw)
            if (parsed.type == -1 || parsed.id.isBlank()) {
                fail("无法识别的内容：${raw.take(60)}", raw)
                return@launch
            }
            status = "正在向服务端查询（${QrParser.qrTypeName(parsed.type)}）…"
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
                        QrResolution.NoDevice ->
                            fail("二维码有效，但服务端未返回设备信息（可能已被停用）", raw)
                        is QrResolution.OtherType ->
                            fail(
                                "该二维码类型为「${QrParser.qrTypeName(outcome.type)}」，" +
                                    "本客户端当前只支持设备类二维码",
                                raw,
                            )
                    }
                }
                is Res.Fail -> fail(res.msg, raw)
            }
        }
    }

    val overlay = Color.Black.copy(alpha = 0.76f)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(
                onQr = ::handle,
                enabled = !resolving,
                onError = { error = "相机初始化失败，请重新进入扫码页或手动输入" },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(overlay).padding(horizontal = AppLayout.Page),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
                    onClick = onClose,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    "扫一扫",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Surface(
                    color = Color.White.copy(alpha = 0.16f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.sharedCard(CardOrigin.SCAN_MANUAL),
                ) {
                    TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = onManualInput) {
                        Text("手动输入", color = Color.White)
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (granted) {
                    ScanFrame(Modifier.fillMaxSize())
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(AppLayout.Page),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                        Text("需要相机权限才能扫码", color = Color.White)
                        Button(
                            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                            onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        ) { Text("授予权限") }
                        if (deniedOnce) {
                            TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = ::openAppSettings) {
                                Text("去系统设置开启相机权限", color = Color.White)
                            }
                        }
                        TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = onManualInput) {
                            Text("改为手动输入编号", color = Color.White)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(overlay).padding(AppLayout.Page),
                verticalArrangement = Arrangement.spacedBy(AppLayout.Gap),
            ) {
                if (granted) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(AppLayout.Card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "将设备二维码放入框内",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                status ?: "识别后进入设备详情。也可手动输入贴纸上的编号。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (resolving) {
                                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                ErrorBanner(error) {
                    error = null
                    // Dismissing the banner is an explicit "try this code again".
                    failedRaw = null
                }
            }
        }
    }
}

@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = minOf(260.dp.toPx(), size.width - 2 * AppLayout.Page.toPx(), size.height - 24.dp.toPx())
        if (side <= 0f) return@Canvas
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val right = left + side
        val bottom = top + side
        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRect(Rect(left, top, right, bottom))
        }
        drawPath(mask, Color.Black.copy(alpha = 0.64f))

        val length = minOf(28.dp.toPx(), side / 3)
        val width = 3.dp.toPx()
        for (x in listOf(left, right)) {
            for (y in listOf(top, bottom)) {
                val corner = Offset(x, y)
                val endX = if (x == left) x + length else x - length
                val endY = if (y == top) y + length else y - length
                val horizontal = Offset(endX, y)
                val vertical = Offset(x, endY)
                val outline = width + 2.dp.toPx()
                drawLine(Color.Black, corner, horizontal, outline, StrokeCap.Round)
                drawLine(Color.Black, corner, vertical, outline, StrokeCap.Round)
                drawLine(Color.White, corner, horizontal, width, StrokeCap.Round)
                drawLine(Color.White, corner, vertical, width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppLayout.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
internal fun CameraPreview(onQr: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onError: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQr by rememberUpdatedState(onQr)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            // TextureView follows Compose clipping during predictive back.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(previewView, lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39)
                .build(),
        )
        val lastValue = AtomicReference("")
        val lastAt = AtomicLong(0L)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(executor) { proxy ->
            if (disposed.get() || !currentEnabled) {
                proxy.close()
            } else {
                process(proxy, scanner) { value ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!disposed.get() && value.isNotBlank() &&
                        (value != lastValue.get() || now - lastAt.get() > 2500)) {
                        lastValue.set(value)
                        lastAt.set(now)
                        previewView.post {
                            if (!disposed.get()) currentOnQr(value)
                        }
                    }
                }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // Both this listener and disposal run on Main: stale binds cannot race cleanup.
            if (!disposed.get()) {
                runCatching {
                    provider = future.get()
                    provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { currentOnError() }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            // Release only this page's use cases, never a newly opened scanner.
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }
}

// Close frames even after disposal/executor shutdown, without posting every frame to Main.
private val scanCallbackExecutor = Executor { it.run() }

/**
 * [ImageProxy.image] is still an opt-in CameraX API; without this annotation
 * `:app:lint` fails the build with UnsafeOptInUsageError.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun process(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onFound: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    val task = try {
        scanner.process(image)
    } catch (_: RuntimeException) {
        proxy.close()
        return
    }
    task
        .addOnSuccessListener(scanCallbackExecutor) { codes ->
            codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let(onFound)
        }
        .addOnCompleteListener(scanCallbackExecutor) { proxy.close() }
}
