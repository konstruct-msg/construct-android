package com.construct.messenger.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PIN hash is PBKDF2-HMAC-SHA256 to 32 bytes — the scheme iOS uses — checked against the
 * published test vectors (RFC 7914 §11 for c = 1; the widely used c = 4096 vector), not against
 * itself. Mutation: swap the PRF to HmacSHA1 in `pbkdf2Sha256` — both assertions redden.
 */
class PinHashTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `one iteration matches the published vector`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(pbkdf2Sha256("password", "salt".toByteArray(), 1)),
        )
    }

    @Test
    fun `4096 iterations match the published vector`() {
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(pbkdf2Sha256("password", "salt".toByteArray(), 4096)),
        )
    }
}
