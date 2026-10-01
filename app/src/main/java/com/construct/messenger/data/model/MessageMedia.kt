package com.construct.messenger.data.model

/**
 * What a message carries besides text: an album of photos, videos or files, or a voice note.
 * **Canon:** iOS `MediaWireCodec` over `content.proto` `MediaAlbumMessage` / `VoiceMessage`.
 *
 * Each item names its encrypted blob in the media store ([MediaItem.mediaId]) and holds the key
 * that opens it; the store holds nothing that reads.
 */
sealed interface MessageMedia {

    /** One or more items sent together; its caption is the message text. */
    data class Album(val items: List<MediaItem>) : MessageMedia {
        /** iOS `looksLikeFileAlbum`: nothing in it is a picture, a video or audio. */
        val isFiles: Boolean
            get() = items.isNotEmpty() && items.none { it.isImage || it.isVideo || it.mimeType.startsWith("audio/") }
    }

    /** A voice note: one AAC blob, its length, and the waveform drawn for it (0–255 each). */
    data class Voice(val audio: MediaItem, val waveform: List<Int>) : MessageMedia

    /** A sticker: a reference into a public pack, never pixels (`stickers/StickerStore`). */
    data class Sticker(val ref: com.construct.messenger.stickers.StickerReference) : MessageMedia
}

class MediaItem(
    val mediaId: String,
    /** AES-256-GCM key, 32 bytes. */
    val key: ByteArray,
    /** SHA-256 of the encrypted blob. */
    val hash: ByteArray,
    /** As the sender counted it: the blob for photos and videos, the file itself for files and voice. */
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val blurhash: String? = null,
    /** A small JPEG to show until the item is fetched; iOS sends it for videos, and photos without a BlurHash. */
    val thumbnail: ByteArray? = null,
    val filename: String? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    override fun equals(other: Any?): Boolean = other is MediaItem && mediaId == other.mediaId && key.contentEquals(other.key)
    override fun hashCode(): Int = mediaId.hashCode()
    override fun toString(): String = "MediaItem(${mediaId.take(8)}…, $mimeType, $sizeBytes B)"
}
