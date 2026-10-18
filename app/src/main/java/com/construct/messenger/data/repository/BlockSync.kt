package com.construct.messenger.data.repository

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.diagnostics.Log
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import shared.proto.services.v1.UserServiceOuterClass.BlockUserRequest
import shared.proto.services.v1.UserServiceOuterClass.GetBlockedUsersRequest
import shared.proto.services.v1.UserServiceOuterClass.UnblockUserRequest

/**
 * A block lives in two places: the local flag, which drops what arrives, and the server's
 * `user_blocks` row, which refuses a call. Each went to the server once, best-effort, and a lost
 * unblock left the row for good — the person shown as unblocked here could not be called ("Call
 * not allowed", TODO 170, 2026-10-18). Now the wanted state is queued per person and resent until
 * the server answers, and the server's list is read back once the queue is empty.
 *
 * **Canon:** iOS `BlockSync.swift`.
 */
internal object BlockSync {
    data class Intent(val blocked: Boolean, val reason: String? = null)

    enum class Disposition { Retry, Drop }

    /**
     * The path failing, or a session being renewed, is tried again; a server's refusal (an account
     * that is gone, an id it rejects) would be refused every time. A failure with no gRPC status
     * is the path's.
     */
    fun disposition(error: Throwable): Disposition = when (grpcCode(error)) {
        null,
        Status.Code.UNAVAILABLE,
        Status.Code.DEADLINE_EXCEEDED,
        Status.Code.CANCELLED,
        Status.Code.UNKNOWN,
        Status.Code.ABORTED,
        Status.Code.RESOURCE_EXHAUSTED,
        Status.Code.UNAUTHENTICATED,
        -> Disposition.Retry
        else -> Disposition.Drop
    }

    /**
     * Server rows this device does not show: made visible here, so the person sees what the server
     * enforces and can lift it. Never unblocked blindly — the row is also what carries a block
     * across a reinstall. A person with a wish still queued is left to the queue.
     */
    fun toAdopt(serverBlocked: List<String>, pending: Set<String>, isBlockedHere: (String) -> Boolean): List<String> =
        serverBlocked.distinct().filter { it.isNotEmpty() && it !in pending && !isBlockedHere(it) }

    private fun grpcCode(e: Throwable): Status.Code? = when (e) {
        is StatusException -> e.status.code
        is StatusRuntimeException -> e.status.code
        else -> null
    }
}

/**
 * The wished block state per person, waiting for the server. Who one blocked says who one knows,
 * so it lives in the core's encrypted store, as [ContactRequestLedger] does.
 */
@Singleton
class BlockSyncQueue @Inject constructor(private val store: SessionBlobStore) {
    private val lock = Mutex()

    /** The latest wish for [userId] replaces one still waiting. */
    internal suspend fun record(userId: String, intent: BlockSync.Intent) {
        if (userId.isEmpty()) return
        lock.withLock { save(load() + (userId to intent)) }
    }

    internal suspend fun pending(): Map<String, BlockSync.Intent> = lock.withLock { load() }

    /** Only the wish that was sent leaves: one recorded while it was in flight stays for the next pass. */
    internal suspend fun confirm(userId: String, sent: BlockSync.Intent) {
        lock.withLock {
            val all = load()
            if (all[userId] == sent) save(all - userId)
        }
    }

    private suspend fun load(): Map<String, BlockSync.Intent> {
        val bytes = runCatching { store.get(KEY) }.getOrNull() ?: return emptyMap()
        return runCatching {
            val o = JSONObject(String(bytes))
            o.keys().asSequence().associateWith { id ->
                val e = o.getJSONObject(id)
                BlockSync.Intent(e.getBoolean("blocked"), e.optString("reason").ifEmpty { null })
            }
        }.onFailure { Log.w(TAG, "$KEY does not parse — dropped", it) }.getOrDefault(emptyMap())
    }

    private suspend fun save(all: Map<String, BlockSync.Intent>) {
        runCatching {
            if (all.isEmpty()) {
                store.remove(KEY)
            } else {
                val o = JSONObject()
                all.forEach { (id, i) -> o.put(id, JSONObject().put("blocked", i.blocked).apply { i.reason?.let { put("reason", it) } }) }
                store.put(KEY, o.toString().toByteArray())
            }
        }.onFailure { Log.e(TAG, "$KEY not saved", it) }
    }

