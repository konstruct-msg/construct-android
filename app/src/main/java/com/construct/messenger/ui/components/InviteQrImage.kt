package com.construct.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.invite.InviteQr

/**
 * An invite QR on a white card — dark modules on white whatever the theme, because scanners
 * are tuned for that contrast.
 *
 * **Canon:** iOS `QRCodeGenerator` + `QRCodeSize` (padding 20, corner radius 20). The iOS
 * logo in the centre is not drawn yet; level H leaves room to add it without changing the code.
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
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.asImageBitmap()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(QR_CORNER))
            .background(Color.White)
            .padding(QR_PADDING),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size - QR_PADDING * 2),
        )
    }
}

private val QR_PADDING = 20.dp
private val QR_CORNER = 20.dp

/** iOS `QRCodeSize.standard(in:)`: 80 % of the width, at most 350. */
fun inviteQrSize(containerWidth: Dp): Dp = minOf(containerWidth * 0.8f, 350.dp)
