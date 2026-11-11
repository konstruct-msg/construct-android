package com.construct.messenger.data.api

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.local.AckStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.StreamCursorHold
import uniffi.construct_core.StreamCursorOutcome
import uniffi.construct_core.StreamCursorState

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
 * The stream resume cursor — the `since_cursor` sent on Subscribe and on GetPendingMessages.
 *
 * The rule is the core's (construct-rules `stream_cursor`, [StreamCursorState]), one for every
 * client: the cursor never advances past a message that has not reached a durable end, since the
 * server trims its offline queue up to it. Entries are kept in arrival order and the cursor moves
 * over the resolved run at the front. Until 2026-11-11 this class held its own copy of the iOS
 * rule; construct-client's third copy had drifted and stored the cursor of whichever message
 * finished last (todo 299).
 *
 * What stays here: where the cursor is stored ([StreamCursorStore]), and one reading of
 * "resolved" the core cannot make — an id [AckStore] already holds is done. Every path that
 * finishes with a message (decrypted and persisted, a control handled, a dead init given up) ends
 * in `markProcessed`, including those that never report here: a message the core held and drained
 * after an init, and a stream copy the router dropped as a duplicate of one the drain handled. So
 * after every change the entry at the front is checked against the store, as before.
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

    private val entries = StreamCursorState()
    private var committed: String? = null

    /** The position to resume from: sent as `since_cursor`. */
    fun committedCursor(): String? = synchronized(this) { committed ?: store.load() }

    /** Drop in-flight tracking on each (re)connect; the replay from the committed cursor
     * re-tracks anything that had not advanced. */
    fun reset() = synchronized(this) {
        entries.reset()
        committed = null
    }

    /** Record a stream entry in arrival order, before it is handled. */
    fun track(messageId: String, cursor: String) = synchronized(this) {
        entries.track(messageId, cursor, System.currentTimeMillis())
    }

    /** A frame that carries no recoverable data (a receipt) still goes through the FIFO, so its
     * cursor cannot leapfrog an earlier message that is still held. */
    fun trackResolved(cursor: String) = synchronized(this) {
        save(entries.trackResolved(cursor, System.currentTimeMillis()))
        releaseProcessed()
    }

    fun report(messageId: String, outcome: Outcome) = synchronized(this) {
        save(entries.report(messageId, outcome.core))
        releaseProcessed()
    }

    fun resolve(messageId: String) = report(messageId, Outcome.Durable)

    /** The entry holding the cursor back, for the log line that names a stall. */
    fun headBlocker(): String? = synchronized(this) {
        entries.headBlocker()
            ?.takeUnless { ackStore.isProcessed(it.messageId) }
            ?.let {
                val hold = when (it.hold) {
                    StreamCursorHold.PENDING -> "Pending"
                    StreamCursorHold.DEFERRED -> "Deferred"
                }
                "${it.messageId.take(8)}… $hold"
            }
    }

    /** An id the ack store holds is done: resolve the front while it is one. */
    private fun releaseProcessed() {
        while (true) {
            val head = entries.headBlocker() ?: return
            if (!ackStore.isProcessed(head.messageId)) return
            save(entries.resolve(head.messageId))
        }
    }

    private fun save(cursor: String?) {
        if (cursor == null) return
        committed = cursor
        store.save(cursor)
        Log.d(TAG, "cursor committed; ${entries.inFlight()} held")
    }

    private val Outcome.core: StreamCursorOutcome
        get() = when (this) {
            Outcome.Durable -> StreamCursorOutcome.DURABLE
            Outcome.Deferred -> StreamCursorOutcome.DEFERRED
            Outcome.Skip -> StreamCursorOutcome.SKIP
        }

    private companion object {
        const val TAG = "StreamCursor"
    }
}
