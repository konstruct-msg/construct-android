package com.construct.messenger.util

import com.construct.messenger.data.model.ReplyRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.messaging.v1.Content.DeleteMessage
import shared.proto.messaging.v1.Content.DeleteScope
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.ReactionAction
import shared.proto.messaging.v1.Content.ReactionMessage
import shared.proto.messaging.v1.Content.TextMessage
import java.util.UUID

class IncomingPlaintextTest {

    @Test
    fun `legacy utf8 is user visible`() {
        val decoded = IncomingPlaintext.decode("hello".toByteArray())
        assertEquals("hello", decoded.text)
        assertTrue(decoded.isUserVisible)
    }

    @Test
    fun `knst text frame unpacks MessageContent`() {
        val frame = knstTextFrame("keys rotated")
        val decoded = IncomingPlaintext.decode(frame)
        assertEquals("keys rotated", decoded.text)
        assertTrue(decoded.isUserVisible)
        assertTrue(IncomingPlaintext.isKnst(frame))
    }

    @Test
    fun `knst pack roundtrips through decode`() {
        val payload = MessageContent.newBuilder()
            .setText(TextMessage.newBuilder().setText("roundtrip"))
            .build()
            .toByteArray()
        val frame = KnstFrame.pack(payload, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID())
        val decoded = IncomingPlaintext.decode(frame)
        assertEquals("roundtrip", decoded.text)
        assertTrue(decoded.isUserVisible)
        assertEquals(KnstFrame.TYPE_E2EE_SIGNAL, decoded.knstContentType)
    }

