package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.QuotedMessage
import shared.proto.messaging.v1.Content.StickerRef
import shared.proto.messaging.v1.Content.VoiceMessage

/**
 * CTM1 as iOS writes and reads it (`LocalMessagePayload.swift`): the same bytes, whichever client
 * wrote the store. Mutation: change the magic or a kind byte — the vectors redden.
 */
class LocalMessagePayloadTest {
    private fun record(text: String = "", mediaType: String? = null, payload: ByteArray? = null, replyTo: String? = null, preview: String? = null, replyMedia: String? = null) =
        MessageRecord("m", "c", text, true, 1, DeliveryStatus.SENT, replyToId = replyTo, replyPreview = preview, replyMediaType = replyMedia, mediaType = mediaType, mediaPayload = payload)

    private val album = MediaAlbumMessage.newBuilder()
        .addItems(MediaMessage.newBuilder().setMediaType(MediaType.MEDIA_TYPE_IMAGE).setFileUrl("id-1").setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 7 })))
        .setCaption("look")
        .build()

    @Test
    fun theEnvelopeIsIosBytes() {
        val hi = LocalMessagePayload.envelope(LocalMessagePayload.KIND_TEXT, "hi".toByteArray())
        assertArrayEquals(byteArrayOf(0x43, 0x54, 0x4D, 0x31, 0x01, 0x68, 0x69), hi)
        assertEquals("hi", LocalMessagePayload.decode(hi).text)
    }

    /** iOS rows from before CTM1 hold bare UTF-8. */
    @Test
    fun bytesWithoutTheMagicAreText() {
        assertEquals("привет", LocalMessagePayload.decode("привет".toByteArray()).text)
        assertEquals("CTM", LocalMessagePayload.decode("CTM".toByteArray()).text)
    }

    @Test
    fun androidWritesTheWholeMessageContent() {
        val body = LocalMessagePayload.encode(record(text = "hello"))
        assertArrayEquals(byteArrayOf(0x43, 0x54, 0x4D, 0x31, 0x03), body.copyOfRange(0, 5))
    }

    @Test
    fun textWithItsQuoteRoundTrips() {
        val r = record(text = "yes", replyTo = "q-1", preview = "are you there?", replyMedia = "MEDIA_TYPE_IMAGE")
        val fields = LocalMessagePayload.decode(LocalMessagePayload.encode(r))
        assertEquals("yes", fields.text)
        assertEquals(ReplyRef("q-1", "are you there?", "MEDIA_TYPE_IMAGE"), fields.reply)
        assertNull(fields.mediaType)
    }

    @Test
    fun anAlbumRoundTripsWithItsCaption() {
        val fields = LocalMessagePayload.decode(LocalMessagePayload.encode(record(text = "look", mediaType = MediaWire.KIND_ALBUM, payload = album.toByteArray())))
        assertEquals("look", fields.text)
        assertEquals(MediaWire.KIND_ALBUM, fields.mediaType)
        assertArrayEquals(album.toByteArray(), fields.mediaPayload)
    }

    /** What iOS writes for an album it sends: kind 0x02, its quote inside. */
    @Test
    fun iosAlbumKindIsRead() {
        val quoted = album.toBuilder().setQuoted(QuotedMessage.newBuilder().setMessageId("Q-2").setTextPreview("this")).build()
        val fields = LocalMessagePayload.decode(LocalMessagePayload.envelope(LocalMessagePayload.KIND_MEDIA_ALBUM, quoted.toByteArray()))
        assertEquals("look", fields.text)
        assertEquals(MediaWire.KIND_ALBUM, fields.mediaType)
        assertArrayEquals(quoted.toByteArray(), fields.mediaPayload)
        assertEquals(ReplyRef("q-2", "this"), fields.reply)
    }

    @Test
    fun voiceAndStickerRoundTrip() {
        val voice = VoiceMessage.newBuilder().setFileUrl("v-1").setDurationMs(1200).build().toByteArray()
        val v = LocalMessagePayload.decode(LocalMessagePayload.encode(record(mediaType = MediaWire.KIND_VOICE, payload = voice)))
        assertEquals(MediaWire.KIND_VOICE, v.mediaType)
        assertArrayEquals(voice, v.mediaPayload)

        val sticker = StickerRef.newBuilder().setPackId(ByteString.copyFrom(byteArrayOf(1, 2))).setIndex(3).setEmoji("🙂").build().toByteArray()
        val s = LocalMessagePayload.decode(LocalMessagePayload.encode(record(mediaType = MediaWire.KIND_STICKER, payload = sticker)))
        assertEquals(MediaWire.KIND_STICKER, s.mediaType)
        assertArrayEquals(sticker, s.mediaPayload)
    }

    @Test
    fun whatCannotBeReadIsEmptyNotAnError() {
        assertEquals("", LocalMessagePayload.decode(LocalMessagePayload.envelope(0x09, byteArrayOf(1))).text)
        assertEquals("", LocalMessagePayload.decode(LocalMessagePayload.envelope(LocalMessagePayload.KIND_MESSAGE_CONTENT, byteArrayOf(-1, -1, -1))).text)
        assertEquals("", LocalMessagePayload.decode(LocalMessagePayload.envelope(LocalMessagePayload.KIND_PROFILE, byteArrayOf(1))).text)
    }
}
