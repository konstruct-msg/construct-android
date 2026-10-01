package com.construct.messenger.stickers

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import shared.proto.messaging.v1.StickerPack.StickerPackManifest

/**
 * A verified sticker pack. **Canon:** iOS `StickerPack.verify` and
 * `decisions/sticker-packs-content-addressed.md`.
 *
 * A pack is named by the SHA-256 of its manifest's canonical bytes — proto3 binary with `pack_id`
 * and `signature` cleared — and signed over the same bytes with the pinned bundle-signing key.
 * Pack bytes travel unencrypted (they are the same for everyone), so this is what makes a
 * tampered pack refused rather than trusted: an altered manifest fails the signature, an altered
 * sticker fails the hash its signed entry names, an altered id is a pack nobody references.
 */
class StickerPack(
    val id: ByteArray,
    val title: String,
    val publisher: String,
    val stickers: List<Entry>,
) {
    class Entry(val sha256: ByteArray, val emoji: String, val byteLen: Int) {
        val hex: String get() = sha256.toHex()
    }

    val hex: String get() = id.toHex()

    fun reference(index: Int): StickerReference? =
        stickers.getOrNull(index)?.let { StickerReference.of(id, index, it.emoji) }

    sealed class VerifyError(message: String) : Exception(message) {
        data object Undecodable : VerifyError("manifest does not decode")
        data object PackIdMismatch : VerifyError("pack_id is not the hash of the manifest")
        data class BadEntry(val index: Int) : VerifyError("sticker $index breaks the image rules")
        data object Unsigned : VerifyError("manifest is unsigned")
        data object BadSignature : VerifyError("signature matches no trusted key")
    }

    companion object {
        const val CANVAS = 512
        const val MAX_BYTES = 100 * 1024

        fun canonicalBytes(manifest: StickerPackManifest): ByteArray =
            manifest.toBuilder().clearPackId().clearSignature().build().toByteArray()

        fun packId(manifest: StickerPackManifest): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(canonicalBytes(manifest))

        /**
         * Decode → hash → entries → signature: the cheapest refusal first, and an unsigned
         * manifest refused only once everything else about it is known to be right.
         *
         * [checkSignature] false re-reads a manifest this app wrote itself, after it was verified
         * once — the app's own disk is not the server.
         */
        fun verify(
            manifestBytes: ByteArray,
            trustedKeys: List<ByteArray>,
            checkSignature: Boolean = true,
            allowUnsigned: Boolean = false,
        ): StickerPack {
            val manifest = runCatching { StickerPackManifest.parseFrom(manifestBytes) }.getOrNull()
                ?: throw VerifyError.Undecodable
            val id = manifest.packId.toByteArray()
            if (id.size != StickerReference.PACK_ID_BYTES || !packId(manifest).contentEquals(id)) {
                throw VerifyError.PackIdMismatch
            }
            val entries = manifest.stickersList.mapIndexed { i, e ->
                val ok = e.sha256.size() == 32 && StickerReference.emojiIsValid(e.emoji) &&
                    e.width == CANVAS && e.height == CANVAS && e.byteLen in 1..MAX_BYTES
                if (!ok) throw VerifyError.BadEntry(i)
                Entry(e.sha256.toByteArray(), e.emoji, e.byteLen)
            }
            val signature = manifest.signature.toByteArray()
            if (signature.isEmpty()) {
                if (!allowUnsigned) throw VerifyError.Unsigned
            } else if (checkSignature) {
                val canonical = canonicalBytes(manifest)
                val valid = trustedKeys.any { key ->
                    runCatching { Ed25519Verify(key).verify(signature, canonical); true }.getOrDefault(false)
                }
                if (!valid) throw VerifyError.BadSignature
            }
            return StickerPack(id, manifest.title, manifest.publisher, entries)
        }
    }
}
