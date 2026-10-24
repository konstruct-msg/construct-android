package com.construct.messenger.devicelink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link QRs byte for byte as iOS `DeviceLinkViewModel` writes them — a difference here is a
 * phone and a Mac that cannot link. The expected strings are what iOS builds from the same input
 * (`URLComponents` queries; `pubkey` escaped to alphanumerics + `-._~`, `name` to `urlQueryAllowed`).
 */
class DeviceLinkQrTest {
    private val fp = ByteArray(32) { it.toByte() }
    private val secret = ByteArray(32) { (0xff - it).toByte() }
    private val fpHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val secretHex = "fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2e1e0"

    @Test
    fun offerIsTheIosString() {
        assertEquals("konstruct://link?token=tok-123&fp=$fpHex&s=$secretHex", DeviceLinkQr.offer("tok-123", fp, secret))
        assertEquals("konstruct://link?token=tok-123&s=$secretHex", DeviceLinkQr.offer("tok-123", null, secret))
    }

    @Test
    fun joinRequestIsTheIosString() {
        // base64 of fb ff bf is "+/+/": every character iOS escapes in `pubkey`.
        val qr = DeviceLinkQr.joinRequest("ab".repeat(16), byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte()), fp, "Mac mini М", "android")
        assertEquals(
            "konstruct://link-to-me?id=${"ab".repeat(16)}&pubkey=%2B%2F%2B%2F&fp=$fpHex&name=Mac%20mini%20%D0%9C&platform=android",
            qr,
        )
    }

    /** What iOS shows is read back whole. Mutation: decode `+` as a space — the base64 key breaks. */
    @Test
    fun iosQrsReadBack() {
        val offer = DeviceLinkQr.parseOffer("konstruct://link?token=tok-123&fp=$fpHex&s=$secretHex")!!
        assertEquals("tok-123", offer.token)
        assertArrayEquals(fp, offer.fingerprint)
        assertArrayEquals(secret, offer.secret)

        val join = DeviceLinkQr.parseJoinRequest(
            "konstruct://link-to-me?id=${"cd".repeat(16)}&pubkey=+/+/&fp=$fpHex&name=Max%E2%80%99s%20MacBook&platform=desktop",
        )!!
        assertEquals("cd".repeat(16), join.deviceId)
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte()), join.identityPublic)
        assertArrayEquals(fp, join.fingerprint)
        assertEquals("Max’s MacBook", join.name)
        assertEquals("desktop", join.platform)
    }

    /** A QR older than certificates has no `s` or `fp`; a malformed one reads as absent, never as a shorter key. */
    @Test
    fun aMissingOrMalformedValueReadsAsAbsent() {
        val old = DeviceLinkQr.parseOffer("konstruct://link?token=t")!!
        assertNull(old.fingerprint)
        assertNull(old.secret)
        assertNull(DeviceLinkQr.parseOffer("konstruct://link?token=t&s=${secretHex.dropLast(2)}")!!.secret)
        assertNull(DeviceLinkQr.parseOffer("konstruct://link?token=t&s=${secretHex.dropLast(2)}zz")!!.secret)
        assertNull(DeviceLinkQr.parseOffer("konstruct://link?fp=$fpHex"))
        assertNull(DeviceLinkQr.parseJoinRequest("konstruct://link-to-me?id=x&platform=ios"))
        assertNull(DeviceLinkQr.parseOffer("konstruct://add?invite=x"))
    }

    /** A name with `&`, `=` or `+` is escaped (iOS leaves them, and splits its own query). */
    @Test
    fun roundTrip() {
        val qr = DeviceLinkQr.joinRequest("ef".repeat(16), ByteArray(32) { 7 }, fp, "Pixel & Co", "android")
        val back = DeviceLinkQr.parseJoinRequest(qr)!!
        assertArrayEquals(ByteArray(32) { 7 }, back.identityPublic)
        assertArrayEquals(fp, back.fingerprint)
        assertEquals("Pixel & Co", back.name)
        assertEquals("android", back.platform)
        assertEquals("a+b=c", DeviceLinkQr.parseJoinRequest(DeviceLinkQr.joinRequest("ef".repeat(16), ByteArray(1), fp, "a+b=c", "android"))!!.name)
    }
}
