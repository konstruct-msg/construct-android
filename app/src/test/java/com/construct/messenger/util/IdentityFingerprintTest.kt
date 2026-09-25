package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityFingerprintTest {
    @Test
    fun `first 8 bytes of SHA-256 in uppercase groups of four`() {
        // Independently computed: sha256(bytes 0..31)[0:8] = 630dcd2966c43366.
        assertEquals("630D CD29 66C4 3366", IdentityFingerprint.short(ByteArray(32) { it.toByte() }))
    }

    @Test
    fun `no key, no fingerprint`() {
        assertNull(IdentityFingerprint.short(ByteArray(0)))
    }
}
