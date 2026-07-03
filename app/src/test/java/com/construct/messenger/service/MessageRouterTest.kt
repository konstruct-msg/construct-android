package com.construct.messenger.service

import com.construct.messenger.stealth.StealthSenderService
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.EnvelopeOuterClass.SealedSenderEnvelope
import shared.proto.core.v1.Identity.UserId

class MessageRouterTest {

    private fun identifiedEnvelope(
        sender: String = "alice",
        contentType: ContentType = ContentType.CONTENT_TYPE_E2EE_SIGNAL,
    ): Envelope = Envelope.newBuilder()
        .setMessageId("msg-1")
        .setSender(UserId.newBuilder().setUserId(sender))
        .setContentType(contentType)
        .setEncryptedPayload(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        .setTimestamp(1_000L)
        .build()

    private fun sealedEnvelope(): Envelope = Envelope.newBuilder()
        .setMessageId("msg-2")
        .setSealedSender(
            SealedSenderEnvelope.newBuilder()
                .setSealedInner(ByteString.copyFrom(byteArrayOf(9, 9))),
        )
        .setTimestamp(2_000L)
        .build()

    @Test
    fun `identified envelope normalizes with sender and payload`() {
        val msg = normalizeEnvelope(identifiedEnvelope()) { error("no sealed resolution expected") }

        requireNotNull(msg)
        assertEquals("alice", msg.senderId)
        assertEquals(ContentType.CONTENT_TYPE_E2EE_SIGNAL, msg.contentType)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(msg.encryptedPayload))
        assertFalse(msg.viaSealedSender)
    }

    @Test
    fun `identified envelope without sender is dropped`() {
        val envelope = identifiedEnvelope().toBuilder().clearSender().build()
        assertNull(normalizeEnvelope(envelope) { null })
    }

    @Test
    fun `sealed envelope takes identity content type and payload from resolver`() {
        val msg = normalizeEnvelope(sealedEnvelope()) { inner ->
            assertTrue(byteArrayOf(9, 9).contentEquals(inner))
            StealthSenderService.ResolvedSender(
                senderId = "bob",
                contentType = ContentType.CONTENT_TYPE_DELIVERY_RECEIPT,
                encryptedPayload = byteArrayOf(7),
            )
        }

        requireNotNull(msg)
        assertEquals("bob", msg.senderId)
        assertEquals(ContentType.CONTENT_TYPE_DELIVERY_RECEIPT, msg.contentType)
        assertTrue(byteArrayOf(7).contentEquals(msg.encryptedPayload))
        assertTrue(msg.viaSealedSender)
    }

    @Test
    fun `unresolvable sealed envelope is dropped`() {
        assertNull(normalizeEnvelope(sealedEnvelope()) { null })
    }

    @Test
    fun `control content types are classified as control`() {
        listOf(
            ContentType.CONTENT_TYPE_SESSION_RESET,
            ContentType.CONTENT_TYPE_KEY_SYNC,
            ContentType.CONTENT_TYPE_SENDER_SYNC,
            ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
            ContentType.CONTENT_TYPE_SESSION_PING,
            ContentType.CONTENT_TYPE_SESSION_READY,
            ContentType.CONTENT_TYPE_HEARTBEAT,
            ContentType.CONTENT_TYPE_KEY_EXCHANGE,
        ).forEach { assertTrue("$it must be control", it.isControl()) }

        listOf(
            ContentType.CONTENT_TYPE_E2EE_SIGNAL,
            ContentType.CONTENT_TYPE_DELIVERY_RECEIPT,
            ContentType.CONTENT_TYPE_CALL_SIGNAL,
        ).forEach { assertFalse("$it must not be control", it.isControl()) }
    }
}
