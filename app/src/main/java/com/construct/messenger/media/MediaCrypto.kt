package com.construct.messenger.media

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption of a media blob. **Canon:** iOS `MediaServiceClient.uploadMedia` /
 * `MediaUploadService.decrypt` — CryptoKit AES-256-GCM, a fresh random 32-byte key per upload, a
 * random 12-byte nonce, no associated data, stored as CryptoKit's `combined` form:
 * `nonce(12) ‖ ciphertext ‖ tag(16)`. The digest the message carries is SHA-256 of that whole
 * blob (the proto comment calls it an HMAC; it is not).
 *
 * Done here rather than in the core because iOS does it on the platform too and the two must
 * agree byte for byte; the core exposes no media AEAD.
 */
object MediaCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    const val OVERHEAD = NONCE_BYTES + TAG_BITS / 8

    class Sealed(val key: ByteArray, val blob: ByteArray, val sha256: ByteArray)

    fun seal(plaintext: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val blob = nonce + cipher.doFinal(plaintext)
        return Sealed(key, blob, sha256(blob))
    }

    /** Throws when [key] is not the one [blob] was sealed with, or [blob] was altered. */
    fun open(blob: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "media key is ${key.size} bytes" }
        require(blob.size >= OVERHEAD) { "media blob is ${blob.size} bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES),
        )
        return cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
