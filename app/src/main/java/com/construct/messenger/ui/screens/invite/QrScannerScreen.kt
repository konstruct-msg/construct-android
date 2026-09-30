package com.construct.messenger.ui.screens.invite

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.R
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.invite.InviteQr
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.theme.CTColor
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

    // iOS `QRScannerView`: the camera fills the screen, "Cancel" over it, a dark card at the
    // foot with the title, the hint, and the paste button.
    var notAnInvite by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var emptyClipboard by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            ScannerViewport(
                onNotAnInvite = { notAnInvite = true },
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
        Text(
            text = stringResource(R.string.action_cancel),
            style = TextStyle(fontSize = 17.sp),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = Spacing.small, vertical = Spacing.small)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNavigateBack)
                .padding(horizontal = Spacing.small, vertical = Spacing.small),
        )
        // A link that arrived as text goes in here and takes the same path as a scanned one —
        // Synaps redeems it and says how it went.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 60.dp)
                .padding(horizontal = Spacing.medium)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_qr_code),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                text = stringResource(if (notAnInvite) R.string.invalid_qr_code else R.string.scan_hint),
                style = TextStyle(fontSize = 15.sp),
                color = if (notAnInvite) CTColor.danger else Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable {
                        val text = clipboard.getText()?.text?.trim().orEmpty()
                        if (text.isEmpty()) {
                            emptyClipboard = true
                        } else {
                            viewModel.deliver(text)
                            onScanned()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.paste_invite_link), style = TextStyle(fontSize = 15.sp), color = Color.White)
            }
            if (emptyClipboard) {
                Text(
                    text = stringResource(R.string.clipboard_no_valid_invite),
                    style = TextStyle(fontSize = 13.sp),
                    color = CTColor.danger,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ScannerViewport(onNotAnInvite: () -> Unit, onInvite: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
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
                        onNotAnInvite()
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanFrame(modifier = Modifier.fillMaxSize())
    }
}

/**
 * iOS `ScannerDimOverlay` + `ScannerCornerBrackets`: the picture dimmed to 60 % outside a
 * rounded square — 72 % of the width, at most 260, 30 above centre — and four brackets on it.
 * iOS strokes them in `AppBrand.button`, which is a near-black grey since the button colour
 * changed; here they stay accent so they can be seen.
 */
@Composable
private fun ScanFrame(modifier: Modifier) {
    val accent = CTColor.accent
    Canvas(modifier = modifier) {
        val side = minOf(size.width * 0.72f, 260.dp.toPx())
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2 - 30.dp.toPx()
        val cutout = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(left, top, left + side, top + side, CornerRadius(12.dp.toPx())))
        }
        drawPath(cutout, Color.Black.copy(alpha = 0.6f))
        val len = 24.dp.toPx()
        val r = 4.dp.toPx()
        val right = left + side
        val bottom = top + side
        val brackets = Path().apply {
            moveTo(left, top + len); lineTo(left, top + r); quadraticBezierTo(left, top, left + r, top); lineTo(left + len, top)
            moveTo(right - len, top); lineTo(right - r, top); quadraticBezierTo(right, top, right, top + r); lineTo(right, top + len)
            moveTo(left, bottom - len); lineTo(left, bottom - r); quadraticBezierTo(left, bottom, left + r, bottom); lineTo(left + len, bottom)
            moveTo(right - len, bottom); lineTo(right - r, bottom); quadraticBezierTo(right, bottom, right, bottom - r); lineTo(right, bottom - len)
        }
        drawPath(brackets, accent, style = Stroke(width = 3.dp.toPx()))
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
