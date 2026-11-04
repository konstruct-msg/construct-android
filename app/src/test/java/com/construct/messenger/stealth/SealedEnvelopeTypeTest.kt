package com.construct.messenger.stealth

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.EnvelopeKind

class SealedEnvelopeTypeTest {

    /**
     * The relay reads this field in plaintext; `WIRE_FORMAT_RULES.md` §2 allows the generic value
     * and 28 alone. Mutation: add a case — this reddens, and the case needs a written reason.
     */
    @Test
    fun `a sealed envelope can declare nothing but generic and decryption error`() {
        assertEquals(listOf(0, 28), SealedEnvelopeType.entries.map { it.proto.number })
    }

    @Test
    fun `a decryption error is labelled from the envelope and a ratchet body is not`() {
        assertEquals(
            ContentType.CONTENT_TYPE_DECRYPTION_ERROR,
            contentTypeOfEnvelope(EnvelopeKind.DECRYPTION_ERROR),
        )
        assertEquals(
            ContentType.CONTENT_TYPE_UNSPECIFIED,
            contentTypeOfEnvelope(EnvelopeKind.RATCHET),
        )
    }
}
