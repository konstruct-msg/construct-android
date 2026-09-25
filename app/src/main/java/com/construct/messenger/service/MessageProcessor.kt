package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * Turns a routed incoming message into decrypt + persist + ack by driving the
 * Rust CFE engine (`OrchestratorCore.handleEvent`) and executing the actions it
 * returns. Foundation decided in
 * `construct-docs/decisions/android-receive-path-cfe-not-component.md` — this
 * class is a **pure action executor**, it owns no session/healing/ACK logic
 * (that all lives in Rust CFE).
 *
 * Faithful port of the routing switch in iOS `MessageRouter.swift`
 * (`processWithRustOrchestrator` / `executeRustActions`):
 *
 *  1. Build `CfeIncomingEvent.MessageReceived` with the full wire blob — the
 *     Rust core parses the header itself (canonical `wire_payload::unpack`),
 *     so Kotlin carries no wire-format knowledge; `msgNum`/`kemCt`/`otpkId`
 *     are legacy event fields and stay zeroed.
 *  2. `handleEvent(event)`. On throw → END_SESSION recovery + mark processed
 *     (so background fetch never re-processes an undecryptable message forever —
 *     the iOS ghost-contact bug).
 *  3. `checkAckInDb` round-trip: after a restart the Rust in-memory ACK cache is
 *     empty; it returns `[CheckAckInDb(id)]`; we answer with `AckDbResult` and
 *     re-run to get the real action list.
 *  4. Route on the resulting actions (see [route]).
 *
 * Side effects (persist, receipt, notify, heal, END_SESSION, key-bundle fetch,
 * DB ack check) are delegated to [ProcessorEffects] — the repository/session
 * layer implements it. The orchestrator call goes through [OrchestratorGateway],
 * implemented by `CryptoManager` (single-threaded core access lives there).
 */
