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
import org.json.JSONObject
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesResponse
import shared.proto.services.v1.MessagingServiceOuterClass.MessageStreamResponse
import shared.proto.services.v1.MessagingServiceOuterClass.MailboxGap as WireGap

/**
 * The server's queue keeps messages for a limited time and count; when this device comes back
 * later than that, what it trimmed is gone. The server says so (`MailboxGap`, on a fetch and once
 * on the stream) and this keeps the interval until the person has seen it: one notice above the
 * chat list (construct-docs `decisions/mailbox-gap-is-reported.md`, TODO 79). Whose messages they
 * were nobody knows — the server only knows the interval. Nothing is refetched or asked for.
 *
 * Kept in the core's encrypted store, so it outlives a restart and goes with the account.
 * **Canon:** iOS `MailboxGapNotice`.
 */
@Singleton
class MailboxGapNotice @Inject constructor(private val store: SessionBlobStore) {
    private val lock = Mutex()
    private val _interval = MutableStateFlow<MailboxGap.Interval?>(null)

    /** What the notice shows; null when there is none. Filled by [load]. */
    val interval: StateFlow<MailboxGap.Interval?> = _interval.asStateFlow()

    suspend fun load() {
        lock.withLock { _interval.value = read() }
    }

    /** What the server reported, from either carrier ([MailboxGap.of]); null is nothing. Several reports widen the one notice. */
    suspend fun record(gap: WireGap?) {
        gap ?: return
        val reported = MailboxGap.interval(gap.afterCursor, gap.resumesAt) ?: return
        Log.i(TAG, "mailbox gap reported: ${gap.afterCursor} → ${gap.resumesAt.ifEmpty { "nothing held" }}")
        lock.withLock {
            val merged = MailboxGap.merge(read(), reported)
            write(merged)
            _interval.value = merged
        }
    }

    suspend fun dismiss() {
        lock.withLock {
            runCatching { store.remove(KEY) }.onFailure { Log.e(TAG, "$KEY not removed", it) }
            _interval.value = null
        }
    }

    private suspend fun read(): MailboxGap.Interval? {
        val bytes = runCatching { store.get(KEY) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(String(bytes))
            MailboxGap.Interval(o.getLong("from"), if (o.has("until")) o.getLong("until") else null)
        }.onFailure { Log.w(TAG, "$KEY does not parse — dropped", it) }.getOrNull()
    }

    private suspend fun write(interval: MailboxGap.Interval) {
        val o = JSONObject().put("from", interval.fromMs)
        interval.untilMs?.let { o.put("until", it) }
        runCatching { store.put(KEY, o.toString().toByteArray()) }.onFailure { Log.e(TAG, "$KEY not saved", it) }
    }

    private companion object {
        const val TAG = "MailboxGap"
        const val KEY = "mailbox-gap.v1"
    }
}

/** The rule, pure. */
object MailboxGap {
    /** From the last position this device had to where delivery resumed; [untilMs] null when the mailbox held nothing to resume from. */
    data class Interval(val fromMs: Long, val untilMs: Long?)

    /** The gap a fetch answer carries, if any. */
    fun of(response: GetPendingMessagesResponse): WireGap? = response.gap.takeIf { response.hasGap() }

    /** The gap a stream frame carries — sent once, before the first delivery. */
    fun of(response: MessageStreamResponse): WireGap? = response.mailboxGap.takeIf { response.hasMailboxGap() }

    /** A stream cursor `ms-seq` as the moment it names (epoch ms); null for anything else. */
    fun msOfCursor(cursor: String): Long? = cursor.substringBefore('-').toLongOrNull()?.takeIf { it >= 0 }

    fun interval(afterCursor: String, resumesAt: String): Interval? {
        val from = msOfCursor(afterCursor) ?: return null
        return Interval(from, msOfCursor(resumesAt))
    }

    /** One notice for several reports: from the earliest start to the latest end; open-ended if either is. */
    fun merge(current: Interval?, reported: Interval): Interval {
        if (current == null) return reported
        val until = if (current.untilMs == null || reported.untilMs == null) null else maxOf(current.untilMs, reported.untilMs)
        return Interval(minOf(current.fromMs, reported.fromMs), until)
    }
}
