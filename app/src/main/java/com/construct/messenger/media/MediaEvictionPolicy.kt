package com.construct.messenger.media

/**
 * Which downloaded media may be deleted to reclaim space, and which is the only copy left.
 *
 * The store keeps an uploaded object 7 days from upload; a download does not extend it. After that
 * the blob is gone for everyone, and a photo received nine days ago exists in one place: this
 * device. The only clock we have is when *we* downloaded a file (its modification time — reads do
 * not touch it), and that proves only one thing: a file downloaded 7 days ago or more is certainly
 * gone from the store. One downloaded since then *might* still be there.
 *
 * So the quota evicts only the newer files, oldest of those first, and stops when it is met; if
 * that is not enough it stays over quota rather than delete photos that exist nowhere else. This
 * inverts LRU on purpose: a re-download costs bytes, a wrong deletion costs the picture.
 *
 * **Canon:** iOS `MediaEvictionPolicy.swift`.
 */
object MediaEvictionPolicy {
    /** How long the store keeps an object, from upload (media-service `MEDIA_FILE_TTL_SECONDS`). */
    const val SERVER_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    class Candidate(val name: String, val bytes: Long, val msSinceDownload: Long)

    fun mayEvict(msSinceDownload: Long): Boolean = msSinceDownload < SERVER_RETENTION_MS

    /** Files to delete, in order, to bring [totalBytes] under [quotaBytes]; maybe not enough. */
    fun filesToEvict(candidates: List<Candidate>, totalBytes: Long, quotaBytes: Long): List<String> {
        if (quotaBytes <= 0 || totalBytes <= quotaBytes) return emptyList()
        var remaining = totalBytes
        val doomed = mutableListOf<String>()
        for (file in candidates.filter { mayEvict(it.msSinceDownload) }.sortedByDescending { it.msSinceDownload }) {
            if (remaining <= quotaBytes) break
            doomed += file.name
            remaining -= file.bytes
        }
        return doomed
    }
}
