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

    class Stored(val kind: String, val bytes: ByteArray, val caption: String)

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
