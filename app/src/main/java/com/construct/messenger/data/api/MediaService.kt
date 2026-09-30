package com.construct.messenger.data.api

import io.grpc.Status
import io.grpc.StatusException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.MediaServiceOuterClass.DownloadMediaRequest

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

    companion object {
        /** iOS `NetworkTiming.mediaDownloadTimeout`. */
        private const val DOWNLOAD_TIMEOUT_S = 180L
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

        /** The server refuses uploads over 100 MiB (`MEDIA_MAX_FILE_SIZE`); a little room over it. */
        const val MAX_BLOB_BYTES = 110L * 1024 * 1024
    }
}
