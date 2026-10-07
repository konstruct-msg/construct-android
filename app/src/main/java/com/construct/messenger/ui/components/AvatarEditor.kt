package com.construct.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTSpace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The square crop for a new avatar. **Canon:** iOS `ImageCropView` — the picture on black, a
 * square of 0.82 of the screen's short side with the rest dimmed, a white border and rule-of-thirds
 * lines; pinch and drag, never smaller than the square, never leaving a gap in it. Cancel and
 * "Use Photo" at the bottom. [onConfirm] gets the part inside the square.
 */
@Composable
fun AvatarCropDialog(uri: Uri, onConfirm: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Coil applies the EXIF orientation; a software bitmap so the crop can read its pixels.
    val picture by produceState<Bitmap?>(null, uri) {
        val request = ImageRequest.Builder(context).data(uri).size(CROP_SOURCE_SIDE).allowHardware(false).build()
        value = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        if (value == null) onCancel()
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(CTColor.mediaGround)) {
            val bitmap = picture
            if (bitmap == null) {
                CircularProgressIndicator(color = CTColor.onMedia, modifier = Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val crop = min(boxW, boxH) * CROP_FRACTION
            // Fitted inside the square at scale 1; the fill scale is the least allowed.
            val fit = min(crop / bitmap.width, crop / bitmap.height)
            val fittedW = bitmap.width * fit
            val fittedH = bitmap.height * fit
            val minScale = max(crop / fittedW, crop / fittedH)
            var scale by remember(bitmap) { mutableStateOf(minScale) }
            var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
            fun clamp(o: Offset, s: Float): Offset {
                val maxX = max(0f, (fittedW * s - crop) / 2)
                val maxY = max(0f, (fittedH * s - crop) / 2)
                return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
            }
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(minScale, minScale * MAX_ZOOM)
                            offset = clamp(offset + pan, scale)
                        }
                    },
            ) {
                val w = fittedW * scale
                val h = fittedH * scale
                drawImage(
                    image = image,
                    dstOffset = IntOffset(((boxW - w) / 2 + offset.x).roundToInt(), ((boxH - h) / 2 + offset.y).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                )
                val left = (boxW - crop) / 2
                val top = (boxH - crop) / 2
                val scrim = CTColor.mediaGround.copy(alpha = 0.55f)
                drawRect(scrim, Offset.Zero, Size(boxW, top))
                drawRect(scrim, Offset(0f, top + crop), Size(boxW, boxH - top - crop))
                drawRect(scrim, Offset(0f, top), Size(left, crop))
                drawRect(scrim, Offset(left + crop, top), Size(boxW - left - crop, crop))
                drawRect(CTColor.onMedia.copy(alpha = 0.9f), Offset(left, top), Size(crop, crop), style = Stroke(1.5.dp.toPx()))
                val thin = 0.5.dp.toPx()
                val grid = CTColor.onMedia.copy(alpha = 0.25f)
                for (i in 1..2) {
                    val d = crop * i / 3
                    drawLine(grid, Offset(left + d, top), Offset(left + d, top + crop), thin)
                    drawLine(grid, Offset(left, top + d), Offset(left + crop, top + d), thin)
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = CTSpace.xl, end = CTSpace.xl, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(CTSpace.s),
            ) {
                CropButton(stringResource(R.string.action_cancel), CTColor.mediaControl, CTColor.onMedia, onCancel)
                CropButton(stringResource(R.string.crop_use_photo), CTColor.mediaControlOn, CTColor.onMediaControlOn) {
                    // The square in the picture's own pixels: iOS `cropImage`.
                    val w = fittedW * scale
                    val h = fittedH * scale
                    val perPixel = bitmap.width / w
                    val x = (((w - crop) / 2 - offset.x) * perPixel).roundToInt().coerceIn(0, bitmap.width - 1)
                    val y = (((h - crop) / 2 - offset.y) * perPixel).roundToInt().coerceIn(0, bitmap.height - 1)
                    val side = (crop * perPixel).roundToInt().coerceAtMost(min(bitmap.width - x, bitmap.height - y)).coerceAtLeast(1)
                    onConfirm(Bitmap.createBitmap(bitmap, x, y, side, side))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CropButton(label: String, fill: Color, text: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, style = CTFont.ui(17, FontWeight.SemiBold))
    }
}

/**
 * Our avatar, whole. **Canon:** iOS `AvatarViewerSheet` — the picture fitted on black, "Close"
 * and "Change Photo" above it.
 */
@Composable
fun AvatarViewerDialog(jpeg: ByteArray, onChange: () -> Unit, onDismiss: () -> Unit) {
    val image = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(CTColor.mediaGround)) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(CTSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = CTColor.onMedia)
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = onChange) {
                    Text(stringResource(R.string.change_avatar), color = CTColor.onMedia, style = CTFont.ui(17))
                }
            }
        }
    }
}

/** Decoded no larger than this before cropping: four times the 512 px it ends as, for zoom. */
private const val CROP_SOURCE_SIDE = 2048
private const val CROP_FRACTION = 0.82f
private const val MAX_ZOOM = 8f
