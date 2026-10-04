package com.construct.messenger.media

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media blobs are the core's (`media.rs`, 0.33.0); construct-docs TODO 114. */
class MediaCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /**
     * `LEGACY_VECTOR` in `media.rs`: the pre-0.33 blob as this file used to seal it — javax.crypto,
     * key 0x07×32, nonce 0x09×12, no associated data. The core opens it: media already in chats
     * stays readable. Mutation: drop the legacy branch in the core — the open throws.
     */
    @Test
    fun `the old blob is the cores legacy vector, and it still opens`() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12) { 9 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val legacy = nonce + cipher.doFinal("konstruct".toByteArray())
        assertEquals("0909090909090909090909094ceaeae7ca82b402d45b2c4d098ff3e8d8546682abc9e5f8ab", hex(legacy))
        assertEquals("konstruct", String(MediaCrypto.open(legacy, key)))
    }

    /** Sealed by CryptoKit (`AES.GCM.seal(...).combined`, key 00…1f, nonce a0…ab), as iOS uploaded before 0.33. */
    @Test
    fun `opens what iOS sealed before the padding`() {
        val blob = hex("a0a1a2a3a4a5a6a7a8a9aaab8d77125e31b977dc1645eab66313a17716b681c0f3ec36b30c1c9a5c0a0c9c")
        assertEquals("konstruct media", String(MediaCrypto.open(blob, ByteArray(32) { it.toByte() })))
    }

    @Test
    fun `what it seals it opens, digested whole, and nothing else does`() {
        val sealed = MediaCrypto.seal("photo".toByteArray())
        assertEquals(32, sealed.key.size)
        assertArrayEquals(sha256(sealed.blob), sealed.sha256)
        assertEquals("photo", String(MediaCrypto.open(sealed.blob, sealed.key)))

        val tampered = sealed.blob.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertThrows(Exception::class.java) { MediaCrypto.open(tampered, sealed.key) }
        assertThrows(Exception::class.java) { MediaCrypto.open(sealed.blob, ByteArray(32)) }
    }

    /** The server sees a size class, not the size. Mutation: stop padding — the two differ. */
    @Test
    fun `files of neighbouring sizes make blobs of one size`() {
        val a = MediaCrypto.seal(ByteArray(100_000) { 1 })
        val b = MediaCrypto.seal(ByteArray(100_001) { 1 })
        assertEquals(a.blob.size, b.blob.size)
        assertTrue(a.blob.size > 100_001)
        assertEquals(4096, MediaCrypto.seal(ByteArray(1)).blob.size)
    }

    /** The limit is the store's 100 000 000 bytes, not 100 MiB; past it the core refuses. */
    @Test
    fun `the largest file is what the store takes, less the seal`() {
        assertEquals(100_000_000L - 36, MediaCrypto.maxPlaintextBytes)
        assertEquals(MediaCrypto.maxPlaintextBytes, PickedFiles.MAX_BYTES)
        assertNotEquals(100L * 1024 * 1024 - 28, PickedFiles.MAX_BYTES)
    }
}
