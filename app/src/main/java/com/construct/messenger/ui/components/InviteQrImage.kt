package com.construct.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.invite.InviteQr
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import kotlin.math.ceil
import kotlin.math.floor

/**
 * An invite QR on a white card with the logo in the middle — dark modules on white whatever the
 * theme, because scanners are tuned for that contrast.
 *
 * **Canon:** iOS `QRCodeGenerator` + `ContactQRCodeView.qrBlock`: the code [size] square, 20 of
 * white around it, card radius, a `noise` hairline. The logo covers 22 % of the code on a white
 * patch snapped outward to whole modules, so no module is cut in half; level H (30 % recovery)
 * is what lets the code survive it.
 */
@Composable
fun InviteQrImage(
    payload: String,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    // One module per pixel, scaled up without smoothing: sharp edges at any size.
    val bitmap = remember(payload) {
        val matrix = InviteQr.matrix(payload, 0)
        val patch = logoPatch(matrix.width)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    val dark = matrix[x, y] && !(x in patch && y in patch)
                    setPixel(x, y, if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.asImageBitmap()
    }
    val card = RoundedCornerShape(CornerRadius.small)
    Box(
        modifier = modifier
            .clip(card)
            .background(CTColor.qrPaper)
            .border(1.dp, CTColor.noise, card)
            .padding(QR_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size),
        )
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            tint = CTColor.qrInk,
            modifier = Modifier
                .size(size * LOGO_FRACTION)
                // iOS draws the light-trait logo: black easing to graphite across the mark.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.linearGradient(listOf(CTColor.qrInk, CTColor.qrInkSoft)), blendMode = BlendMode.SrcIn)
                },
        )
    }
}

/** The modules under the logo and its 1 % margin, widened to whole modules on every side. */
private fun logoPatch(modules: Int): IntRange {
    val edge = modules * LOGO_FRACTION * (1 + 2 * LOGO_PADDING_FRACTION)
    val start = floor((modules - edge) / 2).toInt()
    val end = ceil((modules + edge) / 2).toInt()
    return start until end
}

private val QR_PADDING = 20.dp
private const val LOGO_FRACTION = 0.22f
private const val LOGO_PADDING_FRACTION = 0.01f

/** iOS `QRCodeSize.standard(in:)`: the code is 80 % of the width, at most 350; the card adds 20 a side. */
fun inviteQrSize(containerWidth: Dp): Dp = minOf(containerWidth * 0.8f, 350.dp)
