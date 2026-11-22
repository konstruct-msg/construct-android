package com.construct.messenger.data.local

import com.construct.messenger.util.MediaWire
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaPresentation
import shared.proto.messaging.v1.Content.MessageContent
import uniffi.construct_core.LocalMediaEntry
import uniffi.construct_core.LocalMediaKind

/**
 * What media a stored message holds, for the store's media index: the chat gallery and the storage
 * screen read it (vault todo 327, decisions/chat-media-gallery-storage-backup.md). The core does
 * not read albums, so the client fills the index, as it fills `message_search`.
 * **Canon:** iOS `MessageMediaIndex.swift` — same kinds, same positions.
 */
object MessageMediaIndex {
    /**
     * The index entries of a message whose body is [body], or null when the body cannot hold media
     * (a plain text, a profile, a body from before the envelope) and the index is not asked about it.
     *
     * Empty — not null — for a message content that turns out to be a text or a sticker: the store
     * lists every unindexed album and message content (`unindexed_media_messages`) until it is
     * indexed, with no entries too.
     */
    fun entries(body: ByteArray): List<LocalMediaEntry>? {
        val inner = LocalMessagePayload.innerOf(body)
        return when (LocalMessagePayload.kindOf(body)) {
            LocalMessagePayload.KIND_MEDIA_ALBUM ->
                runCatching { entriesOf(MediaAlbumMessage.parseFrom(inner).itemsList) }.getOrDefault(emptyList())
            LocalMessagePayload.KIND_MESSAGE_CONTENT ->
                runCatching { entriesOf(MessageContent.parseFrom(inner)) }.getOrDefault(emptyList())
            else -> null
        }
    }

    private fun entriesOf(content: MessageContent): List<LocalMediaEntry> = when {
        content.hasMediaAlbum() -> entriesOf(content.mediaAlbum.itemsList)
        content.hasMedia() -> entriesOf(listOf(content.media))
        content.hasVoice() -> {
            val ref = MediaWire.voiceRef(content.voice)
            if (ref.mediaId.isEmpty()) emptyList()
            else listOf(LocalMediaEntry(0u, ref.mediaId, LocalMediaKind.VOICE, ref.sizeBytes))
        }
        else -> emptyList()
    }

    /**
     * The kind the gallery files an item under: by its MIME type, a round video by presentation.
     * What is neither picture nor video is a file — `MessageMedia.Album.isFiles` for a whole album,
     * here per item.
     */
    fun kind(mime: String, isVideoNote: Boolean): LocalMediaKind {
        val m = mime.lowercase()
        return when {
            m.startsWith("video/") -> if (isVideoNote) LocalMediaKind.VIDEO_NOTE else LocalMediaKind.VIDEO
            m == "image/gif" -> LocalMediaKind.ANIMATION
            m.startsWith("image/") -> LocalMediaKind.PHOTO
            else -> LocalMediaKind.FILE
        }
    }

    /**
     * An item's position in the album is its `item`, so the gallery opens the bubble's own item; one
     * with no media id has no file to show and is left out, the positions of the rest kept.
     */
    private fun entriesOf(items: List<MediaMessage>): List<LocalMediaEntry> =
        items.mapIndexedNotNull { index, item ->
            if (item.mediaId.isEmpty()) return@mapIndexedNotNull null
            LocalMediaEntry(
                item = index.toUInt(),
                mediaId = item.mediaId,
                kind = kind(item.mimeType, item.presentation == MediaPresentation.MEDIA_PRESENTATION_VIDEO_NOTE),
                // uint64 on the wire, a signed long here: past Long.MAX_VALUE reads negative.
                byteSize = item.fileSize.coerceAtLeast(0),
            )
        }
}