@Singleton
class MessageProcessor @Inject constructor(
    private val orchestrator: OrchestratorGateway,
    private val effects: ProcessorEffects,
    private val sessionManager: SessionManager,
    private val timerBridge: CfeTimerBridge,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
) {
    suspend fun process(incoming: MessageRouter.IncomingMessage): ProcessingOutcome {
        val copyRoute = resolveCopyRoute(incoming)
        if (copyRoute is CopyRouteResolution.Foreign) {
            effects.markProcessed(incoming.messageId, incoming.senderId)
            return ProcessingOutcome.Acked
        }
        val preferredDeviceId = (copyRoute as? CopyRouteResolution.Local)?.deviceId
        val contactId = preferredDeviceId
            ?: sessionManager.resolveDeviceId(incoming.senderId)
            ?: runCatching {
                sessionManager.discoverPeerDevices(incoming.senderId).firstOrNull()?.deviceId
            }.getOrNull()
            ?: run {
                Log.w(TAG, "cannot name incoming peer device ${incoming.senderId.take(8)}… — deferring")
                return ProcessingOutcome.Deferred
            }
        if (incoming.contentType == ContentType.CONTENT_TYPE_SESSION_RESET_INIT &&
            resetInitSuperseded(contactId, incoming)
        ) {
            effects.markProcessed(incoming.messageId, incoming.senderId)
            return ProcessingOutcome.Acked
        }
        val event = CfeIncomingEvent.MessageReceived(
            messageId = incoming.messageId,
            from = contactId,
            data = incoming.encryptedPayload,
            // The core derives msgNum/kemCt from `data` via the canonical parser;
            // malformed payloads come back as a NotifyError action → ACKed below.
            msgNum = 0u,
            kemCt = ByteArray(0),
            otpkId = 0u,
            isControl = false,
            contentType = incoming.contentType.number.toUByte(),
        )

        var actions = try {
            orchestrator.handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "handleEvent threw for ${incoming.messageId.take(8)}… — END_SESSION", e)
            effects.markProcessed(incoming.messageId, incoming.senderId)
            effects.requestEndSession(incoming.senderId)
            return ProcessingOutcome.Acked
        }

        // ACK cache miss after restart: Rust asks us to check the DB, then re-run.
        val single = actions.singleOrNull()
        if (single is CfeAction.CheckAckInDb) {
            val isProcessed = effects.isAckedInDb(single.messageId)
            actions = try {
                orchestrator.handleEvent(
                    CfeIncomingEvent.AckDbResult(single.messageId, isProcessed),
                ).ifEmpty { actions }
            } catch (e: Exception) {
                Log.e(TAG, "ackDbResult follow-up failed for ${single.messageId.take(8)}…", e)
                return ProcessingOutcome.Deferred
            }
        }

        return route(actions, incoming)
    }

    /**
     * Whether a SESSION_RESET_INIT from [contactId] is one we must acknowledge and not apply —
     * a redelivery of an init already applied, or a backlog replay older than the session held.
     * The core decides and keeps the ledger of applied inits, per device (`ResetInitArrived`).
     *
     * Asked before `MessageReceived` because applying is what the rest of this path does: a copy
     * of an init that reaches the RESPONDER init archives the session its first copy built. Until
     * this was asked nothing on Android recognised a redelivery at all — `ResponderInitUseCase`'s
     * `inFlight` guards only against two copies at the same moment.
     *
     * `false` — apply — when the init cannot be identified or the core cannot be asked: a
     * redundant re-init is cheap, a dropped live one strands the peer on a dead ratchet.
     */
    private suspend fun resetInitSuperseded(
        contactId: String,
        incoming: MessageRouter.IncomingMessage,
    ): Boolean {
        val ephemeral = effects.initEphemeral(incoming.encryptedPayload) ?: return false
        val actions = try {
            orchestrator.handleEvent(
                CfeIncomingEvent.ResetInitArrived(
                    contactId = contactId,
                    initEphemeral = ephemeral,
                    sentAtS = (incoming.timestampMs / 1000).toULong(),
                    establishedAtS = effects.sessionEstablishedAtMs(contactId)?.let { (it / 1000).toULong() },
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "ResetInitArrived threw for ${contactId.take(8)}… — applying", e)
            return false
        }
        val superseded = actions.firstNotNullOfOrNull { it as? CfeAction.ResetInitSuperseded } ?: return false
        Log.i(
            TAG,
            "SESSION_RESET_INIT ${incoming.messageId.take(8)}… from ${contactId.take(8)}… " +
                (if (superseded.redelivery) "already applied" else "pre-dates the session held") + " — ACK only",
        )
        return true
    }

    private suspend fun resolveCopyRoute(incoming: MessageRouter.IncomingMessage): CopyRouteResolution {
        val route = DeviceCopyRoute.parse(incoming.messageId) ?: return CopyRouteResolution.NotADeviceCopy
        val localDeviceId = cryptoManager.currentDeviceId() ?: return CopyRouteResolution.NotADeviceCopy
        val localAccountId = keystoreManager.getUserId()
        val candidates = runCatching {
            if (route.audience == DeviceCopyRoute.Audience.OWN_REPLICA &&
                incoming.senderId == localAccountId
            ) {
                sessionManager.discoverOwnDeviceBundles(incoming.senderId)
            } else {
                sessionManager.discoverPeerBundles(incoming.senderId)
            }
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) return CopyRouteResolution.NotADeviceCopy
        val matches = candidates.filter { candidate ->
            candidate.deviceId != localDeviceId || route.audience == DeviceCopyRoute.Audience.RECIPIENT
        }.any { candidate ->
            cryptoManager.deviceCopyTagMatches(
                tag = route.tag,
                baseMessageId = route.baseMessageId,
                ourDeviceId = localDeviceId,
                peerIdentityPublic = candidate.identityPublic,
            )
        }
        return if (matches) {
            val matching = candidates.firstOrNull { candidate ->
                cryptoManager.deviceCopyTagMatches(
                    tag = route.tag,
                    baseMessageId = route.baseMessageId,
                    ourDeviceId = localDeviceId,
                    peerIdentityPublic = candidate.identityPublic,
                )
            }
            if (matching != null) CopyRouteResolution.Local(matching.deviceId)
            else CopyRouteResolution.NotADeviceCopy
        } else {
            // A validly shaped copy for another device is not a decrypt failure. ACK it without
            // starting a candidate walk or burning a pre-key on a ciphertext we cannot open.
            CopyRouteResolution.Foreign
        }
    }

    /**
     * The routing decision, mirroring iOS's action switch. Pure w.r.t. the
     * orchestrator (all outward work goes through [effects]) so it is unit-tested
     * with a fake. First matching action wins; unmatched → ACK as delivered.
     */
    internal suspend fun route(
        actions: List<CfeAction>,
        incoming: MessageRouter.IncomingMessage,
    ): ProcessingOutcome {
        for (action in actions) {
            when (action) {
                is CfeAction.MessageDecrypted -> {
                    executeSideEffects(actions, incoming)
                    return ProcessingOutcome.Processed
                }
                is CfeAction.CallSignalDecrypted -> {
                    executeSideEffects(actions, incoming)
                    effects.sendReceipt(incoming.messageId, incoming.senderId, "delivered")
                    return ProcessingOutcome.Processed
                }
                is CfeAction.SessionHealNeeded -> {
                    effects.requestHeal(action.contactId, action.role)
                    return ProcessingOutcome.Deferred
                }
                is CfeAction.HealSuppressed -> {
                    Log.i(TAG, "heal suppressed ${action.contactId.take(8)}… retry ${action.retryAfterMs}ms — holding cursor")
                    return ProcessingOutcome.Deferred
                }
                is CfeAction.HeldPendingAck -> {
                    // Our own SESSION_RESET_INIT to this device is unanswered, so this failure is
                    // our re-init's own consequence and neither a heal nor a teardown answers it.
                    //
                    // iOS buffers the message and replays it when the wait ends; this client has
                    // no such buffer yet, so it holds the cursor and lets the server re-deliver,
                    // exactly as it does for a suppressed heal. The difference matters when the
                    // wait outlasts what the server will re-send — that is the buffer's reason
                    // for existing, and it is what this arm still owes.
                    //
                    // What it must not do is fall to the `else` below: that ACKs the message as
                    // delivered, which is the drop this action exists to prevent.
                    Log.i(TAG, "held behind our unacked SESSION_RESET_INIT to ${action.contactId.take(8)}… — holding cursor")
                    return ProcessingOutcome.Deferred
                }
                is CfeAction.EndSessionSuppressed -> {
                    Log.i(TAG, "END_SESSION suppressed ${action.contactId.take(8)}… retry ${action.retryAfterMs}ms — holding cursor")
                    return ProcessingOutcome.Deferred
                }
                is CfeAction.MessageQueuedPendingInit -> {
                    Log.i(TAG, "message queued behind init ${action.contactId.take(8)}… count=${action.queuedCount} — holding cursor")
                    return ProcessingOutcome.Deferred
                }
                is CfeAction.SendEndSession -> {
                    effects.sendReceipt(incoming.messageId, action.contactId, "failed")
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    effects.requestEndSession(action.contactId)
                    return ProcessingOutcome.Acked
                }
                is CfeAction.SendHeartbeat -> {
                    executeSideEffects(actions, incoming)
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    return ProcessingOutcome.Acked
                }
                is CfeAction.SessionTerminated -> {
                    executeSideEffects(actions, incoming)
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    return ProcessingOutcome.Acked
                }
                is CfeAction.FetchPublicKeyBundle -> {
                    effects.requestKeyBundle(action.userId, incoming)
                    return ProcessingOutcome.Deferred
                }
                else -> Unit // keep scanning
            }
        }

        // No actionable routing decision (duplicate / cooldown / msgNum=0 race) — ACK.
        Log.i(
            TAG,
            "no routing decision for ${incoming.messageId.take(8)}… msgNum-derived; " +
                "actions=[${actions.joinToString(",") { it::class.simpleName ?: "?" }}] — ACKing delivered",
        )
        effects.sendReceipt(incoming.messageId, incoming.senderId, "delivered")
        return ProcessingOutcome.Acked
    }

    /** Executes the stateless effect-actions (persist / receipt / notify / …).
     * Unknown actions are logged loudly, never silently dropped, so a new Rust
     * action surfaces here instead of vanishing. */
    private suspend fun executeSideEffects(actions: List<CfeAction>, incoming: MessageRouter.IncomingMessage) {
        for (action in actions) {
            when (action) {
                is CfeAction.MessageDecrypted -> if (
                    incoming.contentType == shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_SENDER_SYNC
                ) {
                    effects.onSenderSync(
                        action.contactId.ifEmpty { incoming.senderId },
                        action.messageId,
                        action.plaintext,
                        incoming.timestampMs,
                    )
                } else {
                    effects.onDecrypted(action.contactId.ifEmpty { incoming.senderId }, action.messageId, action.plaintext)
                }
                is CfeAction.CallSignalDecrypted ->
                    effects.onCallSignal(action.contactId, action.messageId, action.protoBytes)
                is CfeAction.PersistMessage -> effects.persistMessage(action.messageJson)
                is CfeAction.SendReceipt -> effects.sendReceipt(action.messageId, incoming.senderId, action.status)
                is CfeAction.NotifyNewMessage -> effects.notifyNewMessage(action.chatId, action.preview)
                is CfeAction.MarkMessageDelivered -> effects.markDelivered(action.messageId)
                is CfeAction.PersistAck -> effects.markProcessed(action.messageId, incoming.senderId)
                is CfeAction.PruneAckStore -> effects.pruneAckStore(action.cutoffTs.toLong())
                is CfeAction.SaveToSecureStore ->
                    effects.saveSecureStore(action.slot, action.data)
                is CfeAction.ArchiveSession -> effects.archiveSession(action.contactId)
                is CfeAction.SessionTerminated ->
                    effects.sessionTerminated(action.contactId, action.archiveBytes)
                is CfeAction.SendHeartbeat -> effects.sendHeartbeat(action.contactId)
                is CfeAction.NotifySessionCreated ->
                    Log.i(TAG, "session created ${action.contactId.take(8)}…")
                is CfeAction.NotifyError ->
                    Log.e(TAG, "CFE ${action.code}: ${action.message}")
                is CfeAction.ScheduleTimer ->
                    timerBridge.schedule(action.timerId, action.delayMs)
                is CfeAction.CancelTimer ->
                    timerBridge.cancel(action.timerId)
                is CfeAction.NotifyLinkedDevicesOfSessionReset ->
                    Log.i(TAG, "linked-device reset notification pending for ${action.contactId.take(8)}…")
                is CfeAction.EndSessionSuppressed,
                is CfeAction.MessageQueuedPendingInit,
                is CfeAction.SessionHealNeeded,
                is CfeAction.HealSuppressed,
                is CfeAction.HeldPendingAck,
                is CfeAction.HealAttemptAllowed,
                is CfeAction.HealExhausted,
                is CfeAction.CheckAckInDb,
                is CfeAction.DecryptMessage,
                is CfeAction.EncryptMessage,
                is CfeAction.InitSession,
                is CfeAction.SendEncryptedMessage,
                is CfeAction.SendEndSession,
                is CfeAction.FetchPublicKeyBundle,
                -> Log.d(TAG, "CFE action consumed by routing layer: ${action::class.simpleName}")
                // Answers to `ResetInitArrived`, read in `resetInitSuperseded` before the message is
                // routed. Listed so the exhaustive `when` keeps a new answer from having no reader.
                is CfeAction.ApplyResetInit,
                is CfeAction.ResetInitSuperseded,
                -> Log.d(TAG, "CFE reset-init verdict, read before routing: ${action::class.simpleName}")
                // The machine's answers about *opening* a session. This client does not ask it —
                // its session opening is still its own, so nothing here consumes these and the
                // honest record is a warning, not a "consumed by" line that would read as wired.
                // iOS acts on all six (`SessionActionExecutor`); the Android half is step 2 of
                // `decisions/session-is-one-state-machine.md`, not yet started here.
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
        const val TAG = "MessageProcessor"
    }

    private sealed interface CopyRouteResolution {
        data object NotADeviceCopy : CopyRouteResolution
        data class Local(val deviceId: String) : CopyRouteResolution
        data object Foreign : CopyRouteResolution
    }
}

/** Cursor-control result — mirrors iOS `streamOutcome`. */
enum class ProcessingOutcome {
    /** Decrypted & routed — advance the stream cursor. */
    Processed,

    /** Acked as delivered without a user-visible message — advance cursor. */
    Acked,

    /** Queued for heal / re-establish — hold the cursor until it drains. */
    Deferred,
}

/** Single-threaded access to `OrchestratorCore.handleEvent`. Implemented by
 * `CryptoManager` (owns the core lock). Kept as an interface so [MessageProcessor]
 * is testable without the native core. */
interface OrchestratorGateway {
    fun handleEvent(event: CfeIncomingEvent): List<CfeAction>
}

/** Outward side effects the processor delegates to the repository/session layer.
 * Semantics ported from iOS `SessionActionExecutor` + `MessageRouter` delegate. */
interface ProcessorEffects {
    suspend fun onDecrypted(contactId: String, messageId: String, plaintext: ByteArray)
    suspend fun onSenderSync(contactId: String, messageId: String, plaintext: ByteArray, timestampMs: Long) = Unit
    suspend fun onCallSignal(contactId: String, messageId: String, protoBytes: ByteArray)
    suspend fun persistMessage(messageJson: String)
    suspend fun sendReceipt(messageId: String, toUserId: String, status: String)
    suspend fun notifyNewMessage(chatId: String, preview: String)
    suspend fun markDelivered(messageId: String)
    suspend fun markProcessed(messageId: String, senderId: String)
    suspend fun saveSecureStore(slot: uniffi.construct_core.CfeSecureStoreSlot, data: ByteArray)
    suspend fun sessionTerminated(contactId: String, archiveBytes: ByteArray)
    suspend fun pruneAckStore(cutoffTs: Long)
    suspend fun sendHeartbeat(contactId: String)
    suspend fun notifyLinkedDevicesOfSessionReset(contactId: String) = Unit
    suspend fun archiveSession(contactId: String)
    suspend fun requestHeal(contactId: String, role: String)
    suspend fun requestEndSession(contactId: String)
    suspend fun requestKeyBundle(userId: String, incoming: MessageRouter.IncomingMessage)

    /** The X3DH ephemeral public key a SESSION_RESET_INIT carries — the init's identity — or
     * null when the payload does not parse. */
    fun initEphemeral(encryptedPayload: ByteArray): ByteArray?

    /** When the session held with device [contactId] was established (epoch ms), or null. */
    suspend fun sessionEstablishedAtMs(contactId: String): Long?

    /** Whether [messageId] is already recorded delivered in the local DB
     * (answers the CFE `checkAckInDb` round-trip after a restart). */
    fun isAckedInDb(messageId: String): Boolean
}
