package com.construct.messenger.invite

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteQrTest {

    private val invite = InviteObject(
        v = 5,
        jti = UUID.randomUUID().toString(),
        uuid = UUID.randomUUID().toString(),
        deviceId = "0123456789abcdef0123456789abcdef",
        server = "konstruct.cc",
        ephKey = "",
        ts = System.currentTimeMillis() / 1000,
        sig = b64encode(ByteArray(64) { it.toByte() }),
        un = null,
        ttl = InviteConfig.QR_TTL_SECONDS,
    )
    private val payload = invite.toBase64Url()
    private val link = "konstruct://add?invite=$payload"

    @Test
    fun `a rendered code scans back to the link`() {
        // Many invites, not one: the jti is random, and a payload-dependent failure would
        // otherwise pass or fail by luck.
        repeat(50) {
            val payload = invite.copy(jti = UUID.randomUUID().toString()).toBase64Url()
            val matrix = InviteQr.matrix(payload, 0)
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else -1
            }
            val result = QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))),
                // A synthetic, noise-free image: tell the decoder so, rather than make it guess
                // the geometry. The camera path decodes without this hint.
                mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1", DecodeHintType.PURE_BARCODE to true),
            )
            @Suppress("UNCHECKED_CAST")
            val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as List<ByteArray>?

            assertEquals("konstruct://add?invite=$payload", InviteQr.linkFromScan(result.text, segments))
        }
    }

    @Test
    fun `bare base64url payload becomes a link`() {
        assertEquals(link, InviteQr.linkFromScan(payload))
    }

    @Test
    fun `a link is kept as the same link`() {
        assertEquals(link, InviteQr.linkFromScan("  $link\n"))
    }

    @Test
    fun `a doubled scheme is repaired before reading`() {
        assertEquals(link, InviteQr.linkFromScan("https://https://konstruct.cc/add?invite=$payload"))
    }

    @Test
    fun `byte-mode CIv1 is read from the raw segment`() {
        assertEquals(link, InviteQr.linkFromScan(text = "garbled", byteSegments = listOf(invite.encodeBinary())))
    }

    @Test
    fun `byte-mode CIv1 surfaced as a Latin-1 string is recovered`() {
        val latin1 = String(invite.encodeBinary(), Charsets.ISO_8859_1)
        assertEquals(link, InviteQr.linkFromScan(latin1))
    }

    @Test
    fun `anything else is not an invite`() {
        assertNull(InviteQr.linkFromScan("https://example.com"))
        assertNull(InviteQr.linkFromScan("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(InviteQr.linkFromScan("   "))
        assertNull(InviteQr.linkFromScan(null))
    }
}
