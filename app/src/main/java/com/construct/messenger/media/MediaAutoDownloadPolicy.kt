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
        /**
         * Off (owner 2026-11-22, vault todo 134): a fetch at delivery, over the stream's own
         * connection, ties the object to the device it was delivered to. Until then [UNMETERED].
         * A stored choice is kept: the preference is read first, this fills in an absent one.
         */
        val DEFAULT = NEVER
        fun of(raw: Int): MediaAutoDownload = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

/**
 * Whether a received attachment is fetched the moment its message arrives, or waits until a
 * bubble shows it. The store drops a blob 7 days after upload, so a chat left unopened for a week
 * used to lose its photos; fetching on arrival closes that, at the cost of bytes and of a timing
 * link (the download follows the delivery over the same connection) — hence the setting, off by
 * default since 2026-11-22, and [prefetchDelayMs] before an arrival fetch when it is on.
 *
 * **Canon:** iOS `MediaAutoDownloadPolicy.swift`.
 */
object MediaAutoDownloadPolicy {
    /** Above this an attachment waits for the user on Wi-Fi: a video is not bounded the way a photo is. */
    const val UNMETERED_SIZE_CEILING = 16L * 1024 * 1024

    /**
     * The wait before an arrival fetch, one per album: uniform 2–30 s. A fetch at the moment of
     * delivery ties the object to it exactly; the wait mixes it with the half-minute's other
     * deliveries — weaker, not gone (same connection). A bubble on screen or a tap does not wait.
     */
    val PREFETCH_DELAY_MS = 2_000L..30_000L

    /** [fraction] in [0, 1) — `Random.nextDouble()` — onto [PREFETCH_DELAY_MS]. */
    fun prefetchDelayMs(fraction: Double = kotlin.random.Random.nextDouble()): Long =
        PREFETCH_DELAY_MS.first +
            (fraction.coerceIn(0.0, 1.0) * (PREFETCH_DELAY_MS.last - PREFETCH_DELAY_MS.first)).toLong()

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