    @Test
    fun `quoted text roundtrips inside the knst frame`() {
        val reply = ReplyRef.of("ABCDEF", "hello there")!!
        val payload = TextWire.encode("the answer", reply)
        val content = MessageContent.parseFrom(payload)
        assertEquals("abcdef", content.text.quoted.messageId)
        assertEquals("hello there", content.text.quoted.textPreview)
        assertEquals("", content.text.quoted.senderId)

        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(payload, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertEquals("the answer", decoded.text)
        assertEquals("abcdef", decoded.reply?.messageId)
        assertEquals("hello there", decoded.reply?.preview)
        assertNull(decoded.reply?.mediaType)
        assertTrue(decoded.isUserVisible)
    }

    @Test
    fun `a quote of a photo keeps the media type and caps the preview`() {
        val reply = ReplyRef.of("id-1", "x".repeat(250), "MEDIA_TYPE_IMAGE")!!
        assertEquals(ReplyRef.MAX_CHARS, reply.preview.length)
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(TextWire.encode("nice", reply), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertEquals("id-1", decoded.reply?.messageId)
        assertEquals(ReplyRef.MAX_CHARS, decoded.reply?.preview?.length)
        assertEquals("MEDIA_TYPE_IMAGE", decoded.reply?.mediaType)
    }

    @Test
    fun `an empty quote id is not a reply`() {
        assertNull(ReplyRef.of("  ", "hi"))
    }

    @Test
    fun `knst text keeps the header id`() {
        val id = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(TextWire.encode("hi"), KnstFrame.TYPE_E2EE_SIGNAL, id),
        )
        assertEquals(id.toString(), decoded.e2eMessageId)
        assertTrue(decoded.isUserVisible)
        assertNull(decoded.edit)
    }

    @Test
    fun `an edit is not a bubble and names its target`() {
        val frame = KnstFrame.pack(
            EditWire.encode("ABCDEF", "rewritten"),
            KnstFrame.TYPE_E2EE_SIGNAL,
            UUID.randomUUID(),
        )
        val decoded = IncomingPlaintext.decode(frame)
        assertFalse(decoded.isUserVisible)
        assertEquals("", decoded.text)
        assertEquals("ABCDEF", decoded.edit?.targetMessageId)
        assertEquals("rewritten", decoded.edit?.newText)
        assertNull(decoded.delete)
    }

    @Test
    fun `delete for everyone is applied, delete for self is not`() {
        val everyone = MessageContent.newBuilder()
            .setDelete(
                DeleteMessage.newBuilder()
                    .setTargetMessageId("m-1")
                    .setScope(DeleteScope.DELETE_SCOPE_EVERYONE),
            )
            .build()
            .toByteArray()
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(everyone, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertFalse(decoded.isUserVisible)
        assertEquals("m-1", decoded.delete?.targetMessageId)

        val forSelf = MessageContent.newBuilder()
            .setDelete(
                DeleteMessage.newBuilder()
                    .setTargetMessageId("m-1")
                    .setScope(DeleteScope.DELETE_SCOPE_FOR_SELF),
            )
            .build()
            .toByteArray()
        val ignored = IncomingPlaintext.decode(
            KnstFrame.pack(forSelf, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertNull(ignored.delete)
        assertFalse(ignored.isUserVisible)
    }

    @Test
    fun `a reaction is not a bubble`() {
        val reaction = MessageContent.newBuilder()
            .setReaction(
                ReactionMessage.newBuilder()
                    .setTargetMessageId("m-1")
                    .setEmoji("❤")
                    .setAction(ReactionAction.REACTION_ACTION_ADD),
            )
            .build()
            .toByteArray()
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(reaction, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertFalse(decoded.isUserVisible)
        assertEquals("", decoded.text)
        assertNull(decoded.edit)
        assertNull(decoded.delete)
    }

    @Test
    fun `knst heartbeat is not user visible`() {
        val frame = knstTextFrame("", contentType = ContentType.CONTENT_TYPE_HEARTBEAT_VALUE)
        val decoded = IncomingPlaintext.decode(frame)
        assertFalse(decoded.isUserVisible)
        assertEquals(ContentType.CONTENT_TYPE_HEARTBEAT_VALUE, decoded.knstContentType)
    }

    /**
     * iOS resends a message a peer could not read as bare UTF-8 in a KNST frame, not as
     * MessageContent. Mutation: drop the UTF-8 fallback — the resend is hidden again.
     */
    @Test
    fun `bare utf8 in a knst frame is the text iOS resends`() {
        val id = UUID.randomUUID()
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack("Oo".toByteArray(), KnstFrame.TYPE_E2EE_SIGNAL, id),
        )
        assertTrue(decoded.isUserVisible)
        assertEquals("Oo", decoded.text)
        assertEquals(id.toString(), decoded.e2eMessageId)
    }

    /** Bytes that are neither MessageContent nor UTF-8 stay hidden, never shown as mojibake. */
    @Test
    fun `binary that is not text stays hidden in a knst frame`() {
        val decoded = IncomingPlaintext.decode(
            KnstFrame.pack(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x07), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )
        assertFalse(decoded.isUserVisible)
    }
    /**
     * iOS frames a shared profile as an ordinary type-1 KNST body. With a timestamp whose bytes
     * are all ASCII the whole payload is valid UTF-8, and the bare-text fallback showed it as a
     * bubble of control bytes. Mutation: try the text fallback first — this reddens.
     */
    @Test
    fun `a shared profile in a frame is a profile, not a bubble`() {
        val profile = ProfileShare("Kostya", timestampSec = 0x01010101)
        val frame = KnstFrame.pack(profile.encode(), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID())

        val decoded = IncomingPlaintext.decode(frame)

        assertEquals(false, decoded.isUserVisible)
        assertEquals("Kostya", decoded.profile?.displayName)
    }

}

private fun knstTextFrame(text: String, contentType: Int = 0, messageId: ByteArray = ByteArray(16)): ByteArray {
    val payload = MessageContent.newBuilder()
        .setText(TextMessage.newBuilder().setText(text))
        .build()
        .toByteArray()
    val out = ByteArray(IncomingPlaintext.HEADER_SIZE + payload.size)
    out[0] = 'K'.code.toByte()
    out[1] = 'N'.code.toByte()
    out[2] = 'S'.code.toByte()
    out[3] = 'T'.code.toByte()
    out[4] = 0x01
    out[5] = contentType.toByte()
    System.arraycopy(messageId, 0, out, 6, minOf(16, messageId.size))
    out[24] = 0
    out[25] = 1
    val len = payload.size
    out[26] = (len ushr 24).toByte()
    out[27] = (len ushr 16).toByte()
    out[28] = (len ushr 8).toByte()
    out[29] = len.toByte()
    System.arraycopy(payload, 0, out, IncomingPlaintext.HEADER_SIZE, payload.size)
    return out
}
