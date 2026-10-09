package com.construct.messenger.media

/** Settings → Data & storage → Auto-download media. Raw values are iOS's `MediaAutoDownloadSetting`. */
enum class MediaAutoDownload(val raw: Int) {
    /** Never ahead: media arrives when the chat shows it. A chat not opened within 7 days loses its media. */
    NEVER(0),

    /** On an unmetered network only — not on mobile data, not with Data Saver on. */
    UNMETERED(1),

    /** On any network. */
    ALWAYS(2),
    ;

    companion object {
        val DEFAULT = UNMETERED
        fun of(raw: Int): MediaAutoDownload = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

/**
 * Whether a received attachment is fetched the moment its message arrives, or waits until a
 * bubble shows it. The store drops a blob 7 days after upload, so a chat left unopened for a week
 * used to lose its photos; fetching on arrival closes that, at the cost of bytes — hence the
 * setting, Wi-Fi only by default: on mobile data nothing changes.
 *
 * **Canon:** iOS `MediaAutoDownloadPolicy.swift`.
 */
object MediaAutoDownloadPolicy {
    /** Above this an attachment waits for the user on Wi-Fi: a video is not bounded the way a photo is. */
    const val UNMETERED_SIZE_CEILING = 16L * 1024 * 1024

    /**
     * [metered]: mobile data or a hotspot. [constrained]: Data Saver — an instruction to hold back on
     * exactly this kind of speculative transfer, so it counts as metered even on Wi-Fi.
     */
    fun shouldFetchOnArrival(setting: MediaAutoDownload, sizeBytes: Long, metered: Boolean, constrained: Boolean): Boolean =
        when (setting) {
            MediaAutoDownload.NEVER -> false
            MediaAutoDownload.ALWAYS -> true
            MediaAutoDownload.UNMETERED -> !metered && !constrained && sizeBytes <= UNMETERED_SIZE_CEILING
        }
}
