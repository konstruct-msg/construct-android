package com.construct.messenger.stickers

import com.google.protobuf.ByteString
import shared.proto.messaging.v1.Content.StickerRef

/**
 * A sticker on the wire: a pack hash, an index and an emoji — about 40 bytes inside the E2EE
 * plaintext, never pixels. **Canon:** iOS `StickerReference`; rules from
 * `construct-protos/conformance/knst_sticker_ref.json` (`StickerReferenceTest` holds them to it).
 *
 * A reference that fails validation never becomes one of these: it is a corrupt message, neither
 * shown nor stored, and its fields never reach a file path.
 */
class StickerReference private constructor(
    /** SHA-256 of the pack's canonical signed manifest. Exactly 32 bytes. */
    val packId: ByteArray,
    /** Position in the manifest's sticker list — stable, because a pack never changes. */
    val index: Int,
    /** Duplicated from the manifest on purpose: what renders with nothing downloaded. */
    val emoji: String,
) {
    val packHex: String get() = packId.toHex()

    fun toWire(): StickerRef = StickerRef.newBuilder()
        .setPackId(ByteString.copyFrom(packId))
        .setIndex(index)
        .setEmoji(emoji)
        .build()

    override fun equals(other: Any?) =
        other is StickerReference && packId.contentEquals(other.packId) && index == other.index && emoji == other.emoji

    override fun hashCode() = (packId.contentHashCode() * 31 + index) * 31 + emoji.hashCode()

    companion object {
        const val PACK_ID_BYTES = 32
        const val EMOJI_MIN_BYTES = 1
        const val EMOJI_MAX_BYTES = 32

        /**
         * Measured in UTF-8 bytes, not graphemes: segmentation depends on the ICU version and
         * would differ between iOS, Rust and Android; byte length does not. 32 admits a ZWJ family
         * (25) and a tag-sequence flag (28).
         */
        fun emojiIsValid(emoji: String): Boolean = emoji.toByteArray(Charsets.UTF_8).size in EMOJI_MIN_BYTES..EMOJI_MAX_BYTES

        fun of(packId: ByteArray, index: Int, emoji: String): StickerReference? {
            if (packId.size != PACK_ID_BYTES || index < 0 || !emojiIsValid(emoji)) return null
            return StickerReference(packId.copyOf(), index, emoji)
        }

        fun fromWire(wire: StickerRef): StickerReference? = of(wire.packId.toByteArray(), wire.index, wire.emoji)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
