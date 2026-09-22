package dev.aegis.remote.android.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.aegis.remote.android.R
import dev.aegis.remote.android.home.LOCAL_PAIRING_LOG_TAG
import dev.aegis.remote.android.pairing.PairingQrTransport
import dev.aegis.remote.android.ui.theme.OnyxColors
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun LiveQrScanner(
    onQrCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var requestCount by remember { mutableIntStateOf(0) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            requestCount += 1
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (permissionGranted) {
        CameraQrPreview(onQrCode = onQrCode, modifier = modifier)
    } else {
        CameraPermissionCard(
            previouslyDenied = requestCount > 0,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = modifier,
        )
    }
}

@Composable
private fun CameraPermissionCard(
    previouslyDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OnyxColors.ContainerLow)
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(20.dp))
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .background(OnyxColors.ContainerHigh, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = OnyxColors.Primary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.camera_permission_title), color = OnyxColors.OnSurfaceStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            if (previouslyDenied) {
                stringResource(R.string.camera_permission_denied_message)
            } else {
                stringResource(R.string.camera_permission_message)
            },
            color = OnyxColors.OnSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.camera_allow), color = OnyxColors.OnSurfaceStrong)
        }
    }
}

@Composable
private fun CameraQrPreview(
    onQrCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnQrCode by rememberUpdatedState(onQrCode)
    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val disposed = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var analyzer: MlKitQrFrameAnalyzer? = null

        providerFuture.addListener(
            {
                if (disposed.get()) return@addListener
                runCatching {
                    val cameraProvider = providerFuture.get().also { provider = it }
                    val preview =
                        Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                    val imageAnalysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                ResolutionSelector
                                    .Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(1920, 1080),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        ),
                                    ).build(),
                            ).build()
                            .also { useCase -> analysis = useCase }
                    cameraProvider.unbindAll()
                    val boundCamera =
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    camera = boundCamera
                    analyzer =
                        MlKitQrFrameAnalyzer(boundCamera, mainExecutor) { payload -> latestOnQrCode(payload) }
                            .also { imageAnalysis.setAnalyzer(cameraExecutor, it) }
                    previewView.post {
                        if (disposed.get() || previewView.width <= 0 || previewView.height <= 0) return@post
                        val center =
                            previewView.meteringPointFactory.createPoint(
                                previewView.width / 2f,
                                previewView.height / 2f,
                            )
                        val focusAction =
                            FocusMeteringAction
                                .Builder(
                                    center,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                ).setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        boundCamera.cameraControl.startFocusAndMetering(focusAction)
                    }
                }.onFailure { error ->
                    cameraError = error.message ?: context.getString(R.string.camera_start_error)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            analyzer?.close()
            provider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OnyxColors.ContainerLowest)
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(20.dp))
                .semantics { contentDescription = context.getString(R.string.camera_viewfinder_description) },
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .border(2.dp, OnyxColors.Primary, RoundedCornerShape(12.dp)),
        )
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = OnyxColors.Primary)
                Text(
                    stringResource(R.string.camera_point_at_qr),
                    color = OnyxColors.OnSurfaceStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        camera?.cameraControl?.enableTorch(torchEnabled)
                    },
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(999.dp)),
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Outlined.FlashOff else Icons.Outlined.FlashOn,
                        contentDescription =
                            stringResource(
                                if (torchEnabled) R.string.camera_torch_off else R.string.camera_torch_on,
                            ),
                        tint = OnyxColors.OnSurfaceStrong,
                    )
                }
            }
        }
        cameraError?.let { error ->
            Text(
                text = error,
                color = OnyxColors.Error,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
            )
        }
        if (cameraError == null) {
            Text(
                text = stringResource(R.string.camera_detection_hint),
                color = OnyxColors.OnSurfaceStrong,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private class MlKitQrFrameAnalyzer(
    camera: Camera,
    private val mainExecutor: java.util.concurrent.Executor,
    private val onQrCode: (String) -> Unit,
) : ImageAnalysis.Analyzer,
    AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val scanner: BarcodeScanner

    init {
        val maxZoomRatio =
            camera.cameraInfo.zoomState.value
                ?.maxZoomRatio ?: 1f
        val zoomOptions =
            ZoomSuggestionOptions
                .Builder { suggestedRatio ->
                    if (delivered.get()) {
                        false
                    } else {
                        camera.cameraControl.setZoomRatio(suggestedRatio)
                        true
                    }
                }.setMaxSupportedZoomRatio(maxZoomRatio)
                .build()
        val scannerOptions =
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .setZoomSuggestionOptions(zoomOptions)
                .build()
        scanner = BarcodeScanning.getClient(scannerOptions)
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        if (delivered.get() || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            processing.set(false)
            image.close()
            return
        }
        scanner
            .process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { barcodes ->
                val payload =
                    barcodes
                        .asSequence()
                        .filter { it.format == Barcode.FORMAT_QR_CODE }
                        .mapNotNull { it.completePairingPayload() }
                        .firstOrNull()
                if (payload != null && delivered.compareAndSet(false, true)) {
                    Log.i(LOCAL_PAIRING_LOG_TAG, "Accepted complete pairing QR (${payload.length} chars)")
                    mainExecutor.execute { onQrCode(payload) }
                }
            }.addOnCompleteListener {
                processing.set(false)
                image.close()
            }
    }

    override fun close() {
        delivered.set(true)
        scanner.close()
    }
}

private fun Barcode.completePairingPayload(): String? {
    val fromValue = rawValue?.trim().orEmpty()
    val fromBytes =
        rawBytes
            ?.let { bytes -> runCatching { String(bytes, Charsets.ISO_8859_1).trim() }.getOrDefault("") }
            .orEmpty()
    return listOf(fromValue, fromBytes).firstOrNull(::isCompletePairingPayload)
}

private fun isCompletePairingPayload(raw: String): Boolean {
    if (raw.startsWith("AEGIS3:")) {
        val decoded = runCatching { PairingQrTransport.decode(raw) }
        if (decoded.isFailure) {
            Log.d(
                LOCAL_PAIRING_LOG_TAG,
                "Ignoring incomplete AEGIS3 QR (${raw.length} chars): ${decoded.exceptionOrNull()?.message}",
            )
            return false
        }
        return decoded.getOrThrow().contains("\"pairingUrl\"")
    }
    return raw.startsWith("{") && raw.contains("\"pairingUrl\"")
}
