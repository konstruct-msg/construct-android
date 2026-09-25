package com.construct.messenger.util

import java.security.MessageDigest

/**
 * Short, human-comparable label for an identity public key: `A1B2 C3D4 E5F6 0718`.
 *
 * **Canon:** iOS `IdentityFingerprint.short(from:)` — SHA-256 of the key, first 8 bytes,
 * uppercase hex in groups of 4. Both apps must print the same string for the same key, or
 * comparing them out of band proves nothing. Not the two-party safety number.
 */
object IdentityFingerprint {
    private const val BYTE_COUNT = 8

    fun short(identityPublicKey: ByteArray): String? {
        if (identityPublicKey.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(identityPublicKey)
        return digest.take(BYTE_COUNT)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }
}
