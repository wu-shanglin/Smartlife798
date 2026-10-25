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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            error = "未授予相机权限，可点右上角「手动输入」或到系统设置中开启"
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraPreview(onQr = { raw -> handle(raw) }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                    Text("需要相机权限才能扫码", color = Color.White)
                    Button(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授予权限") }
                    if (deniedOnce) {
                        TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = { openAppSettings() }) {
                            Text("去系统设置开启相机权限", color = Color.White)
                        }
                    }
                    TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = onManualInput) {
                        Text("改为手动输入编号", color = Color.White)
                    }
                }
            }
        }

        // Viewfinder frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = Color.Transparent,
                shape = MaterialTheme.shapes.extraLarge,
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.72f)),
                modifier = Modifier.size(260.dp),
            ) {}
        }

        // A single, secondary action in the top-right. The old "扫码 / 输入编号"
        // segmented control competed with the camera; manual input is now its
        // own page reached from here.
        Surface(
            color = Color.Black.copy(alpha = 0.42f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 10.dp)
                .sharedCard(CardOrigin.SCAN_MANUAL),
        ) {
            TextButton(shapes = androidx.compose.material3.ButtonDefaults.shapes(), onClick = onManualInput) {
                Text("手动输入", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        IconButton(

            shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 4.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将设备二维码放入框内",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        status ?: "识别成功后自动进入设备详情并开始使用；编号贴纸可点右上角手动输入",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (resolving) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearWavyProgressIndicator(modifier = Modifier.width(56.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("处理中…", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            ErrorBanner(error) {
                error = null
                // Dismissing the banner is an explicit "try this code again".
                failedRaw = null
            }
            Spacer(Modifier.height(6.dp))
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
                .padding(14.dp),
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
private fun CameraPreview(onQr: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39)
                .build(),
        )
    }
    // The analyzer runs on a background thread, so the de-duplication state is
    // atomics rather than Compose state.
    val lastValue = remember { AtomicReference("") }
    val lastAt = remember { AtomicLong(0L) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { AtomicReference<ImageAnalysis?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // CameraX binds to the *Activity* lifecycle, so leaving the scan route
            // would keep the sensor streaming until the Activity stops.
            runCatching {
                analysisRef.get()?.clearAnalyzer()
                providerRef.get()?.unbindAll()
            }
            providerRef.set(null)
            analysisRef.set(null)
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // TextureView participates in Compose translation and clipping. The default
                // SurfaceView can lag behind the scanner page during predictive back.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerRef.set(provider)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysisRef.set(analysis)
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    process(proxy, scanner) { value ->
                        val now = System.currentTimeMillis()
                        val previous = lastValue.get()
                        if (value.isNotBlank() && (value != previous || now - lastAt.get() > 2500)) {
                            lastValue.set(value)
                            lastAt.set(now)
                            previewView.post { onQr(value) }
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

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
    scanner.process(image)
        .addOnSuccessListener { codes ->
            codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let(onFound)
        }
        .addOnCompleteListener { proxy.close() }
}
