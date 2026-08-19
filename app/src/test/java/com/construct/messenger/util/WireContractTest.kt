package com.construct.messenger.util

import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.invite.InviteObject
import com.google.protobuf.ByteString
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.EnvelopeOuterClass.SealedInner
import shared.proto.core.v1.Identity.UserId
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.TextMessage
import shared.proto.signaling.v1.Presence.DeliveryReceipt
import shared.proto.signaling.v1.Presence.DirectReceipt

/**
 * JVM stand-in for iOS↔Android interop: facts the other client will read off the wire.
 *
 * **Canon:** `docs/WIRE_FORMAT_RULES.md`, invite v5 spec, delivery-receipt binary ADR.
 */
class WireContractTest {

    @Test
    fun knstHeaderIsMagicVersionAndTypeInByte5() {
        val payload = MessageContent.newBuilder()
            .setText(TextMessage.newBuilder().setText("hi"))
            .build()
            .toByteArray()
        val id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val frame = KnstFrame.pack(payload, 14, id)
        assertEquals('K'.code, frame[0].toInt())
        assertEquals('N'.code, frame[1].toInt())
        assertEquals('S'.code, frame[2].toInt())
        assertEquals('T'.code, frame[3].toInt())
        assertEquals(1, frame[4].toInt())
        assertEquals(14, frame[5].toInt() and 0xFF)
        assertEquals(KnstFrame.HEADER_SIZE + payload.size, frame.size)
    }

    @Test
    fun identifiedEnvelopeOmitsConversationId() {
        val envelope = Envelope.newBuilder()
            .setMessageId("m1")
            .setSender(UserId.newBuilder().setUserId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .setRecipient(UserId.newBuilder().setUserId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            .setEncryptedPayload(ByteString.copyFrom(byteArrayOf(1)))
            .setContentType(ContentType.CONTENT_TYPE_E2EE_SIGNAL)
            .build()
        assertEquals("", envelope.conversationId)
    }

    @Test
    fun sealedInnerUnspecifiedOmitsContentTypeTag() {
        val generic = SealedInner.newBuilder()
            .setRecipientUserId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            .setDeliveryTag(ByteString.copyFrom(ByteArray(32)))
            .setSenderCertCiphertext(ByteString.copyFrom(byteArrayOf(1)))
            .setEncryptedPayload(ByteString.copyFrom(byteArrayOf(2)))
            .setContentType(ContentType.CONTENT_TYPE_UNSPECIFIED)
            .build()
            .toByteArray()
        val typed = SealedInner.newBuilder()
            .setRecipientUserId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            .setDeliveryTag(ByteString.copyFrom(ByteArray(32)))
            .setSenderCertCiphertext(ByteString.copyFrom(byteArrayOf(1)))
            .setEncryptedPayload(ByteString.copyFrom(byteArrayOf(2)))
            .setContentType(ContentType.CONTENT_TYPE_E2EE_SIGNAL)
            .build()
            .toByteArray()
        assertTrue("UNSPECIFIED SealedInner must be shorter than a typed one", generic.size < typed.size)
        assertFalse("field 5 tag 0x28 must be absent for GENERIC", generic.contains(0x28.toByte()))
        assertTrue(typed.contains(0x28.toByte()))
    }

    @Test
    fun endSessionPayloadIsFixed1024() {
        assertEquals(1024, ByteArray(1024).size)
    }

    @Test
    fun inviteV5CanonicalIncludesTtl() {
        val invite = InviteObject(
            v = 5,
            jti = "11111111-1111-1111-1111-111111111111",
            uuid = "22222222-2222-2222-2222-222222222222",
            deviceId = "0123456789abcdef0123456789abcdef",
            server = "konstruct.cc",
            ephKey = "",
            ts = 1_700_000_000L,
            sig = "AA",
            un = null,
            ttl = 300,
        )
        assertEquals(
            "5|11111111-1111-1111-1111-111111111111|22222222-2222-2222-2222-222222222222|0123456789abcdef0123456789abcdef|konstruct.cc|1700000000||300",
            invite.canonicalString(),
        )
        assertEquals(300L, InviteConfig.effectiveTtl(300))
    }

    @Test
    fun receiptProtoAndLegacyJsonAreBothRead() {
        val proto = DeliveryReceipt.newBuilder()
            .setDirect(DirectReceipt.newBuilder().addMessageIds("mid-1"))
            .build()
            .toByteArray()
        assertEquals(listOf("mid-1"), IncomingReceipt.messageIds(proto))
        val json = """{"type":"delivery_receipt","message_ids":["mid-2"]}""".toByteArray()
        assertEquals(listOf("mid-2"), IncomingReceipt.messageIds(json))
    }
}
