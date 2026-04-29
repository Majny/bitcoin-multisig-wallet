package com.example.bitcoinwallet.feature.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.bitcoinwallet.core.ur.UrDescriptorDecoder
import com.example.bitcoinwallet.ui.theme.AccentTeal
import com.example.bitcoinwallet.ui.theme.DarkBackground
import com.example.bitcoinwallet.ui.theme.ErrorRed
import com.example.bitcoinwallet.ui.theme.TextPrimary
import com.example.bitcoinwallet.ui.theme.TextSecondary
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "UrQrScannerScreen"

/*
 * Animated multi-part UR QR scanner for Sparrow/Keystone/Passport airgap wallet
 * exports. Accumulates frames into a [URDecoder] fountain decoder and converts
 * the completed UR into a BIP-380 descriptor string via [UrDescriptorDecoder].
 *
 * Plain (non-UR) QR payloads are also accepted as-is so users can scan a
 * text-only descriptor QR.
 */
@Composable
fun UrQrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Decoder state that survives recompositions but not screen disposal.
    val urDecoder = remember { URDecoder() }
    val seenParts = remember { HashSet<String>() }
    val finished = remember { AtomicBoolean(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Scan QR code…") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                    if (!finished.get()) {
                                        val text = decodeQrFromProxy(imageProxy)
                                        if (text != null) {
                                            handleScannedText(
                                                text = text,
                                                urDecoder = urDecoder,
                                                seenParts = seenParts,
                                                finished = finished,
                                                onProgress = { pct, label ->
                                                    progress = pct
                                                    statusText = label
                                                },
                                                onDescriptor = { descriptor ->
                                                    if (finished.compareAndSet(false, true)) {
                                                        onResult(descriptor)
                                                    }
                                                },
                                                onError = { msg ->
                                                    errorText = msg
                                                }
                                            )
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalyzer
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to bind camera", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 72.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentTeal,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 14.sp
                )
                errorText?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = it, color = ErrorRed, fontSize = 12.sp)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission required",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Allow camera access in system settings to scan QR codes.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }
    }
}

private fun handleScannedText(
    text: String,
    urDecoder: URDecoder,
    seenParts: HashSet<String>,
    finished: AtomicBoolean,
    onProgress: (Int, String) -> Unit,
    onDescriptor: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (!text.startsWith("ur:", ignoreCase = true)) {
        // Plain text descriptor - accept as-is.
        if (finished.compareAndSet(false, true)) onDescriptor(text.trim())
        return
    }

    val normalized = text.lowercase()
    if (!seenParts.add(normalized)) return  // already fed this exact frame

    try {
        urDecoder.receivePart(normalized)
    } catch (e: Exception) {
        Log.w(TAG, "receivePart failed", e)
        onError("Invalid UR frame")
        return
    }

    val pct = (urDecoder.estimatedPercentComplete * 100).toInt().coerceIn(0, 100)
    onProgress(pct, "Reading QR… ${pct}%")

    val result = urDecoder.result ?: return
    if (result.type == ResultType.SUCCESS) {
        try {
            val descriptor = UrDescriptorDecoder.decode(result.ur)
            Log.d(TAG, "Decoded descriptor: ${descriptor.take(120)}…")
            onDescriptor(descriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render descriptor from UR", e)
            onError(e.message ?: "Failed to decode descriptor")
            finished.set(false)  // allow retry with fresh decoder? user will reopen screen
        }
    } else {
        onError(result.error ?: "UR decode failed")
    }
}

private fun decodeQrFromProxy(imageProxy: ImageProxy): String? {
    return try {
        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val data = ByteArray(yBuffer.remaining())
        yBuffer.get(data)

        val source = PlanarYUVLuminanceSource(
            data,
            yPlane.rowStride,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        QRCodeReader().decode(bitmap).text
    } catch (e: Exception) {
        null
    }
}
