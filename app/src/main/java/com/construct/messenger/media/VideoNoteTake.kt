package com.construct.messenger.media

import java.io.File

/**
 * A recorded video note as it is handed to the send: the segments in order — one per stretch
 * between pauses — and the kept part of their joined timeline, or null for all of it.
 * **Canon:** iOS `VideoNoteRecorder.finish()` + the attachment's time range.
 */
data class VideoNoteTake(val segments: List<Segment>, val trimMs: LongRange? = null) {
    data class Segment(val file: File, val durationMs: Long)

    val durationMs: Long get() = segments.sumOf { it.durationMs }

    /** What is sent, in ms. */
    val keptMs: Long get() = trimMs?.let { it.last - it.first } ?: durationMs

    fun delete() = segments.forEach { it.file.delete() }
}

/** The trim's rules. Pure. **Canon:** iOS `VideoTrimBar.moved` and `transcodeVideo(timeRange:)`. */
object VideoNoteTrim {
    /** A note shorter than this is not worth sending; the handles stop here. */
    const val SHORTEST_MS = 1_000L

    /** [range] with one end moved to [t], kept inside [durationMs] and [SHORTEST_MS] from the other. */
    fun moved(start: Boolean, t: Long, range: LongRange, durationMs: Long): LongRange {
        val shortest = minOf(SHORTEST_MS, durationMs)
        return if (start) {
            val lower = minOf(maxOf(0L, t), range.last - shortest).coerceAtLeast(0L)
            lower..range.last
        } else {
            val upper = maxOf(minOf(durationMs, t), range.first + shortest)
            range.first..minOf(durationMs, upper)
        }
    }

    /**
     * The part of each segment the joined [range] keeps, as (segment index, local start..end in
     * ms); segments outside it are left out. Null [range]: every segment whole.
     */
    fun clips(durationsMs: List<Long>, range: LongRange?): List<Pair<Int, LongRange>> {
        var offset = 0L
        return durationsMs.mapIndexedNotNull { i, d ->
            val segment = offset..(offset + d)
            offset += d
            if (range == null) return@mapIndexedNotNull i to (0L..d)
            val from = maxOf(segment.first, range.first)
            val to = minOf(segment.last, range.last)
            if (to - from <= 0) null else i to ((from - segment.first)..(to - segment.first))
        }
    }
}
