package com.construct.messenger.data.repository

import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * What this device remembers about the contact requests it sent (TODO 157). **Canon:** iOS
 * Keychain `contactRequestMapping` + `AcceptedRequestAcks`.
 *
 * - **Sent:** request id → the user it went to. The server's view of a sent request names no
 *   recipient, so this is the only way an ACCEPTED one becomes a contact here. Forgotten once
 *   the contact is made.
 * - **Acks:** accepted requests made into contacts, waiting to tell the server, which then
 *   deletes their rows instead of keeping them 30 days. An id leaves only when a poll that
 *   carried it got an answer: a lost answer sends it again, and a second delete is nothing.
 *
 * Both say who one asked to talk to, so they live in the core's encrypted store, not in plain
 * preferences — unlike iOS, whose queue is in UserDefaults.
 */
@Singleton
class ContactRequestLedger @Inject constructor(private val store: SessionBlobStore) {
    private val lock = Mutex()

    suspend fun recordSent(requestId: String, toUserId: String) {
        if (requestId.isEmpty() || toUserId.isEmpty()) return
        lock.withLock { saveSent(loadSent() + (requestId to toUserId)) }
    }

    /** Who [requestId] went to; null for a request this device did not send, or already made a contact. */
    suspend fun sentTo(requestId: String): String? = lock.withLock { loadSent()[requestId] }

    suspend fun forgetSent(requestId: String) {
        lock.withLock {
            val sent = loadSent()
            if (requestId in sent) saveSent(sent - requestId)
        }
    }

    /** Queue [requestId] for the next poll; queuing it twice keeps one. */
    suspend fun ack(requestId: String) {
        if (requestId.isEmpty()) return
        lock.withLock {
            val acks = loadAcks()
            if (requestId !in acks) saveAcks(acks + requestId)
        }
    }

    /** What the next poll carries: the oldest [PER_POLL]; the rest wait for the one after. */
    suspend fun pendingAcks(): List<String> = lock.withLock { loadAcks().take(PER_POLL) }

    /**
     * The poll that carried [sent] was answered. Only those leave: one queued while the poll was
     * in flight goes with the next.
     */
    suspend fun confirmAcks(sent: List<String>) {
        if (sent.isEmpty()) return
        lock.withLock {
            val done = sent.toSet()
            saveAcks(loadAcks().filterNot { it in done })
        }
    }

    private suspend fun loadSent(): Map<String, String> = read(SENT_KEY) { bytes ->
        val o = JSONObject(String(bytes))
        o.keys().asSequence().associateWith { o.getString(it) }
    } ?: emptyMap()

    private suspend fun saveSent(sent: Map<String, String>) = write(SENT_KEY, sent.isEmpty()) {
        JSONObject().apply { sent.forEach { (k, v) -> put(k, v) } }.toString()
    }

    private suspend fun loadAcks(): List<String> = read(ACKS_KEY) { bytes ->
        val a = JSONArray(String(bytes))
        (0 until a.length()).map(a::getString)
    } ?: emptyList()

    private suspend fun saveAcks(acks: List<String>) = write(ACKS_KEY, acks.isEmpty()) { JSONArray(acks).toString() }

    private suspend fun <T> read(key: String, decode: (ByteArray) -> T): T? {
        val bytes = runCatching { store.get(key) }.getOrNull() ?: return null
        return runCatching { decode(bytes) }.onFailure { Log.w(TAG, "$key does not parse — dropped", it) }.getOrNull()
    }

    private suspend fun write(key: String, empty: Boolean, encode: () -> String) {
        runCatching { if (empty) store.remove(key) else store.put(key, encode().toByteArray()) }
            .onFailure { Log.e(TAG, "$key not saved", it) }
    }

    internal companion object {
        const val TAG = "ContactRequests"
        const val SENT_KEY = "contact-requests:sent.v1"
        const val ACKS_KEY = "contact-requests:acks.v1"

        /** The server honours this many per poll (construct-server #86). */
        const val PER_POLL = 100
    }
}
