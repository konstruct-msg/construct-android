package com.construct.messenger.data.api

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.local.AckStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the committed resume cursor lives between runs. The same prefs file and key the stream
 * used before [StreamCursorTracker] existed, so an installed app keeps its position.
 */
@Singleton
class StreamCursorStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_CURSOR, null)

    fun save(cursor: String) {
        prefs.edit().putString(KEY_CURSOR, cursor).apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "message_stream_prefs"
        const val KEY_CURSOR = "since_cursor"
    }
}

/**
 * ACK-driven advance of the stream resume cursor — the `since_cursor` sent on Subscribe and on
 * GetPendingMessages.
 *
 * **Canon:** iOS `StreamCursorTracker.swift`.
 *
 * THE INVARIANT: the committed cursor never advances past a message that has not reached a
 * durable end. The server trims its offline queue up to the cursor it is sent; advancing past a
 * message held behind a session init tells the server to delete it, and if the app then dies the
 * message is gone from both sides. Before this class the stream committed every frame's cursor on
 * arrival, before anything was done with it.
 *
 * Model: a FIFO of stream entries in arrival order. The committed cursor advances over the longest
 * contiguous run of resolved entries from the front, never skipping a pending or deferred one.
 *
 * "Resolved" is either reported ([report] / [resolve]) or read from [AckStore]: every path that
 * finishes with a message — decrypted and persisted, a control handled, a dead init given up —
 * ends in `markProcessed`, so a processed id is durable by definition. That covers the paths
 * that never report here: a message the core held and drained after an init, and a stream copy
 * the router dropped as a duplicate of one the pending drain already handled.
 *
 * A missed resolution degrades to a stall — the server re-delivers, the ack store dedups — never
 * to loss. The stall clears on the next reconnect's replay.
 */
@Singleton
class StreamCursorTracker @Inject constructor(
    private val store: StreamCursorStore,
    private val ackStore: AckStore,
) {
    enum class Outcome {
        /** Durably handled → may advance the cursor. */
        Durable,
        /** Held (queued behind an init, a cooldown) → hold the cursor until it is resolved. */
        Deferred,
        /** Not this caller's to decide → leave the entry as it is. */
        Skip,
    }

    private enum class State { Pending, Deferred, Resolved }

    private class Entry(val messageId: String, val cursor: String, var state: State)

    private val entries = ArrayDeque<Entry>()
    private var committed: String? = null

    /** The position to resume from: sent as `since_cursor`. */
    fun committedCursor(): String? = synchronized(this) { committed ?: store.load() }

    /** Drop in-flight tracking on each (re)connect; the replay from the committed cursor
     * re-tracks anything that had not advanced. */
    fun reset() = synchronized(this) {
        entries.clear()
        committed = null
    }

    /** Record a stream entry in arrival order, before it is handled. */
    fun track(messageId: String, cursor: String) = synchronized(this) {
        if (messageId.isEmpty() || cursor.isEmpty()) return@synchronized
        if (entries.any { it.messageId == messageId }) return@synchronized
        entries.addLast(Entry(messageId, cursor, State.Pending))
    }

    /** A frame that carries no recoverable data (a receipt) still goes through the FIFO, so its
     * cursor cannot leapfrog an earlier message that is still held. */
    fun trackResolved(cursor: String) {
        track(cursor, cursor)
        resolve(cursor)
    }

    fun report(messageId: String, outcome: Outcome) = synchronized(this) {
        val entry = entries.firstOrNull { it.messageId == messageId }
        when (outcome) {
            Outcome.Durable -> entry?.state = State.Resolved
            Outcome.Deferred -> if (entry?.state == State.Pending) entry.state = State.Deferred
            Outcome.Skip -> Unit
        }
        advance()
    }

    fun resolve(messageId: String) = report(messageId, Outcome.Durable)

    /** The entry holding the cursor back, for the log line that names a stall. */
    fun headBlocker(): String? = synchronized(this) {
        entries.firstOrNull()?.takeUnless { isResolved(it) }?.let { "${it.messageId.take(8)}… ${it.state}" }
    }

    private fun isResolved(entry: Entry): Boolean =
        entry.state == State.Resolved || ackStore.isProcessed(entry.messageId)

    private fun advance() {
        var newCommitted: String? = null
        while (entries.isNotEmpty() && isResolved(entries.first())) {
            newCommitted = entries.removeFirst().cursor
        }
        if (newCommitted == null || newCommitted == committed) return
        committed = newCommitted
        store.save(newCommitted)
        Log.d(TAG, "cursor committed; ${entries.size} held")
    }

    private companion object {
        const val TAG = "StreamCursor"
    }
}
