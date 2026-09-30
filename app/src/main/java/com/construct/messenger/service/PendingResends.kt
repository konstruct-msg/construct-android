package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.data.local.db.PendingResendDao
import com.construct.messenger.data.local.db.PendingResendEntity
import com.construct.messenger.domain.usecase.ResendOutcome
import com.construct.messenger.domain.usecase.SendMessageUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The core's `ResendMessage`, kept until it is sent.
 *
 * The core answers a DECRYPTION_ERROR with one `ResendMessage` per message — its bound on the
 * loop — so a resend that fails is not asked for again. Until 2026-09-30 it ran inline and was
 * given up on a network error: on the stand the stream died unnoticed after a restart, the resend
 * waited 60 s, failed, and the message was lost — and the incoming pipeline it ran on waited too.
 * Now the request is a Room row, [drainSoon] sends it off the pipeline, and every reconnect of
 * the stream tries what is left.
 *
 * A row goes when the copy is sent or the message is not one we can resend (media, someone
 * else's, deleted), after [MAX_ATTEMPTS] failed tries, or after [ServerMessageIds.RETENTION_MS]:
 * the id it names means nothing to us after that.
 */
@Singleton
class PendingResends @Inject constructor(
    private val dao: PendingResendDao,
    private val sendMessage: dagger.Lazy<SendMessageUseCase>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = Mutex()

    suspend fun enqueue(accountId: String, deviceId: String, messageId: String, nowMs: Long = System.currentTimeMillis()) {
        dao.insert(PendingResendEntity(messageId, deviceId, accountId, createdAtMs = nowMs))
        drainSoon()
    }

    /** Off the caller: a resend may wait on the network for as long as a dead stream takes to notice. */
    fun drainSoon() {
        scope.launch { runCatching { drain() }.onFailure { Log.w(TAG, "drain failed", it) } }
    }

    suspend fun drain(nowMs: Long = System.currentTimeMillis()) = draining.withLock {
        dao.pruneOlderThan(nowMs - ServerMessageIds.RETENTION_MS)
        for (row in dao.all()) {
            when (sendMessage.get().resend(row.accountId, row.deviceId, row.messageId)) {
                ResendOutcome.SENT, ResendOutcome.NOT_OURS -> dao.delete(row.messageId, row.deviceId)
                ResendOutcome.FAILED ->
                    if (row.attempts + 1 >= MAX_ATTEMPTS) {
                        Log.w(TAG, "resend ${row.messageId.take(8)}… to ${row.deviceId.take(8)}… — given up after $MAX_ATTEMPTS tries")
                        dao.delete(row.messageId, row.deviceId)
                    } else {
                        dao.countAttempt(row.messageId, row.deviceId)
                    }
            }
        }
    }

    companion object {
        private const val TAG = "PendingResends"

        /** Tries are counted per reconnect, not per second; a day of a flapping network stays under it. */
        const val MAX_ATTEMPTS = 20
    }
}
