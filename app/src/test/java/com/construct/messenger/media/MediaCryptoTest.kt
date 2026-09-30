package com.construct.messenger.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * Sealed by CryptoKit (`AES.GCM.seal(...).combined`, key 00…1f, nonce a0…ab), as iOS uploads.
     * Mutation: move the nonce after the tag, or add associated data — this reddens.
     */
    @Test
    fun `opens what iOS sealed, and digests it as iOS does`() {
        val blob = hex("a0a1a2a3a4a5a6a7a8a9aaab8d77125e31b977dc1645eab66313a17716b681c0f3ec36b30c1c9a5c0a0c9c")
        val key = ByteArray(32) { it.toByte() }
        assertEquals("konstruct media", String(MediaCrypto.open(blob, key)))
        assertArrayEquals(hex("c8969c2c1e026ec3c68303faf29fa44438ae18b9dd318a3ffa1e6c6cd79d0553"), MediaCrypto.sha256(blob))
    }

    @Test
    fun `what it seals it opens, and nothing else does`() {
        val sealed = MediaCrypto.seal("photo".toByteArray())
        assertEquals(5 + MediaCrypto.OVERHEAD, sealed.blob.size)
        assertArrayEquals(MediaCrypto.sha256(sealed.blob), sealed.sha256)
        assertEquals("photo", String(MediaCrypto.open(sealed.blob, sealed.key)))

        val tampered = sealed.blob.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertThrows(Exception::class.java) { MediaCrypto.open(tampered, sealed.key) }
        assertThrows(Exception::class.java) { MediaCrypto.open(sealed.blob, ByteArray(32)) }
    }
}