    private companion object {
        const val TAG = "BlockSync"
        const val KEY = "block-sync:pending.v1"
    }
}

/**
 * Runs the queue, then reads the server's list. One pass at a time; a request during a pass
 * starts another after it, so a wish recorded mid-pass is never overtaken by the read-back.
 * Triggered by every local block or unblock, by the start after login or a restored session, and
 * by the app coming to the foreground.
 */
@Singleton
class BlockSyncService internal constructor(
    private val queue: BlockSyncQueue,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val contacts: ContactStore,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit,
) {
    @Inject
    constructor(queue: BlockSyncQueue, grpcClient: GrpcClient, keystoreManager: KeystoreManager, contacts: ContactStore) : this(
        queue, grpcClient, keystoreManager, contacts, CoroutineScope(SupervisorJob() + Dispatchers.IO), { delay(it) },
    )

    private var running = false
    private var again = false

    /** Records the wish and sends it. The caller has already set the local flag. */
    suspend fun request(userId: String, blocked: Boolean, reason: String? = null) {
        queue.record(userId, BlockSync.Intent(blocked, reason))
        sync()
    }

    fun sync() {
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        scope.launch {
            do {
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "block sync pass failed", e)
                }
            } while (takeAgain())
        }
    }

    private fun takeAgain(): Boolean = synchronized(this) {
        if (again) {
            again = false
            true
        } else {
            running = false
            false
        }
    }

    internal suspend fun pass() {
        val myId = keystoreManager.getUserId() ?: return
        for (attempt in 0..BACKOFF_MS.size) {
            val failure = flush(myId) ?: return readBack()
            if (attempt == BACKOFF_MS.size) break
            Log.i(TAG, "block sync: path not ready (${failure.message}) — again in ${BACKOFF_MS[attempt] / 1000}s")
            pause(BACKOFF_MS[attempt])
        }
        Log.i(TAG, "block sync: ${queue.pending().size} still queued — next foreground")
    }

    /** Null when the queue is empty; otherwise the failure that stopped it. */
    private suspend fun flush(myId: String): Throwable? {
        for ((userId, intent) in queue.pending()) {
            try {
                if (intent.blocked) {
                    grpcClient.user.blockUser(
                        BlockUserRequest.newBuilder().setBlockerUserId(myId).setUserId(userId)
                            .apply { intent.reason?.let { setReason(it) } }.build(),
                    )
                } else {
                    grpcClient.user.unblockUser(UnblockUserRequest.newBuilder().setBlockerUserId(myId).setUserId(userId).build())
                }
                queue.confirm(userId, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (BlockSync.disposition(e)) {
                    BlockSync.Disposition.Retry -> return e
                    BlockSync.Disposition.Drop -> {
                        Log.e(TAG, "block sync refused for ${userId.take(8)}… (blocked=${intent.blocked})", e)
                        queue.confirm(userId, intent)
                    }
                }
            }
        }
        return null
    }

    private suspend fun readBack() {
        // An empty user id is the caller; the server answers with the whole list.
        val serverBlocked = try {
            grpcClient.user.getBlockedUsers(GetBlockedUsersRequest.getDefaultInstance()).blockedUsersList.map { it.userId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "block sync: server list not read (${e.message})")
            return
        }
        val local = serverBlocked.associateWith { contacts.get(it) }
        val adopt = BlockSync.toAdopt(serverBlocked, queue.pending().keys) { local[it]?.isBlocked == true }
        var shown = 0
        for (userId in adopt) {
            if (local[userId] == null) continue // nobody here to show it on
            contacts.setBlocked(userId, true)
            shown++
        }
        if (shown > 0) Log.i(TAG, "block sync: $shown server block(s) were not shown here — now shown")
    }

    private companion object {
        const val TAG = "BlockSync"
        val BACKOFF_MS = longArrayOf(2_000, 10_000, 30_000, 120_000)
    }
}
