package com.construct.messenger.util

import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.MessageMedia
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.VoiceMessage

/**
 * Media in `MessageContent`, and back. **Canon:** iOS `MediaWireCodec`.
 *
 * What a row keeps is the wire message itself — [Stored.kind] and the `MediaAlbumMessage` or
 * `VoiceMessage` bytes — so nothing the sender put there is lost before Android can show it.
 */
object MediaWire {
    const val KIND_ALBUM = "album"
    const val KIND_VOICE = "voice"
    /** `mediaPayload` holds the `StickerRef` as received — the pack, the index, the emoji. */
    const val KIND_STICKER = "sticker"

    fun sticker(ref: com.construct.messenger.stickers.StickerReference) =
        Stored(KIND_STICKER, ref.toWire().toByteArray(), "")

    /** An item of ours not yet in the store is named this, then the store's id once uploaded. */
    const val LOCAL_PREFIX = "local-"

    fun isStaged(media: MessageMedia): Boolean = when (media) {
        is MessageMedia.Album -> media.items.any { it.mediaId.startsWith(LOCAL_PREFIX) }
        is MessageMedia.Voice -> media.audio.mediaId.startsWith(LOCAL_PREFIX)
        is MessageMedia.Sticker -> false
    }

    /** iOS `protoMediaType`: gif animated, other images images, video, audio, else a file. */
    fun mediaTypeOf(mime: String): shared.proto.messaging.v1.Content.MediaType {
        val m = mime.lowercase()
        return when {
            m == "image/gif" -> shared.proto.messaging.v1.Content.MediaType.MEDIA_TYPE_ANIMATED
            m.startsWith("image/") -> shared.proto.messaging.v1.Content.MediaType.MEDIA_TYPE_IMAGE
            m.startsWith("video/") -> shared.proto.messaging.v1.Content.MediaType.MEDIA_TYPE_VIDEO
            m.startsWith("audio/") -> shared.proto.messaging.v1.Content.MediaType.MEDIA_TYPE_AUDIO
            else -> shared.proto.messaging.v1.Content.MediaType.MEDIA_TYPE_FILE
        }
    }

    /** The `MessageContent` a stored media message was, to send again. */
    fun content(kind: String, bytes: ByteArray): ByteArray? = runCatching {
        when (kind) {
            KIND_ALBUM -> MessageContent.newBuilder().setMediaAlbum(MediaAlbumMessage.parseFrom(bytes))
            KIND_VOICE -> MessageContent.newBuilder().setVoice(VoiceMessage.parseFrom(bytes))
            KIND_STICKER -> MessageContent.newBuilder().setSticker(shared.proto.messaging.v1.Content.StickerRef.parseFrom(bytes))
            else -> null
        }?.build()?.toByteArray()
    }.getOrNull()

    class Stored(val kind: String, val bytes: ByteArray, val caption: String)

    /**
     * A stored album with its caption replaced — what an edit of a photo's caption does to the row
     * (iOS `MediaWireCodec.editedCaptionPayload`), so a later resend carries the caption shown.
     * Null for anything but an album.
     */
    fun withCaption(kind: String?, bytes: ByteArray?, caption: String): ByteArray? {
        if (kind != KIND_ALBUM || bytes == null) return null
        return runCatching { MediaAlbumMessage.parseFrom(bytes).toBuilder().setCaption(caption).build().toByteArray() }.getOrNull()
    }

    /**
     * The media in [content], or null when it carries none. A single `media` is kept as a
     * one-item album, as iOS reads it; iOS itself only ever sends albums.
     */
    fun stored(content: MessageContent): Stored? = when {
        content.hasMediaAlbum() -> content.mediaAlbum.let {
            Stored(KIND_ALBUM, it.toByteArray(), if (it.hasCaption()) it.caption else "")
        }
        content.hasMedia() -> content.media.let { item ->
            val caption = if (item.hasCaption()) item.caption else ""
            val album = MediaAlbumMessage.newBuilder().addItems(item)
            if (caption.isNotEmpty()) album.caption = caption
            Stored(KIND_ALBUM, album.build().toByteArray(), caption)
        }
        content.hasVoice() -> Stored(KIND_VOICE, content.voice.toByteArray(), "")
        else -> null
    }

    fun decode(kind: String?, bytes: ByteArray?): MessageMedia? {
        if (kind == null || bytes == null) return null
        return runCatching {
            when (kind) {
                KIND_ALBUM -> MessageMedia.Album(MediaAlbumMessage.parseFrom(bytes).itemsList.mapNotNull(::item))
                    .takeIf { it.items.isNotEmpty() }
                KIND_VOICE -> voice(VoiceMessage.parseFrom(bytes))
                KIND_STICKER -> com.construct.messenger.stickers.StickerReference
                    .fromWire(shared.proto.messaging.v1.Content.StickerRef.parseFrom(bytes))
                    ?.let(MessageMedia::Sticker)
                else -> null
            }
        }.getOrNull()
    }

    /** The quote an album carries, when it is a reply. */
    fun albumQuote(content: MessageContent) = if (content.hasMediaAlbum() && content.mediaAlbum.hasQuoted()) content.mediaAlbum.quoted else null

    private fun item(m: MediaMessage): MediaItem? {
        if (m.mediaId.isEmpty() || m.encryptionKey.size() != KEY_BYTES) return null
        return MediaItem(
            mediaId = m.mediaId,
            key = m.encryptionKey.toByteArray(),
            hash = m.fileHash.toByteArray(),
            sizeBytes = m.fileSize,
            mimeType = m.mimeType.ifEmpty { "application/octet-stream" },
            width = if (m.hasDimensions() && m.dimensions.width > 0) m.dimensions.width else null,
            height = if (m.hasDimensions() && m.dimensions.height > 0) m.dimensions.height else null,
            durationMs = if (m.hasDurationMs()) m.durationMs.toLong() else null,
            blurhash = if (m.hasBlurhash()) m.blurhash.ifEmpty { null } else null,
            thumbnail = if (m.hasThumbnail()) m.thumbnail.toByteArray().takeIf { it.isNotEmpty() } else null,
            filename = if (m.hasFilename()) m.filename.ifEmpty { null } else null,
            // A value this build does not know reads as UNRECOGNIZED: the ordinary bubble.
            isVideoNote = m.presentation == shared.proto.messaging.v1.Content.MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE,
        )
    }

    /**
     * iOS has no `media_id` field for voice: it rides in `codec` as `mime|mediaId|size`
     * (`MediaWireCodec.voiceJSON`). The mime defaults to `audio/m4a`, as there.
     */
    private fun voice(v: VoiceMessage): MessageMedia.Voice? {
        val parts = v.codec.split("|")
        val mime = parts.getOrNull(0)?.ifEmpty { null } ?: "audio/m4a"
        val mediaId = parts.getOrNull(1).orEmpty()
        val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (mediaId.isEmpty() || v.encryptionKey.size() != KEY_BYTES) return null
        return MessageMedia.Voice(
            audio = MediaItem(
                mediaId = mediaId,
                key = v.encryptionKey.toByteArray(),
                hash = v.fileHash.toByteArray(),
                sizeBytes = size,
                mimeType = mime,
                durationMs = v.durationMs.toLong(),
            ),
            waveform = v.waveformList.map { it.coerceIn(0, 255) },
        )
    }

    private const val KEY_BYTES = 32
}
