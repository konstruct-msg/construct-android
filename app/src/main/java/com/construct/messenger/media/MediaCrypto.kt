package com.construct.messenger.media

import uniffi.construct_core.mediaMaxPlaintextLen
import uniffi.construct_core.openMedia
import uniffi.construct_core.sealMedia

/**
 * A media blob — the encrypted file a media message points to — sealed and opened by the core
 * (`media.rs`, 0.33.0): one format on every client, padded to a Padmé bucket so the server sees a
 * size class rather than the size, and sealed with associated data so a padded blob cannot be
 * read as an older one. [open] also opens the pre-0.33 blob (`nonce ‖ ciphertext ‖ tag`, no
 * padding) that iOS and Android uploaded until then.
 *
 * No cipher of its own: until 2026-10-04 this file was AES-GCM on the platform, and a platform
 * cipher is how two clients come to disagree about a format (construct-docs TODO 113, 114).
 * **Canon:** iOS `MediaServiceClient` / `MediaUploadService` on core 0.33.
 */
object MediaCrypto {
    class Sealed(val key: ByteArray, val blob: ByteArray, val sha256: ByteArray)

    /** The largest file [seal] takes: the store's 100 000 000-byte blob less the seal's bytes. */
    val maxPlaintextBytes: Long get() = mediaMaxPlaintextLen().toLong()

    /** A fresh key, the blob to upload and its SHA-256 for the message. Throws for a file longer
     * than [maxPlaintextBytes]. */
    fun seal(plaintext: ByteArray): Sealed = sealMedia(plaintext).let { Sealed(it.key, it.blob, it.sha256) }

    /** Throws when [key] is not the one [blob] was sealed with, or [blob] was altered. */
    fun open(blob: ByteArray, key: ByteArray): ByteArray = openMedia(key, blob)
}
