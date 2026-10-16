package com.construct.messenger.readsync

import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Boundaries this device moved, waiting to go to the account's other devices in one message:
 * the first one starts a window of 2 minutes plus up to 1 of jitter, and leaving the app sends at
 * once (owner, 2026-10-15). Not one message per read: timing in proportion to reading is what the
 * server must not get. **Canon:** iOS `ReadSyncOutbox`. Not kept across a kill, as on iOS: the
 * next read sends again.
 *
 * A send that fails puts its markers back for the next window, unless a later boundary for the
 * same chat is already waiting (iOS #235: a flush on leaving whose request the system cancelled
 * lost them).
 */
@Singleton
class ReadSyncOutbox internal constructor(
    private val boundaries: ReadBoundaries,
    private val send: Sender,
    private val scope: CoroutineScope,
    private val windowMs: () -> Long,
) {
    @Inject
    constructor(boundaries: ReadBoundaries, send: Sender) : this(
        boundaries,
        send,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { INTERVAL_MS + Random.nextLong(0, JITTER_MS + 1) },
    )

    /**
     * Hands a `ReadMarkers` payload to the account's other devices. False when it did not get
     * through and should be tried again; true when it did, or there is no other device to tell.
     */
    fun interface Sender {
        suspend fun send(payload: ByteArray): Boolean
    }

    private val lock = Mutex()
    private val pending = LinkedHashMap<String, ReadSync.Boundary>()
    private var window: Job? = null

    /** The chat with [partner] was read up to its newest incoming message. */
    suspend fun noteRead(partner: String) {
        val newest = boundaries.newestIncoming(partner) ?: return
        noteRead(partner, newest)
    }

    suspend fun noteRead(partner: String, boundary: ReadSync.Boundary) {
        // Held here too: an older copy arriving later is read already on this device.
        if (!boundaries.advance(partner, boundary)) return
        lock.withLock {
            pending[partner.lowercase()] = boundary
            openWindow()
        }
    }

    /** Send what is pending now — the app is leaving the screen. */
    suspend fun flush() {
        val sending = lock.withLock {
            window?.takeIf { it !== kotlinx.coroutines.currentCoroutineContext()[Job] }?.cancel()
            window = null
            if (pending.isEmpty()) return
            LinkedHashMap(pending).also { pending.clear() }
        }
        val sent = runCatching { send.send(ReadSync.encode(sending)) }
            .onFailure { Log.w(TAG, "read markers not sent", it) }
            .getOrDefault(false)
        if (sent) return
        lock.withLock {
            // Back for the next window; a later boundary noted meanwhile wins.
            sending.forEach { (partner, b) ->
                val newer = pending[partner]
                if (newer == null || ReadSync.isLater(b, newer)) pending[partner] = b
            }
            openWindow()
        }
    }

    private fun openWindow() {
        if (window != null) return
        window = scope.launch {
            delay(windowMs())
            flush()
        }
    }

    fun flushSoon() {
        scope.launch { flush() }
    }

    private companion object {
        const val TAG = "ReadSyncOutbox"
        const val INTERVAL_MS = 120_000L
        const val JITTER_MS = 60_000L
    }
}
