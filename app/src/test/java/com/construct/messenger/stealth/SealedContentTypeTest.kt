package com.construct.messenger.stealth

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType

/**
 * `sealedContentType` maps the core's `SealedInner.content_type` byte onto the proto enum for the
 * certificate and first-flight routes. An unknown value reads as UNSPECIFIED, not a guess — and
 * never UNRECOGNIZED, which the routing layer would treat as a message type it has not seen.
 */
class SealedContentTypeTest {

    @Test
    fun unspecifiedByteIsUnspecified() {
        assertEquals(ContentType.CONTENT_TYPE_UNSPECIFIED, sealedContentType(0u))
    }

    @Test
    fun decryptionErrorByteIsDecryptionError() {
        assertEquals(ContentType.CONTENT_TYPE_DECRYPTION_ERROR, sealedContentType(28u))
    }

    @Test
    fun unknownByteReadsAsUnspecified() {
        assertEquals(ContentType.CONTENT_TYPE_UNSPECIFIED, sealedContentType(99u))
    }
}
