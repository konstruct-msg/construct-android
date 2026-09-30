package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.data.api.MediaService
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.MediaCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** A message's media, fetched and opened. */
interface MediaRepository {
    /**
     * The plaintext of [item]. Throws [MediaUnavailable] when it is gone for good — the store
     * keeps a blob 7 days — and anything else (network) when it may yet come.
     */
    suspend fun bytes(item: MediaItem): ByteArray
}

class MediaUnavailable(message: String) : Exception(message)

/**
 * **Canon:** iOS `MediaManager.downloadAndDecrypt` — disk cache, then the store; one download per
 * id however many ask; a `notFound` remembered 30 minutes.
 *
 * What is cached is the blob as the store served it, still encrypted, under `files/media/<id>`:
 * the key stays in the message row, and a copy of the file alone reads as nothing. (iOS seals
 * its decrypted copy under a device key instead; the effect at rest is the same.) Nothing here
 * evicts yet — the quota is the Data & Storage screen's (B7), which comes with sending.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val mediaService: MediaService,
) : MediaRepository {
    private val dir = File(context.filesDir, "media")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<ByteArray>>()
    private val missingUntil = ConcurrentHashMap<String, Long>()

    override suspend fun bytes(item: MediaItem): ByteArray = withContext(Dispatchers.IO) {
        val name = fileName(item.mediaId) ?: throw MediaUnavailable("malformed media id")
        val file = File(dir, name)
        val cached = file.takeIf { it.exists() }?.readBytes()
        val blob = cached ?: blob(item.mediaId, file)
        try {
            MediaCrypto.open(blob, item.key)
        } catch (e: Exception) {
            // A key that does not open it: the sender's error, or a blob that is not theirs.
            if (cached != null) file.delete()
            Log.w(TAG, "media ${item.mediaId.take(8)}… does not open: ${e.javaClass.simpleName}")
            throw MediaUnavailable("does not decrypt")
        }
    }

    private suspend fun blob(mediaId: String, file: File): ByteArray {
        missingUntil[mediaId]?.let { until ->
            if (System.currentTimeMillis() < until) throw MediaUnavailable("not found")
            missingUntil.remove(mediaId)
        }
        val job = inFlight.computeIfAbsent(mediaId) {
            scope.async {
                try {
                    val blob = mediaService.download(mediaId)
                    dir.mkdirs()
                    val tmp = File(dir, "${file.name}.part")
                    tmp.writeBytes(blob)
                    tmp.renameTo(file)
                    blob
                } finally {
                    inFlight.remove(mediaId)
                }
            }
        }
        return try {
            job.await()
        } catch (e: MediaService.NotFound) {
            missingUntil[mediaId] = System.currentTimeMillis() + NOT_FOUND_MS
            throw MediaUnavailable("not found")
        }
    }

    companion object {
        private const val TAG = "MediaRepository"
        private const val NOT_FOUND_MS = 30 * 60 * 1000L
        private val ID = Regex("[0-9A-Za-z-]{1,64}")

        /** The server mints UUIDs; anything else never becomes a path. */
        fun fileName(mediaId: String): String? = mediaId.lowercase().takeIf { ID.matches(it) }
    }
}
