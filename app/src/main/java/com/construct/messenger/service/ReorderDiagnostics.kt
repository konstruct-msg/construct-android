package com.construct.messenger.service

import com.construct.messenger.diagnostics.Log
import uniffi.construct_core.ReorderStats

/**
 * PQR-4 (construct-docs TODO 64.3): how late messages arrive, as the core counts them since
 * start. **Canon:** iOS `RuntimeDiagnostics.sample` — the same `REORDER` line, written when the
 * counters move rather than on every sample, and as an error once a message was lost because its
 * PQ epoch's chain was already gone (`evicted`). The others size how deep reordering gets, which
 * is what the bound on held epochs is to be chosen from. Local only, like every log line.
 */
class ReorderDiagnostics(private val read: () -> ReorderStats?) {
    private var last: ReorderStats? = null

    /** Writes the line if the counters changed since the last one written; returns it, or null. */
    fun sample(): String? {
        val stats = read() ?: return null
        if (stats == last) return null
        last = stats
        val text = line(stats)
        if (stats.evictedEpochFailures > 0uL) Log.e(TAG, text) else Log.i(TAG, text)
        return text
    }

    companion object {
        private const val TAG = "Runtime"

        fun line(stats: ReorderStats): String = "REORDER " + listOf(
            "decrypted=${stats.decrypted}",
            "prev_epoch=${stats.previousEpoch}",
            "older_epoch=${stats.olderEpoch}",
            "max_lag=${stats.maxEpochLag}",
            "max_skip=${stats.maxSkipDepth}",
            "evicted=${stats.evictedEpochFailures}",
        ).joinToString(" ")
    }
}
