package com.construct.messenger.data.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sealed request is the only door a sealed envelope leaves by, so what it carries is the
 * whole of what the server gets. **Canon:** iOS `buildSealedRequest` tests.
 */
class MessagingServiceSealedRequestTest {

    /** Mutation: drop `setTimestamp` — a forwarded envelope arrives stamped 0 and is refused. */
    @Test
    fun `the sealed request carries the inner bytes and the send time in seconds`() {
        val inner = byteArrayOf(1, 2, 3)

        val request = MessagingService.buildSealedRequest(inner, nowSeconds = 1_700_000_000, attemptId = "a")

        assertArrayEquals(inner, request.sealedSender.sealedInner.toByteArray())
        assertEquals(1_700_000_000L, request.sealedSender.timestamp)
        assertEquals("a", request.attemptId)
    }

    /** Nothing that names the pair: the request has no field for it, and the envelope sets none. */
    @Test
    fun `the sealed request names no destination server`() {
        val request = MessagingService.buildSealedRequest(byteArrayOf(9), nowSeconds = 1, attemptId = "a")

        assertEquals("", request.sealedSender.recipientServer)
        assertEquals(0, request.sealedSender.forwardingToken.size())
    }
}
