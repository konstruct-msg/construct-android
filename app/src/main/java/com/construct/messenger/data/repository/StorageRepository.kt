package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.MediaAutoDownload
import com.construct.messenger.media.MediaEvictionPolicy
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Settings → Data & storage: the media store's limit, how long media is kept, auto-download. */
data class StorageSettings(
    /** The media store's limit in bytes; 0 = no limit. */
    val limitBytes: Long = StorageRepository.DEFAULT_LIMIT_BYTES,
    /** Media downloaded longer ago than this is removed at launch; 0 = kept until cleared. */
    val keepDays: Int = 0,
    /** Whether received attachments are fetched as their messages arrive. */
    val autoDownload: MediaAutoDownload = MediaAutoDownload.DEFAULT,
)

/**
 * The media store on disk (`files/media`, the blobs as the store served them, still encrypted) and
 * the rules that keep it in bounds — what Settings → Data & storage shows and changes.
 *
 * - The limit evicts only media the store may still have ([MediaEvictionPolicy]); it runs after
 *   every download and when the limit changes. Default 1 GiB, as iOS.
 * - "Keep media for" removes what is older, whatever it is; off by default — set, it is the user
 *   saying they do not want older media. Runs at launch and when the setting changes.
 * - "Clear" removes everything; the screen says first what cannot come back.
 *
 * Never touched: our own media not yet uploaded (`local-…`, the only copy and the retry's source),
 * downloads in progress (`.part`), and the files notes hold (vault todo 279): they are not cache.
 * When the store cannot say which those are, nothing is deleted. Keys are iOS's `@AppStorage` names.
 *
 * **Canon:** iOS `MediaManager` (`evictToQuota`, `evictOldFiles`), `DataStorageSettingsView`.
 */
@Singleton
class StorageRepository @Inject constructor(
    @param:ApplicationContext context: Context,
    private val notes: NoteStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, MEDIA_DIR)

    private val state = MutableStateFlow(
        StorageSettings(
            limitBytes = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT_BYTES),
            keepDays = prefs.getInt(KEY_KEEP_DAYS, 0),
            autoDownload = MediaAutoDownload.of(prefs.getInt(KEY_AUTO_DOWNLOAD, MediaAutoDownload.DEFAULT.raw)),
        ),
    )
    val settings: StateFlow<StorageSettings> = state.asStateFlow()

    suspend fun setLimit(bytes: Long) {
        prefs.edit().putLong(KEY_LIMIT, bytes).apply()
        state.update { it.copy(limitBytes = bytes) }
        evictToQuota()
    }

    suspend fun setKeepDays(days: Int) {
        prefs.edit().putInt(KEY_KEEP_DAYS, days).apply()
        state.update { it.copy(keepDays = days) }
        evictOld()
    }

    fun setAutoDownload(setting: MediaAutoDownload) {
        prefs.edit().putInt(KEY_AUTO_DOWNLOAD, setting.raw).apply()
        state.update { it.copy(autoDownload = setting) }
    }

    /** What the cached media takes, in bytes. */
    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) { cached(heldByNotes().orEmpty()).sumOf { it.length() } }

    /** What the files notes hold take, in bytes. */
    suspend fun notesBytes(): Long = withContext(Dispatchers.IO) {
        heldByNotes().orEmpty().sumOf { File(dir, it).length() }
    }

    /** Deletes the copies of [mediaIds], which no note and no message holds any more. */
    suspend fun deleteFiles(mediaIds: List<String>) = withContext(Dispatchers.IO) {
        mediaIds.mapNotNull(MediaRepositoryImpl::fileName).forEach { File(dir, it).delete() }
    }

    /** Removes every cached download. Returns the bytes freed. */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (file in cached(heldByNotes() ?: return@withContext 0L)) {
            val bytes = file.length()
            if (file.delete()) freed += bytes
        }
        Log.i(TAG, "cleared media cache, ${freed / 1024} KiB")
        freed
    }

    /** Brings the store under its limit, deleting only what the store may still have. */
    suspend fun evictToQuota(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val limit = state.value.limitBytes
        if (limit <= 0) return@withContext
        val files = cached(heldByNotes() ?: return@withContext)
        val total = files.sumOf { it.length() }
        if (total <= limit) return@withContext
        val candidates = files.map { MediaEvictionPolicy.Candidate(it.name, it.length(), now - it.lastModified()) }
        val doomed = MediaEvictionPolicy.filesToEvict(candidates, total, limit)
        var freed = 0L
        for (name in doomed) {
            val file = File(dir, name)
            val bytes = file.length()
            if (file.delete()) freed += bytes
        }
        if (total - freed > limit) {
            // Over the limit with nothing safe left to delete: the rest is past the store's
            // retention and exists only here. Said, not resolved — a full disk is a problem the
            // user can see and act on; a missing photo is not.
            Log.i(TAG, "media over limit by ${(total - freed - limit) / 1024} KiB — the rest are the only copies")
        } else if (doomed.isNotEmpty()) {
            Log.i(TAG, "evicted ${doomed.size} re-downloadable file(s), ${freed / 1024} KiB — limit")
        }
    }

    /** Removes media downloaded longer ago than "keep media for", when that is set. */
    suspend fun evictOld(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val days = state.value.keepDays
        if (days <= 0) return@withContext
        val cutoff = now - days * DAY_MS
        val old = cached(heldByNotes() ?: return@withContext).filter { it.lastModified() < cutoff }
        val removed = old.count { it.delete() }
        if (removed > 0) Log.i(TAG, "removed $removed cached file(s) older than $days days")
    }

    /** The names of the files notes hold; null when the store did not answer, and any file might be one. */
    private suspend fun heldByNotes(): Set<String>? = try {
        notes.heldMediaIds().mapNotNullTo(HashSet(), MediaRepositoryImpl::fileName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "which files notes hold is unknown, nothing is deleted", e)
        null
    }

    /** The cached downloads: not what notes hold, not our unsent media, not a download in progress. */
    private fun cached(held: Set<String>): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name !in held && !it.name.startsWith(MediaWire.LOCAL_PREFIX) && !it.name.endsWith(".part") }
        .orEmpty()

    companion object {
        private const val TAG = "StorageRepository"
        private const val PREFS = "storage_prefs"
        private const val KEY_LIMIT = "media.maxDiskCacheBytes"
        private const val KEY_KEEP_DAYS = "media.evictAfterDays"
        private const val KEY_AUTO_DOWNLOAD = "media.autoDownload"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Where [MediaRepositoryImpl] keeps the blobs, under `filesDir`. */
        const val MEDIA_DIR = "media"
        const val DEFAULT_LIMIT_BYTES = 1L shl 30
    }
}
