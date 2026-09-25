package com.construct.messenger.invite

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * What goes into an invite QR and how a scan is read back.
 *
 * **Canon:** iOS `ContactQRCodeView` / `QRCodeGenerator` / `QRScannerView.handleScannedCode`.
 * The code carries base64url(CIv1) as text — not the `konstruct://` link, and not raw bytes:
 * scanners surface text reliably, while byte-mode CIv1 often comes back without a string.
 * Byte-mode codes printed by older builds are still read, from the raw byte segment.
 */
object InviteQr {

    /** Module matrix for [payload]: level H (iOS), one-module quiet zone. Pure — rendering to
     * pixels is the UI's job. */
    fun matrix(payload: String, sizePx: Int): BitMatrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /**
     * Turns one decoded QR into the `konstruct://add?invite=…` link the deep-link path redeems,
     * or `null` if it is not an invite. Only the shape is checked here; signature, expiry and
     * reuse are the verifier's.
     *
     * @param text the decoded string (read as ISO-8859-1, so byte-mode data survives it).
     * @param byteSegments raw byte-mode segments, when the decoder kept them.
     */
    fun linkFromScan(text: String?, byteSegments: List<ByteArray>? = null): String? {
        byteSegments
            ?.fold(ByteArray(0)) { acc, seg -> acc + seg }
            ?.takeIf { it.isCompactBinary() }
            ?.let { return linkFor(base64UrlEncode(it)) }

        var value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        value = value
            .replaceFirst("https://https://", "https://")
            .replaceFirst("http://https://", "https://")

        // Byte-mode CIv1 whose bytes reached us as an ISO-8859-1 string.
        val latin1 = value.toByteArray(Charsets.ISO_8859_1)
        if (latin1.isCompactBinary()) return linkFor(base64UrlEncode(latin1))

        if (runCatching { InviteObject.fromRaw(value) }.isFailure) return null
        val param = InviteObject.extractInviteParam(value)
        return linkFor(param ?: value)
    }

    private fun linkFor(payload: String) = "${InviteConfig.DEEP_LINK_SCHEME}?invite=$payload"

    private fun ByteArray.isCompactBinary(): Boolean =
        size > InviteObject.BINARY_MAGIC.size &&
            copyOfRange(0, InviteObject.BINARY_MAGIC.size).contentEquals(InviteObject.BINARY_MAGIC) &&
            runCatching { InviteObject.decodeBinary(this) }.isSuccess
}
