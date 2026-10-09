package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.data.api.MediaService
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.FileContent
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

    /**
     * Keep [blob], a photo of ours sealed and not yet uploaded, under [localId], so its bubble
     * shows it at once and the upload can be retried from it.
     */
    suspend fun stage(localId: String, blob: ByteArray)

    /**
     * Upload what [stage] kept under [localId]; the copy is then filed under the id the store
     * gave it, so the sender never downloads its own photo.
     */
    suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded

    /**
     * [item], a photo or video, decrypted into the shared gallery (`Pictures/Konstruct`,
     * `Movies/Konstruct`) — the iOS viewer's "Save Image". Android 10 and later only: there it
     * needs no permission; before it, writing there would ask for storage, and the viewer offers
     * Share instead.
     */
    suspend fun saveToGallery(item: MediaItem)

    /**
     * [item], a received file, decrypted into a file another app can be handed: a content URI
     * for [name], readable only through the grant that goes with it. The copy is deleted an hour
     * later, when the next file is opened, or when the app starts.
     */
    suspend fun openable(item: MediaItem, name: String): android.net.Uri
}

class MediaUnavailable(message: String) : Exception(message)

/**
 * **Canon:** iOS `MediaManager.downloadAndDecrypt` — disk cache, then the store; one download per
 * id however many ask; a `notFound` remembered 30 minutes.
 *
 * What is cached is the blob as the store served it, still encrypted, under `files/media/<id>`:
 * the key stays in the message row, and a copy of the file alone reads as nothing. (iOS seals
 * its decrypted copy under a device key instead; the effect at rest is the same.) Its bounds —
 * the limit after each download, "keep media for", clearing — are [StorageRepository]'s.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val mediaService: MediaService,
    private val storage: StorageRepository,
) : MediaRepository {
    private val dir = File(context.filesDir, StorageRepository.MEDIA_DIR)
    private val openDir = File(context.cacheDir, OPEN_DIR).also { it.deleteRecursively() }
    private val authority = "${context.packageName}.media"
    private val appContext = context
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

    override suspend fun stage(localId: String, blob: ByteArray) = withContext(Dispatchers.IO) {
        val name = fileName(localId) ?: error("malformed local id")
        dir.mkdirs()
        File(dir, name).writeBytes(blob)
    }

    override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded = withContext(Dispatchers.IO) {
        val staged = File(dir, fileName(localId) ?: error("malformed local id"))
        val blob = staged.readBytes()
        var attempt = 0
        while (true) {
            try {
                val uploaded = mediaService.upload(blob, sha256)
                fileName(uploaded.mediaId)?.let { staged.renameTo(File(dir, it)) }
                return@withContext uploaded
            } catch (e: io.grpc.StatusException) {
                // iOS `NetworkTiming`: again after 3 s and 6 s, on what the network can cause.
                if (attempt >= UPLOAD_RETRY_MS.size || e.status.code !in RETRYABLE) throw e
                Log.w(TAG, "upload of ${localId.take(14)}… failed (${e.status.code}) — again")
                kotlinx.coroutines.delay(UPLOAD_RETRY_MS[attempt++])
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override suspend fun saveToGallery(item: MediaItem) = withContext(Dispatchers.IO) {
        check(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) { "needs Android 10" }
        val plain = bytes(item)
        val video = item.isVideo
        val collection = if (video) {
            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val mime = item.mimeType.ifBlank { if (video) "video/mp4" else "image/jpeg" }
        val name = item.filename?.takeIf { it.isNotBlank() }?.let(FileContent::safeName)
            ?: "konstruct-${System.currentTimeMillis()}.${android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"}"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES) + "/Konstruct",
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(collection, values) ?: error("gallery refused the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(plain) } ?: error("no output stream")
            resolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        Unit
    }

    override suspend fun openable(item: MediaItem, name: String): android.net.Uri = withContext(Dispatchers.IO) {
        val bytes = FileContent.unpacked(bytes(item), item.mimeType)
        val cutoff = System.currentTimeMillis() - OPEN_KEEP_MS
        openDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val folder = File(openDir, fileName(item.mediaId) ?: throw MediaUnavailable("malformed media id")).apply { mkdirs() }
        val file = File(folder, FileContent.safeName(name))
        file.writeBytes(bytes)
        androidx.core.content.FileProvider.getUriForFile(appContext, authority, file)
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
                    // Each download can take the store past its limit (Settings → Data & storage).
                    storage.evictToQuota()
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
        const val OPEN_DIR = "open"
        private const val OPEN_KEEP_MS = 60 * 60 * 1000L
        private val UPLOAD_RETRY_MS = listOf(3_000L, 6_000L)
        private val RETRYABLE = setOf(
            io.grpc.Status.Code.CANCELLED,
            io.grpc.Status.Code.UNAVAILABLE,
            io.grpc.Status.Code.DEADLINE_EXCEEDED,
            io.grpc.Status.Code.UNKNOWN,
        )
        private val ID = Regex("[0-9A-Za-z-]{1,64}")

        /** The server mints UUIDs; anything else never becomes a path. */
        fun fileName(mediaId: String): String? = mediaId.lowercase().takeIf { ID.matches(it) }
    }
}
