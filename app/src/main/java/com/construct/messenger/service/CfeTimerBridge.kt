package com.construct.messenger.service

import android.util.Log
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
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * Executes the platform half of Rust CFE scheduling.
 *
 * Rust owns timer meaning and emits ScheduleTimer/CancelTimer. Android owns only the wake-up,
 * then feeds TimerFired back through the same OrchestratorCore event API. This mirrors iOS
 * OutboundSessionService.scheduleRustTimer without introducing a second protocol scheduler.
 */
@Singleton
class CfeTimerBridge @Inject constructor(
    private val orchestrator: OrchestratorGateway,
    private val effects: ProcessorEffects,
    private val held: HeldEnvelopes,
    private val sessionManager: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        dispatch(CfeIncomingEvent.AppLaunched)
    }

    fun stop() {
        started = false
        synchronized(jobs) {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
        }
    }

    fun onNetworkReconnected() {
        if (started) dispatch(CfeIncomingEvent.NetworkReconnected)
    }

    fun schedule(timerId: String, delayMs: ULong) {
        cancel(timerId)
        if (!started) return
        val job = scope.launch {
            // Core timers are bounded to practical delays (milliseconds to hours); avoid a
            // Long.MAX_VALUE ULong conversion because K2's constant evaluator mishandles it.
            delay(delayMs.toLong())
            if (!started) return@launch
            synchronized(jobs) { jobs.remove(timerId) }
            dispatch(CfeIncomingEvent.TimerFired(timerId))
        }
        synchronized(jobs) { jobs[timerId] = job }
        Log.d(TAG, "CFE timer scheduled $timerId in ${delayMs}ms")
    }

    fun cancel(timerId: String) {
        synchronized(jobs) { jobs.remove(timerId)?.cancel() }
        Log.d(TAG, "CFE timer cancelled $timerId")
    }

    private fun dispatch(event: CfeIncomingEvent) {
        scope.launch {
            val actions = runCatching {
                eventMutex.withLock { orchestrator.handleEvent(event) }
            }.onFailure { error ->
                Log.e(TAG, "CFE timer event failed: ${event::class.simpleName}", error)
            }.getOrNull() ?: return@launch
            runCatching { execute(actions) }
                .onFailure { error ->
                    Log.e(TAG, "CFE timer actions failed: ${event::class.simpleName}", error)
                }
        }
    }

    /**
     * Executes core actions that arrive off the message path — from an alarm, a reconnect, or a
     * receiving open (`open_receiving` returns the opener's decrypt and whatever drained behind
     * it). One executor, so a drained message is persisted exactly as a timer-driven one is.
     */
    suspend fun execute(actions: List<CfeAction>) {
        for (action in actions) {
            when (action) {
                is CfeAction.ScheduleTimer -> schedule(action.timerId, action.delayMs)
                is CfeAction.CancelTimer -> cancel(action.timerId)
                is CfeAction.SaveToSecureStore -> effects.saveSecureStore(action.slot, action.data)
                // A failed open answers the messages it gave up, and a decryption error received
                // is answered with a retire and a resend: all three can reach this executor.
                is CfeAction.SendDecryptionError ->
                    effects.sendDecryptionError(action.contactId, action.messageId, action.payload)
                is CfeAction.SessionRetired ->
                    effects.sessionRetired(action.contactId, action.withoutOneTimePrekey)
                is CfeAction.ResendMessage -> effects.resendMessage(action.contactId, action.messageId)
                is CfeAction.ArchiveSession -> effects.archiveSession(action.contactId)
                is CfeAction.PersistAck -> effects.markProcessed(action.messageId, "")
                is CfeAction.PruneAckStore -> effects.pruneAckStore(action.cutoffTs.toLong())
                // Routed by the envelope kept while it waited: a drained SENDER_SYNC is our own
                // copy, and `MessageDecrypted` does not say so.
                is CfeAction.MessageDecrypted -> effects.deliverDecrypted(action, held.take(action.messageId))
                is CfeAction.CallSignalDecrypted ->
                    effects.onCallSignal(action.contactId, action.messageId, action.protoBytes)
                is CfeAction.DuplicateDropped -> effects.markProcessed(action.messageId, "")
                is CfeAction.MarkMessageDelivered -> effects.markDelivered(action.messageId)
                is CfeAction.NotifyNewMessage -> effects.notifyNewMessage(action.chatId, action.preview)
                is CfeAction.SendReceipt -> effects.sendReceipt(action.messageId, "", action.status)
                is CfeAction.NotifySessionCreated -> Log.i(TAG, "session created ${action.contactId.take(8)}…")
                is CfeAction.NotifyError -> Log.e(TAG, "CFE ${action.code}: ${action.message}")
                is CfeAction.MessageQueuedPendingInit,
                -> Log.i(TAG, "CFE deferred action ${action::class.simpleName}")
                // Granted in answer to a message, which is where it is acted on; no alarm pays it.
                is CfeAction.OpenReceiving,
                is CfeAction.CheckAckInDb,
                is CfeAction.DecryptMessage,
                is CfeAction.EncryptMessage,
                is CfeAction.InitSession,
                is CfeAction.SendEncryptedMessage,
                -> Log.d(TAG, "CFE routing action on timer path: ${action::class.simpleName}")
                is CfeAction.OpenSession -> runCatching {
                    sessionManager.reopenSessionForDevice(action.contactId)
                }.onFailure { error ->
                    Log.e(TAG, "CFE requested session reopen for ${action.contactId.take(8)}… failed", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "CfeTimerBridge"
    }
}
