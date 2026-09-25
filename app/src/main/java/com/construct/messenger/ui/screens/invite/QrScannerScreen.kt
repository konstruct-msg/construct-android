package com.construct.messenger.ui.screens.invite

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.R
import com.construct.messenger.invite.InviteQr
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.QrScannerViewModel
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans an invite QR and hands the link to [onScanned].
 *
 * **Canon:** iOS `QRScannerView`. One decode and it stops; a code that is not an invite says so
 * and scanning goes on (iOS closes the sheet instead — keeping the camera up spares a second
 * tap for the right code). CameraX + ZXing, no Google Play Services.
 */
@Composable
fun QrScannerScreen(
    onNavigateBack: () -> Unit,
    onScanned: () -> Unit,
    viewModel: QrScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.scan_qr_code),
            showBack = true,
            onBack = onNavigateBack,
        )
        if (granted) {
            ScannerViewport(
                onInvite = { link ->
                    viewModel.deliver(link)
                    onScanned()
                },
            )
        } else {
            PermissionPrompt(
                // After a refusal the system dialog may not come back ("don't ask again");
                // from then on only the app's settings page can grant it.
                permanentlyDenied = askedOnce && !context.shouldShowCameraRationale(),
                onGrant = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }
    }
}

@Composable
private fun ScannerViewport(onInvite: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var notAnInvite by remember { mutableStateOf(false) }
    val done = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                val scan = image.use { decodeQr(it) }
                if (scan == null || done.get()) return@setAnalyzer
                val link = InviteQr.linkFromScan(scan.first, scan.second)
                mainExecutor.execute {
                    if (link == null) {
                        notAnInvite = true
                    } else if (done.compareAndSet(false, true)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        provider.unbindAll()
                        onInvite(link)
                    }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { Log.e(TAG, "camera bind failed", it) }
        }, mainExecutor)
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .size(260.dp)
                .border(2.dp, CTColor.accent, RoundedCornerShape(12.dp)),
        )
        Text(
            text = stringResource(if (notAnInvite) R.string.invalid_qr_code else R.string.scan_hint),
            style = ctRegular(13),
            color = if (notAnInvite) CTColor.danger else CTColor.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CTColor.bg.copy(alpha = 0.7f))
                .padding(horizontal = CTLayout.edgePad, vertical = Spacing.medium),
        )
    }
}

@Composable
private fun PermissionPrompt(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.large, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_needed),
            style = ctRegular(14),
            color = CTColor.textDim,
            textAlign = TextAlign.Center,
        )
        CTButton(
            label = stringResource(
                if (permanentlyDenied) R.string.camera_permission_settings else R.string.camera_permission_grant,
            ),
            onClick = if (permanentlyDenied) onOpenSettings else onGrant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Y plane → ZXing. Returns the text (read as ISO-8859-1) and any raw byte segments. */
private fun decodeQr(image: ImageProxy): Pair<String, List<ByteArray>?>? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(
        data, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
    )
    return try {
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS)
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as List<ByteArray>?
        result.text to segments
    } catch (_: NotFoundException) {
        null
    } catch (e: Exception) {
        // Checksum / format errors on a blurred frame: the next frame will do.
        null
    }
}

// ISO-8859-1 so a byte-mode CIv1 code decodes byte-for-byte; base64url text is ASCII either way.
private val DECODE_HINTS = mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1")

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.shouldShowCameraRationale(): Boolean =
    (this as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: false

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val TAG = "QrScanner"
