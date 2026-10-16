package com.construct.messenger.service

import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

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
 * Where owed copies wait, and the rules they wait by. **Canon:** iOS `FanoutRetryQueue`.
 *
 * Identifiers only, never a payload: the retry rebuilds the copy from the message's row, as a
 * resend after a DECRYPTION_ERROR does. Those ids still say who one writes to, so they live in the
 * core's encrypted store (its `kv`, through [SessionBlobStore] under [STORAGE_KEY]) — not in plain
 * SharedPreferences. While Room is still the store, in Room's file, beside the history it names.
 *
 * The original send counts as the first of [MAX_ATTEMPTS]; the waits after it are 30 s, doubling
 * to at most an hour. An entry lives a day from when it was first queued — failing again does not
 * extend it — and an expired one is dropped when the queue is read.
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
     * enqueue of the same message adds devices and keeps the entry's age and attempts.
     */
    suspend fun enqueue(baseMessageId: String, recipientUserId: String, senderUserId: String, owed: List<String>) {
        if (baseMessageId.isEmpty() || recipientUserId.isEmpty() || owed.isEmpty()) return
        lock.withLock {
            val all = load().toMutableList()
            val key = "$baseMessageId|$recipientUserId"
            val at = all.indexOfFirst { it.key == key }
            if (at >= 0) {
                all[at] = all[at].copy(owedDeviceIds = (all[at].owedDeviceIds + owed).distinct().sorted())
            } else {
                val t = now()
                all += FanoutRetryEntry(baseMessageId, recipientUserId, senderUserId, owed.distinct().sorted(), 0, t + BASE_BACKOFF_MS, t)
            }
            save(all)
        }
    }

    /**
     * One attempt spent, before it is made: a pass cut short mid-send must not leave the count
     * untouched. The entry afterwards, or null once it is exhausted and removed.
     */
    suspend fun recordAttempt(key: String): FanoutRetryEntry? = lock.withLock {
        val all = load().toMutableList()
        val at = all.indexOfFirst { it.key == key }
        if (at < 0) return null
        val attempts = all[at].attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            all.removeAt(at)
            save(all)
            return null
        }
        val backoff = minOf(BASE_BACKOFF_MS shl attempts, MAX_BACKOFF_MS)
        val entry = all[at].copy(attempts = attempts, nextAttemptAtMs = now() + backoff)
        all[at] = entry
        save(all)
        entry
    }

    /** Narrow [key] to exactly the devices a retry just lost — not a union, or a device that took its copy gets a second. */
    suspend fun replaceOwed(key: String, owed: List<String>) {
        if (owed.isEmpty()) return remove(key)
        lock.withLock {
            val all = load().toMutableList()
            val at = all.indexOfFirst { it.key == key }
            if (at < 0) return
            all[at] = all[at].copy(owedDeviceIds = owed.distinct().sorted())
            save(all)
        }
    }

    suspend fun remove(key: String) {
        lock.withLock {
            val all = load()
            if (all.any { it.key == key }) save(all.filterNot { it.key == key })
        }
    }

    /** Entries whose wait is over, oldest first. Expired ones are dropped on the way. */
    suspend fun due(): List<FanoutRetryEntry> = lock.withLock {
        val t = now()
        val all = load()
        val live = all.filter { t - it.createdAtMs < ENTRY_TTL_MS }
        if (live.size != all.size) save(live)
        live.filter { it.nextAttemptAtMs <= t }.sortedBy { it.createdAtMs }
    }

    /** When the next live entry comes due; null when nothing is owed. */
    suspend fun nextDue(): Long? = lock.withLock {
        val t = now()
        load().filter { t - it.createdAtMs < ENTRY_TTL_MS }.minOfOrNull { it.nextAttemptAtMs }
    }

    suspend fun all(): List<FanoutRetryEntry> = lock.withLock { load() }

    private suspend fun load(): List<FanoutRetryEntry> {
        val bytes = runCatching { store.get(STORAGE_KEY) }.getOrNull() ?: return emptyList()
        return runCatching { decode(bytes) }
            .onFailure { Log.w(TAG, "owed copies do not parse — dropped", it) }
            .getOrDefault(emptyList())
    }

    private suspend fun save(entries: List<FanoutRetryEntry>) {
        runCatching {
            if (entries.isEmpty()) store.remove(STORAGE_KEY) else store.put(STORAGE_KEY, encode(entries))
        }.onFailure { Log.e(TAG, "owed copies not saved — they will not survive a restart", it) }
    }

    internal companion object {
        const val TAG = "FanoutRetry"
        const val STORAGE_KEY = "fanout:retry-queue.v1"
        const val MAX_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 3_600_000L
        const val ENTRY_TTL_MS = 24L * 60 * 60 * 1000

        fun encode(entries: List<FanoutRetryEntry>): ByteArray = JSONArray().apply {
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
