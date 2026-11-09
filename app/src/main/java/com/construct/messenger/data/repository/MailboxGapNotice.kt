package com.construct.messenger.data.repository

import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesResponse
import shared.proto.services.v1.MessagingServiceOuterClass.MessageStreamResponse
import uniffi.construct_core.MailboxGapState
import shared.proto.services.v1.MessagingServiceOuterClass.MailboxGap as WireGap

/**
 * The server's queue keeps messages for a limited time and count; when this device comes back
 * later than that, what it trimmed is gone. The server says so (`MailboxGap`, on a fetch and on
 * the stream) and the person is told with one notice above the chat list until they close it
 * (construct-docs `decisions/mailbox-gap-is-reported.md`, TODO 79). Whose messages they were
 * nobody knows — the server only knows the interval. Nothing is refetched or asked for.
 *
 * The rule is the core's ([MailboxGapState], construct-rules `mailbox_gap`), one for every client:
 * it keeps the server's reports, open and closed, and a report the person closed does not raise
 * the notice again — the server repeats it on every stream until the cursor moves. Until
 * 2026-11-09 the rule was written here, keeping only dates, so a closed notice came back on every
 * connection (todo 290; `decisions/client-rules-shared-in-rust.md`). This class holds the core's
 * state for the UI and keeps its bytes (a CFE envelope) in the core's encrypted store.
 */
@Singleton
class MailboxGapNotice @Inject constructor(private val store: SessionBlobStore) {
    private val lock = Mutex()
    private var state: MailboxGapState? = null
    private val _interval = MutableStateFlow<MailboxGap.Interval?>(null)

    /** What the notice shows; null when there is none. Filled by [load]. */
    val interval: StateFlow<MailboxGap.Interval?> = _interval.asStateFlow()

    suspend fun load() {
        lock.withLock { _interval.value = MailboxGap.Interval.of(held()) }
    }

    /** What the server reported, from either carrier ([MailboxGap.of]); null is nothing. */
    suspend fun record(gap: WireGap?) {
        gap ?: return
        lock.withLock {
            val held = held()
            if (!held.record(gap.afterCursor, gap.resumesAt)) {
                Log.d(TAG, "mailbox gap reported again: ${gap.afterCursor} — already shown or closed")
                return
            }
            Log.i(TAG, "mailbox gap reported: ${gap.afterCursor} → ${gap.resumesAt.ifEmpty { "nothing held" }}")
            changed(held)
        }
    }

    suspend fun dismiss() {
        lock.withLock {
            val held = held()
            held.dismiss()
            changed(held)
        }
    }

    /** The core's state, read from the store the first time it is needed. */
    private suspend fun held(): MailboxGapState = state ?: read().also { state = it }

    private suspend fun read(): MailboxGapState {
        // v1 kept only dates: it cannot tell a closed report from a new one, so it is dropped and
        // the next report shows once.
        runCatching { store.remove(LEGACY_KEY) }
        val kept = runCatching { store.get(KEY) }.onFailure { Log.w(TAG, "$KEY not read", it) }.getOrNull()
        // Bytes that are not a notice start it empty, logged by the core: the rule's, not ours.
        return kept?.let { MailboxGapState.fromBytes(it) } ?: MailboxGapState()
    }

    private suspend fun changed(held: MailboxGapState) {
        _interval.value = MailboxGap.Interval.of(held)
        runCatching { store.put(KEY, held.toBytes()) }.onFailure { Log.e(TAG, "$KEY not saved", it) }
    }

    private companion object {
        const val TAG = "MailboxGap"
        const val KEY = "mailbox-gap.v2"
        const val LEGACY_KEY = "mailbox-gap.v1"
    }
}

/** What the platform does with the gap: find it on either carrier, and show the core's interval. */
object MailboxGap {
    /** From the last position this device had to where delivery resumed; [untilMs] null when the mailbox held nothing to resume from. */
    data class Interval(val fromMs: Long, val untilMs: Long?) {
        companion object {
            fun of(state: MailboxGapState): Interval? = state.interval()?.let { Interval(it.fromMs, it.untilMs) }
        }
    }

    /** The gap a fetch answer carries, if any. */
    fun of(response: GetPendingMessagesResponse): WireGap? = response.gap.takeIf { response.hasGap() }

    /** The gap a stream frame carries. */
    fun of(response: MessageStreamResponse): WireGap? = response.mailboxGap.takeIf { response.hasMailboxGap() }
}
