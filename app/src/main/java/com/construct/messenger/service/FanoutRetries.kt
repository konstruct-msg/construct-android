package com.construct.messenger.service

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.domain.usecase.ResendOutcome
import com.construct.messenger.domain.usecase.SendMessageUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a send tells the retry about the recipient's devices (TODO 155). */
interface OwedCopies {
    /** [devices] of [recipient] did not take their copy of [messageId], which went to another of theirs. */
    suspend fun owed(messageId: String, recipient: String, sender: String, devices: List<String>)

    /** Every device of [recipient] took [messageId]: nothing an earlier attempt left is owed now. */
    suspend fun settled(messageId: String, recipient: String)

    companion object {
        val NONE = object : OwedCopies {
            override suspend fun owed(messageId: String, recipient: String, sender: String, devices: List<String>) = Unit
            override suspend fun settled(messageId: String, recipient: String) = Unit
        }
    }
}

/**
 * Sends the copies [FanoutRetryQueue] holds when they come due (TODO 155). **Canon:** iOS
 * `MultiDeviceSendCoordinator.drainRetryQueue` / `scheduleDrain` (#236).
 *
 * Until now a copy one of the recipient's devices refused was never sent again: the row said sent
 * once another device took it, a receipt from any of them said delivered, and the second device
 * never saw the message. Each owed device now gets the same copy [SendMessageUseCase.resend] sends
 * after a DECRYPTION_ERROR — rebuilt from the row, sealed to that device, under the name its
 * first copy had — and only the owed ones.
 *
 * One pass is scheduled for the nearest due time, when an entry is queued and after every pass;
 * iOS drained only on a network change until #236, and a copy lost to anything else waited
 * hours. A reconnect drains too. Serial: each device without a session spends one of its one-time
 * pre-keys (`decisions/prekey-bundle-fetch-is-destructive.md`).
 */
@Singleton
class FanoutRetries internal constructor(
    private val queue: FanoutRetryQueue,
    private val send: () -> SendMessageUseCase,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : OwedCopies {
    @Inject
    constructor(queue: FanoutRetryQueue, send: dagger.Lazy<SendMessageUseCase>) : this(
        queue,
        { send.get() },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        System::currentTimeMillis,
    )

    private val draining = Mutex()
    private val timerLock = Mutex()
    private var timer: Job? = null

    override suspend fun owed(messageId: String, recipient: String, sender: String, devices: List<String>) {
        queue.enqueue(messageId, recipient, sender, devices)
        Log.i(TAG, "${messageId.take(8)}… owed to ${devices.size} device(s) of ${recipient.take(8)}…")
        schedule()
    }

    override suspend fun settled(messageId: String, recipient: String) = queue.remove("$messageId|$recipient")

    /** The stream is back — also the first connect after a restart: whatever is due goes now, the rest is scheduled. */
    fun drainSoon() {
        scope.launch { runCatching { drain() }.onFailure { Log.w(TAG, "drain failed", it) } }
    }

    suspend fun drain() {
        if (!draining.tryLock()) return
        try {
            val due = queue.due()
            if (due.isEmpty()) return
            // Before the core is up nothing can be sent; the attempt is not spent on it.
            if (!send().isReady()) {
                Log.i(TAG, "drain skipped — not ready to send")
                return
            }
            for (entry in due) retry(entry)
        } finally {
            draining.unlock()
            // Whatever is left, or not due yet, gets its own pass when it comes due.
            schedule()
        }
    }

    private suspend fun retry(entry: FanoutRetryEntry) {
        val short = entry.baseMessageId.take(8)
        if (queue.recordAttempt(entry.key) == null) {
            Log.w(TAG, "$short… given up after ${FanoutRetryQueue.MAX_ATTEMPTS} attempts, ${entry.owedDeviceIds.size} device(s) without it")
            return
        }
        val lost = mutableListOf<String>()
        for (device in entry.owedDeviceIds) {
            when (send().resend(entry.recipientUserId, device, entry.baseMessageId)) {
                ResendOutcome.SENT -> Unit
                ResendOutcome.FAILED -> lost += device
                ResendOutcome.NOT_OURS -> {
                    // The row is gone or cannot be sent again: no device will get it from here.
                    Log.w(TAG, "$short… no longer resendable — dropped")
                    queue.remove(entry.key)
                    return
                }
            }
        }
        // From what this pass lost, not what the entry said: a device reached now must not get a second copy.
        queue.replaceOwed(entry.key, lost)
        Log.i(TAG, "$short… retried: ${entry.owedDeviceIds.size - lost.size} of ${entry.owedDeviceIds.size} device(s) took it")
    }

    private suspend fun schedule() {
        val next = queue.nextDue()
        timerLock.withLock {
            timer?.cancel()
            timer = null
            if (next == null) return
            val t = now()
            // Already due means a pass just left it untried (not ready yet): not again at once.
            val wait = if (next > t) next - t + DRAIN_SLACK_MS else FanoutRetryQueue.BASE_BACKOFF_MS
            timer = scope.launch {
                delay(wait)
                // Off the field first, so the pass rescheduling at its end does not cancel itself.
                timerLock.withLock { if (timer === coroutineContext[Job]) timer = null }
                drain()
            }
        }
    }

    private companion object {
        const val TAG = FanoutRetryQueue.TAG

        /** A moment past the due time, so the entry reads as due when the pass looks. */
        const val DRAIN_SLACK_MS = 1_000L
    }
}
