package com.construct.messenger.data.api

import io.grpc.Status
import io.grpc.StatusException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.flow
import shared.proto.services.v1.MediaServiceOuterClass.DownloadMediaRequest
import shared.proto.services.v1.MediaServiceOuterClass.GenerateUploadTokenRequest
import shared.proto.services.v1.MediaServiceOuterClass.UploadMediaRequest

/**
 * `MediaService` RPCs. **Canon:** iOS `MediaServiceClient`.
 *
 * The server stores what it is given — ciphertext — for 7 days and serves it to whoever names its
 * id; see `construct-server/media-service`.
 */
@Singleton
class MediaService @Inject constructor(
    private val grpcClient: GrpcClient,
) {
    /** The media store no longer has it: expired (7 days) or never existed. Not worth a retry. */
    class NotFound(mediaId: String) : Exception("media ${mediaId.take(8)}… not found")

    /**
     * The encrypted blob of [mediaId], whole. Over the channel with no token (see
     * [GrpcClient.publicMedia]). The server streams it in 4 MiB messages — the gRPC default
     * inbound cap — so the cap is raised to fit one with its framing.
     */
    suspend fun download(mediaId: String): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            grpcClient.publicMedia
                .withMaxInboundMessageSize(MAX_MESSAGE_BYTES)
                .withDeadlineAfter(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
                .downloadMedia(DownloadMediaRequest.newBuilder().setMediaId(mediaId).build())
                .collect { chunk ->
                    if (out.size().toLong() + chunk.chunk.size() > MAX_BLOB_BYTES) {
                        throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription("media over $MAX_BLOB_BYTES bytes"))
                    }
                    chunk.chunk.writeTo(out)
                }
        } catch (e: StatusException) {
            if (e.status.code == Status.Code.NOT_FOUND) throw NotFound(mediaId)
            throw e
        }
        return out.toByteArray()
    }

    class Uploaded(val mediaId: String, val downloadUrl: String)

    /**
     * Put an encrypted [blob] in the store: a one-time token, then the blob in 64 KiB messages —
     * the token on every one and the hex SHA-256 of the blob, as iOS `MediaServiceClient.uploadMedia`
     * sends them — both on the authenticated channel, the same one, as the token is bound to it.
     *
     * The token request says how big the blob is and nothing else: iOS also names the file's
     * type, which the store neither checks nor keeps.
     */
    suspend fun upload(blob: ByteArray, sha256: ByteArray, onProgress: (Float) -> Unit = {}): Uploaded {
        val stub = grpcClient.media.withDeadlineAfter(UPLOAD_TIMEOUT_S, TimeUnit.SECONDS)
        val token = stub.generateUploadToken(
            GenerateUploadTokenRequest.newBuilder().setExpectedSize(blob.size.toLong()).build(),
        ).uploadToken
        val hashHex = sha256.joinToString("") { "%02x".format(it) }
        val total = maxOf(1, (blob.size + UPLOAD_CHUNK - 1) / UPLOAD_CHUNK)
        val requests = flow {
            for (i in 0 until total) {
                val start = i * UPLOAD_CHUNK
                val end = minOf(start + UPLOAD_CHUNK, blob.size)
                emit(
                    UploadMediaRequest.newBuilder()
                        .setUploadToken(token)
                        .setChunk(ByteString.copyFrom(blob, start, end - start))
                        .setChunkNumber(i)
                        .setIsLast(i == total - 1)
                        .setTotalSize(blob.size.toLong())
                        .setFileHash(hashHex)
                        .build(),
                )
                // The last 5 % is the store putting it together and answering.
                onProgress(minOf(0.95f, end.toFloat() / blob.size))
            }
        }
        val response = stub.uploadMedia(requests)
        onProgress(1f)
        return Uploaded(response.mediaId, response.downloadUrl)
    }

    companion object {
        /** iOS `NetworkTiming.mediaUploadTimeout`. */
        private const val UPLOAD_TIMEOUT_S = 300L
        private const val UPLOAD_CHUNK = 64 * 1024

        /** iOS `NetworkTiming.mediaDownloadTimeout`. */
        private const val DOWNLOAD_TIMEOUT_S = 180L
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

        /** The server refuses uploads over 100 MiB (`MEDIA_MAX_FILE_SIZE`); a little room over it. */
        const val MAX_BLOB_BYTES = 110L * 1024 * 1024
    }
}
