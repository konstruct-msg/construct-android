package com.construct.messenger.service

import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import uniffi.construct_core.FanoutRetryState
import uniffi.construct_core.OwedCopy
import uniffi.construct_core.fanoutRetryBaseBackoffMs
import uniffi.construct_core.fanoutRetryMaxAttempts

/**
 * Devices of a recipient still owed their copy of a message this device already calls sent
 * (construct-docs TODO 155). **Canon:** iOS `FanoutRetryEntry`.
 *
 * [owedDeviceIds] is never empty here, unlike iOS, where empty means "plan again": on Android a
 * send that reached none of the recipient's devices is FAILED and the user retries the whole
 * message, so an entry only ever comes from a plan that named devices, some of which did not take
 * their copy. Retrying any other device would put two ciphertexts of one message through one
 * ratchet and show it twice.
 */
data class FanoutRetryEntry(
    val baseMessageId: String,
    val recipientUserId: String,
    val senderUserId: String,
    val owedDeviceIds: List<String>,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val createdAtMs: Long,
) {
    val key: String get() = "$baseMessageId|$recipientUserId"
}

/**
 * Where owed copies wait. The rules they wait by are the core's ([FanoutRetryState], construct-rules
 * `fanout_retry`), one for every client (todo 300): five attempts — the original send counts as
 * the first — 30 s doubling to an hour, a day's life from the first enqueue, merge on a second
 * enqueue, narrow to what a retry lost.
 *
 * Identifiers only, never a payload: the retry rebuilds the copy from the message's row. Those ids
 * still say who one writes to, so they live in the core's encrypted store (its `kv`, through
 * [SessionBlobStore]) as the core's CFE under [STORAGE_KEY]. Until 2026-11-12 they were JSON under
 * [LEGACY_KEY]; that is read once into the core's state, until [LEGACY_UNTIL_MS].
 */
@Singleton
class FanoutRetryQueue internal constructor(
    private val store: SessionBlobStore,
    private val now: () -> Long,
) {
    @Inject
    constructor(store: SessionBlobStore) : this(store, System::currentTimeMillis)

    private val lock = Mutex()

    /**
     * [owed] devices of [recipientUserId] still need their copy of [baseMessageId]. A second
     * enqueue of the same message adds devices and keeps the entry's age and attempts. Never empty
     * here: on Android a send that reached none of the devices is FAILED.
     */
    suspend fun enqueue(baseMessageId: String, recipientUserId: String, senderUserId: String, owed: List<String>) {
        if (owed.isEmpty()) return
        changing { it.enqueue(baseMessageId, recipientUserId, senderUserId, owed, now()) }
    }

    /**
     * One attempt spent, before it is made: a pass cut short mid-send must not leave the count
     * untouched. The entry afterwards, or null once it is exhausted and removed.
     */
    suspend fun recordAttempt(key: String): FanoutRetryEntry? = changing { it.recordAttempt(key, now()) }?.let(::entry)

    /** Narrow [key] to exactly the devices a retry just lost — not a union, or a device that took its copy gets a second. */
    suspend fun replaceOwed(key: String, owed: List<String>) = changing { it.replaceOwed(key, owed) }

    suspend fun remove(key: String) = changing { it.remove(key) }

    /** Entries whose wait is over, oldest first. Expired ones are dropped on the way. */
    suspend fun due(): List<FanoutRetryEntry> = changing { it.due(now()) }.map(::entry)

    /** When the next live entry comes due; null when nothing is owed. */
    suspend fun nextDue(): Long? = lock.withLock { load().nextDue(now()) }

    suspend fun all(): List<FanoutRetryEntry> = lock.withLock { load().all() }.map(::entry)

    private suspend fun <T> changing(body: (FanoutRetryState) -> T): T = lock.withLock {
        val state = load()
        body(state).also { save(state) }
    }

    private suspend fun load(): FanoutRetryState {
        val kept = runCatching { store.get(STORAGE_KEY) }.getOrNull()
        if (kept != null) return FanoutRetryState.fromBytes(kept)
        val v1 = runCatching { store.get(LEGACY_KEY) }.getOrNull() ?: return FanoutRetryState()
        // JSON by rule 6's exception: an earlier build's format, read once.
        val entries = runCatching { decode(v1) }
            .onFailure { Log.w(TAG, "owed copies of v1 do not parse — dropped", it) }
            .getOrDefault(emptyList())
        val state = FanoutRetryState.restore(entries.map { it.core })
        save(state)
        runCatching { store.remove(LEGACY_KEY) }
        return state
    }

    private suspend fun save(state: FanoutRetryState) {
        runCatching {
            if (state.all().isEmpty()) store.remove(STORAGE_KEY) else store.put(STORAGE_KEY, state.toBytes())
        }.onFailure { Log.e(TAG, "owed copies not saved — they will not survive a restart", it) }
    }

    internal companion object {
        const val TAG = "FanoutRetry"
        const val STORAGE_KEY = "fanout:retry-queue.v2"
        const val LEGACY_KEY = "fanout:retry-queue.v1"

        /** 2027-01-31: after it v1 JSON is no longer read (rule 11); a test fails then. */
        const val LEGACY_UNTIL_MS = 1_801_353_600_000L

        val MAX_ATTEMPTS: Int = fanoutRetryMaxAttempts().toInt()
        val BASE_BACKOFF_MS: Long = fanoutRetryBaseBackoffMs()

        /** The core's day of life (`fanout_retry::ENTRY_TTL_MS`), for the drain's tests; they fail if it drifts. */
        const val ENTRY_TTL_MS = 24L * 60 * 60 * 1000

        fun entry(copy: OwedCopy) = FanoutRetryEntry(
            baseMessageId = copy.baseMessageId,
            recipientUserId = copy.recipientUserId,
            senderUserId = copy.senderUserId,
            owedDeviceIds = copy.owedDeviceIds,
            attempts = copy.attempts.toInt(),
            nextAttemptAtMs = copy.nextAttemptAtMs,
            createdAtMs = copy.createdAtMs,
        )

        private val FanoutRetryEntry.core: OwedCopy
            get() = OwedCopy(key, baseMessageId, recipientUserId, senderUserId, owedDeviceIds, attempts.toUInt(), nextAttemptAtMs, createdAtMs)

        /** v1, as an earlier build wrote it. */
        fun encodeV1(entries: List<FanoutRetryEntry>): ByteArray = JSONArray().apply {
            entries.forEach { e ->
                put(
                    JSONObject()
                        .put("baseMessageId", e.baseMessageId)
                        .put("recipientUserId", e.recipientUserId)
                        .put("senderUserId", e.senderUserId)
                        .put("owedDeviceIds", JSONArray(e.owedDeviceIds))
                        .put("attempts", e.attempts)
                        .put("nextAttemptAtMs", e.nextAttemptAtMs)
                        .put("createdAtMs", e.createdAtMs),
                )
            }
        }.toString().toByteArray()

        fun decode(bytes: ByteArray): List<FanoutRetryEntry> {
            val array = JSONArray(String(bytes))
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val owed = o.getJSONArray("owedDeviceIds")
                FanoutRetryEntry(
                    baseMessageId = o.getString("baseMessageId"),
                    recipientUserId = o.getString("recipientUserId"),
                    senderUserId = o.getString("senderUserId"),
                    owedDeviceIds = (0 until owed.length()).map(owed::getString),
                    attempts = o.getInt("attempts"),
                    nextAttemptAtMs = o.getLong("nextAttemptAtMs"),
                    createdAtMs = o.getLong("createdAtMs"),
                )
            }
        }
    }
}
