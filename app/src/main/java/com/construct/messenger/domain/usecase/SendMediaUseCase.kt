package com.construct.messenger.domain.usecase

import android.net.Uri
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.ImagePreparer
import com.construct.messenger.media.MediaCrypto
import com.construct.messenger.media.PickedFiles
import com.construct.messenger.media.VideoPreparer
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.TextWire
import com.google.protobuf.ByteString
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaDimensions
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaType
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.VoiceMessage

/**
 * Photos sent as one album. **Canon:** iOS `ChatSendCoordinator` + `MediaUploadManager` +
 * `MediaWireCodec.albumContent`: every photo is its own upload, at most 4 at a time, in order;
 * the message is `MessageContent.media_album` — always an album, one photo or many — with the
 * caption on the album and the quote there too.
 *
 * The row appears first, with each photo sealed and kept under a local id, so the bubble shows
 * the photos while they upload; after the uploads it is rewritten with the store's ids and sent.
 * A failed upload fails the message: nothing is sent that the recipient could not open.
 */
class SendMediaUseCase @Inject constructor(
    private val images: ImagePreparer,
    private val files: PickedFiles,
    private val videos: VideoPreparer,
    private val media: MediaRepository,
    private val sendMessage: SendMessageUseCase,
) {
    suspend fun photos(contactId: String, uris: List<Uri>, caption: String, reply: ReplyRef?): SendOutcome {
        require(uris.isNotEmpty())
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()

        val staged = try {
            withContext(Dispatchers.Default) {
                uris.map { uri ->
                    val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
                    if (videos.isVideo(uri)) {
                        val video = videos.prepare(uri)
                        val sealed = MediaCrypto.seal(video.mp4)
                        media.stage(localId, sealed.blob)
                        Staged(localId, sealed, video = video)
                    } else {
                        val photo = images.prepare(uri)
                        val sealed = MediaCrypto.seal(photo.jpeg)
                        media.stage(localId, sealed.blob)
                        Staged(localId, sealed, photo = photo)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "photos could not be prepared", e)
            return SendOutcome.Failed(messageId, "photo unreadable")
        }

        val caption = caption.trim()
        sendMessage.persistMedia(contactId, messageId, timestampMs, stored(staged.map { it.item(it.localId, "") }, caption, reply), reply)

        val uploaded = try {
            coroutineScope {
                val gate = Semaphore(PARALLEL_UPLOADS)
                staged.map { s ->
                    async { gate.withPermit { s.item(media.upload(s.localId, s.sealed.sha256)) } }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upload for ${messageId.take(8)}… failed", e)
            sendMessage.markFailed(messageId)
            return SendOutcome.Failed(messageId, "upload failed")
        }

        val album = stored(uploaded, caption, reply)
        sendMessage.replaceMedia(messageId, album)
        val content = MessageContent.newBuilder().setMediaAlbum(MediaAlbumMessage.parseFrom(album.bytes)).build()
        return sendMessage.deliverPrepared(contactId, messageId, timestampMs, content.toByteArray())
    }

    /**
     * Documents, as one album of files. **Canon:** iOS `MediaManager.uploadFile` +
     * `MediaWireCodec.fileAlbumContent` — each file whole, its type as the system names it (the
     * album's items say what kind it is, `MediaType` from the type as iOS maps it), its name, and
     * its own size. Never compressed: iOS's receiver cannot tell a compressed file (the flag is
     * not on the wire), so Android does not make any.
     */
    suspend fun files(contactId: String, uris: List<Uri>, caption: String): SendOutcome {
        require(uris.isNotEmpty())
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val staged = try {
            withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val picked = files.read(uri)
                    val sealed = MediaCrypto.seal(picked.bytes)
                    val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
                    media.stage(localId, sealed.blob)
                    StagedFile(localId, sealed, picked.name, picked.mime, picked.bytes.size.toLong())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "files could not be read", e)
            return SendOutcome.Failed(messageId, if (e is PickedFiles.TooLarge) "file too large" else "file unreadable")
        }
        val caption = caption.trim()
        sendMessage.persistMedia(contactId, messageId, timestampMs, stored(staged.map { it.item(it.localId, "") }, caption, null), null)
        val uploaded = try {
            coroutineScope {
                val gate = Semaphore(PARALLEL_UPLOADS)
                staged.map { f -> async { gate.withPermit { media.upload(f.localId, f.sealed.sha256).let { f.item(it.mediaId, it.downloadUrl) } } } }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "file upload for ${messageId.take(8)}… failed", e)
            sendMessage.markFailed(messageId)
            return SendOutcome.Failed(messageId, "upload failed")
        }
        val album = stored(uploaded, caption, null)
        sendMessage.replaceMedia(messageId, album)
        return sendMessage.deliverPrepared(contactId, messageId, timestampMs, MediaWire.content(album.kind, album.bytes)!!)
    }

    private class StagedFile(val localId: String, val sealed: MediaCrypto.Sealed, val name: String, val mime: String, val size: Long) {
        fun item(mediaId: String, url: String): MediaMessage = MediaMessage.newBuilder()
            .setMediaType(MediaWire.mediaTypeOf(mime))
            .setFileUrl(url)
            .setEncryptionKey(ByteString.copyFrom(sealed.key))
            .setFileHash(ByteString.copyFrom(sealed.sha256))
            .setFileSize(size)
            .setMimeType(mime)
            .setFilename(name)
            .setMediaId(mediaId)
            .build()
    }

    /**
     * A voice note. **Canon:** iOS `MediaWireCodec.voiceMessageContent` — `MessageContent.voice`
     * with the key and the blob's digest, the length, the waveform as 0–255, and — there being
     * no field for it — the media id in `codec` as `audio/m4a|<id>|<size of the recording>`.
     * The recording is sealed, kept under a local id for the bubble, and deleted from the cache.
     */
    suspend fun voice(contactId: String, recording: File, durationMs: Long, waveform: List<Float>): SendOutcome {
        val messageId = UUID.randomUUID().toString().lowercase()
        val timestampMs = System.currentTimeMillis()
        val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
        val audio = withContext(Dispatchers.IO) { recording.readBytes().also { recording.delete() } }
        val sealed = MediaCrypto.seal(audio)
        media.stage(localId, sealed.blob)
        fun wire(mediaId: String, url: String) = MediaWire.stored(
            MessageContent.newBuilder().setVoice(
                VoiceMessage.newBuilder()
                    .setFileUrl(url)
                    .setEncryptionKey(ByteString.copyFrom(sealed.key))
                    .setFileHash(ByteString.copyFrom(sealed.sha256))
                    .setDurationMs(durationMs.toInt())
                    .addAllWaveform(waveform.map { (it * 255).roundToInt().coerceIn(0, 255) })
                    .setCodec("$VOICE_MIME|$mediaId|${audio.size}"),
            ).build(),
        )!!
        sendMessage.persistMedia(contactId, messageId, timestampMs, wire(localId, ""), null)
        val uploaded = try {
            media.upload(localId, sealed.sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "voice upload for ${messageId.take(8)}… failed", e)
            sendMessage.markFailed(messageId)
            return SendOutcome.Failed(messageId, "upload failed")
        }
        val stored = wire(uploaded.mediaId, uploaded.downloadUrl)
        sendMessage.replaceMedia(messageId, stored)
        return sendMessage.deliverPrepared(contactId, messageId, timestampMs, MediaWire.content(stored.kind, stored.bytes)!!)
    }

    private class Staged(
        val localId: String,
        val sealed: MediaCrypto.Sealed,
        val photo: ImagePreparer.Prepared? = null,
        val video: VideoPreparer.Prepared? = null,
    ) {
        fun item(uploaded: com.construct.messenger.data.api.MediaService.Uploaded) = item(uploaded.mediaId, uploaded.downloadUrl)

        /**
         * iOS `MediaWireCodec.albumContent`: the blob's size and digest, pixel dimensions, a
         * BlurHash. A photo has no thumbnail when it has a BlurHash; a video always has its
         * poster, and its length (`InlinePreviewPolicy`).
         */
        fun item(mediaId: String, url: String): MediaMessage {
            val m = MediaMessage.newBuilder()
                .setFileUrl(url)
                .setEncryptionKey(ByteString.copyFrom(sealed.key))
                .setFileHash(ByteString.copyFrom(sealed.sha256))
                .setFileSize(sealed.blob.size.toLong())
                .setMediaId(mediaId)
            val v = video
            if (v != null) {
                m.setMediaType(MediaType.MEDIA_TYPE_VIDEO).setMimeType("video/mp4")
                if (v.width > 0 && v.height > 0) m.setDimensions(MediaDimensions.newBuilder().setWidth(v.width).setHeight(v.height))
                m.setDurationMs(v.durationMs.toInt())
                v.thumbnail?.let { m.setThumbnail(ByteString.copyFrom(it)) }
                v.blurhash?.let { m.setBlurhash(it) }
            } else {
                val p = photo!!
                m.setMediaType(MediaType.MEDIA_TYPE_IMAGE).setMimeType("image/jpeg")
                m.setDimensions(MediaDimensions.newBuilder().setWidth(p.width).setHeight(p.height))
                p.blurhash?.let { m.setBlurhash(it) }
            }
            return m.build()
        }
    }

    private fun stored(items: List<MediaMessage>, caption: String, reply: ReplyRef?): MediaWire.Stored {
        val album = MediaAlbumMessage.newBuilder().addAllItems(items)
        if (caption.isNotEmpty()) album.caption = caption
        TextWire.quoted(reply)?.let { album.quoted = it }
        return MediaWire.stored(MessageContent.newBuilder().setMediaAlbum(album).build())!!
    }

    private companion object {
        const val TAG = "SendMediaUseCase"

        /** iOS `MediaUploadManager`: four at a time. */
        const val PARALLEL_UPLOADS = 4

        /** What iOS names a voice note's type — not a registered one, but the one it reads. */
        const val VOICE_MIME = "audio/m4a"
    }
}
