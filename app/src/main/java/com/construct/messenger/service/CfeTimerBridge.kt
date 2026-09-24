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
     * session the platform just finished opening (`SessionInitCompleted` drains what the core
     * held behind it). One executor, so a drained message is persisted exactly as a timer-driven
     * one is.
     */
    suspend fun execute(actions: List<CfeAction>) {
        for (action in actions) {
            when (action) {
                is CfeAction.ScheduleTimer -> schedule(action.timerId, action.delayMs)
                is CfeAction.CancelTimer -> cancel(action.timerId)
                is CfeAction.SendHeartbeat -> effects.sendHeartbeat(action.contactId)
                is CfeAction.SendEndSession -> effects.requestEndSession(action.contactId)
                is CfeAction.NotifyLinkedDevicesOfSessionReset ->
                    effects.notifyLinkedDevicesOfSessionReset(action.contactId)
                is CfeAction.ApplyPqContribution ->
                    effects.applyPqContribution(action.contactId, action.kemSs)
                is CfeAction.SaveToSecureStore -> effects.saveSecureStore(action.slot, action.data)
                is CfeAction.SessionTerminated ->
                    effects.sessionTerminated(action.contactId, action.archiveBytes)
                is CfeAction.ArchiveSession -> effects.archiveSession(action.contactId)
                is CfeAction.PersistAck -> effects.markProcessed(action.messageId, "")
                is CfeAction.PruneAckStore -> effects.pruneAckStore(action.cutoffTs.toLong())
                is CfeAction.MessageDecrypted ->
                    effects.onDecrypted(action.contactId, action.messageId, action.plaintext)
                is CfeAction.CallSignalDecrypted ->
                    effects.onCallSignal(action.contactId, action.messageId, action.protoBytes)
                is CfeAction.PersistMessage -> effects.persistMessage(action.messageJson)
                is CfeAction.MarkMessageDelivered -> effects.markDelivered(action.messageId)
                is CfeAction.NotifyNewMessage -> effects.notifyNewMessage(action.chatId, action.preview)
                is CfeAction.SendReceipt -> effects.sendReceipt(action.messageId, "", action.status)
                is CfeAction.NotifySessionCreated -> Log.i(TAG, "session created ${action.contactId.take(8)}…")
                is CfeAction.NotifyError -> Log.e(TAG, "CFE ${action.code}: ${action.message}")
                is CfeAction.SessionHealNeeded -> effects.requestHeal(action.contactId, action.role)
                is CfeAction.HealSuppressed,
                is CfeAction.EndSessionSuppressed,
                is CfeAction.MessageQueuedPendingInit,
                is CfeAction.HeldPendingAck,
                // Answers to `HealAttempted`, which this client does not ask yet — its heal path
                // has no retry budget of its own to replace. Listed so the exhaustive `when`
                // keeps compiling and so the omission is visible rather than silent.
                is CfeAction.HealAttemptAllowed,
                is CfeAction.HealExhausted,
                -> Log.i(TAG, "CFE deferred action ${action::class.simpleName}")
                // Answers to `ResetInitArrived`, which `MessageProcessor` asks and reads on the
                // message path. No alarm produces them; listed so the `when` stays exhaustive.
                is CfeAction.ApplyResetInit,
                is CfeAction.ResetInitSuperseded,
                -> Log.w(TAG, "CFE reset-init verdict on the timer path: ${action::class.simpleName}")
                is CfeAction.FetchPublicKeyBundle,
                is CfeAction.CheckAckInDb,
                is CfeAction.DecryptMessage,
                is CfeAction.EncryptMessage,
                is CfeAction.InitSession,
                is CfeAction.SendEncryptedMessage,
                -> Log.d(TAG, "CFE routing action on timer path: ${action::class.simpleName}")
                // The machine's answers about opening a session. Reached from here whenever an
                // alarm it armed fires, and nothing on this client acts on them yet — session
                // opening is still Android's own. Warned rather than folded into the line above,
                // which would read as wired. iOS acts on all six; see `MessageProcessor`.
                is CfeAction.OpenSession,
                is CfeAction.OpenDeferred,
                is CfeAction.OpenNotNeeded,
                is CfeAction.OpeningGaveUp,
                is CfeAction.ResendSri,
                is CfeAction.EndSessionNotNeeded,
                -> Log.w(TAG, "CFE session-open action not acted on by this client: ${action::class.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "CfeTimerBridge"
    }
}
