package com.construct.messenger.domain.usecase

import android.net.Uri
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.ImagePreparer
import com.construct.messenger.media.MediaCrypto
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.TextWire
import com.google.protobuf.ByteString
import java.util.UUID
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
                    val photo = images.prepare(uri)
                    val sealed = MediaCrypto.seal(photo.jpeg)
                    val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
                    media.stage(localId, sealed.blob)
                    Staged(localId, sealed, photo)
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

    private class Staged(val localId: String, val sealed: MediaCrypto.Sealed, val photo: ImagePreparer.Prepared) {
        fun item(uploaded: com.construct.messenger.data.api.MediaService.Uploaded) = item(uploaded.mediaId, uploaded.downloadUrl)

        /**
         * iOS `MediaWireCodec.albumContent` for a compressed photo: the blob's size, the digest
         * of the blob, pixel dimensions, and a BlurHash — no thumbnail when there is one.
         */
        fun item(mediaId: String, url: String): MediaMessage = MediaMessage.newBuilder()
            .setMediaType(MediaType.MEDIA_TYPE_IMAGE)
            .setFileUrl(url)
            .setEncryptionKey(ByteString.copyFrom(sealed.key))
            .setFileHash(ByteString.copyFrom(sealed.sha256))
            .setFileSize(sealed.blob.size.toLong())
            .setMimeType("image/jpeg")
            .setDimensions(MediaDimensions.newBuilder().setWidth(photo.width).setHeight(photo.height))
            .also { m -> photo.blurhash?.let { m.setBlurhash(it) } }
            .setMediaId(mediaId)
            .build()
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
    }
}
