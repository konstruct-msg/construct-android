package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.MediaEvictionPolicy
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Settings → Data & storage: the media store's limit and how long media is kept. */
data class StorageSettings(
    /** The media store's limit in bytes; 0 = no limit. */
    val limitBytes: Long = StorageRepository.DEFAULT_LIMIT_BYTES,
    /** Media downloaded longer ago than this is removed at launch; 0 = kept until cleared. */
    val keepDays: Int = 0,
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
 * Never touched: our own media not yet uploaded (`local-…`, the only copy and the retry's source)
 * and downloads in progress (`.part`). Keys are iOS's `@AppStorage` names.
 *
 * **Canon:** iOS `MediaManager` (`evictToQuota`, `evictOldFiles`), `DataStorageSettingsView`.
 */
@Singleton
class StorageRepository @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, MEDIA_DIR)

    private val state = MutableStateFlow(
        StorageSettings(
            limitBytes = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT_BYTES),
            keepDays = prefs.getInt(KEY_KEEP_DAYS, 0),
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

    /** What the cached media takes, in bytes. */
    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) { cached().sumOf { it.length() } }

    /** Removes every cached download. Returns the bytes freed. */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (file in cached()) {
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
        val files = cached()
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
        val old = cached().filter { it.lastModified() < cutoff }
        val removed = old.count { it.delete() }
        if (removed > 0) Log.i(TAG, "removed $removed cached file(s) older than $days days")
    }

    /** The cached downloads: not our unsent media, not a download in progress. */
    private fun cached(): List<File> = dir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(MediaWire.LOCAL_PREFIX) && !it.name.endsWith(".part") }
        .orEmpty()

    companion object {
        private const val TAG = "StorageRepository"
        private const val PREFS = "storage_prefs"
        private const val KEY_LIMIT = "media.maxDiskCacheBytes"
        private const val KEY_KEEP_DAYS = "media.evictAfterDays"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Where [MediaRepositoryImpl] keeps the blobs, under `filesDir`. */
        const val MEDIA_DIR = "media"
        const val DEFAULT_LIMIT_BYTES = 1L shl 30
    }
}
