package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
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
 *  1. Build `CfeIncomingEvent.MessageReceived` with the full wire blob and the
 *     sender certificate — the Rust core parses the header itself (canonical
 *     `wire_payload::unpack`), so Kotlin carries no wire-format knowledge;
 *     `msgNum`/`kemCt`/`otpkId` are legacy event fields and stay zeroed.
 *  2. `handleEvent(event)`. On throw → END_SESSION recovery + mark processed
 *     (so background fetch never re-processes an undecryptable message forever —
 *     the iOS ghost-contact bug).
 *  3. `checkAckInDb` round-trip: after a restart the Rust in-memory ACK cache is
 *     empty; it returns `[CheckAckInDb(id)]`; we answer with `AckDbResult` and
 *     re-run to get the real action list.
 *  4. Route on the resulting actions (see [route]).
 *
 * Side effects (persist, receipt, notify, END_SESSION, receiving open,
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
    private val held: HeldEnvelopes,
) {
    suspend fun process(incoming: MessageRouter.IncomingMessage): ProcessingOutcome {
        val copyRoute = resolveCopyRoute(incoming)
        if (copyRoute is CopyRouteResolution.Foreign) {
            effects.markProcessed(incoming.messageId, incoming.senderId)
            return ProcessingOutcome.Acked
        }
        // The device comes from the message: its certificate names it. The pinned device of the
        // account is the answer only for an unsealed message on a session we already hold. There
        // is no third answer — until 2026-09-27 this asked the directory and took its *first*
        // device, a guess that filed a stranger's first message under whichever device answered.
        val contactId = incoming.senderDeviceId.ifEmpty { null }
            ?: sessionManager.resolveDeviceId(incoming.senderId)
            ?: run {
                Log.w(TAG, "cannot name incoming peer device ${incoming.senderId.take(8)}… — refused")
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
            contentType = incoming.contentType.number.toUByte(),
            // What a first message opens its session from; the core checks it when it does.
            senderCertificate = incoming.senderCertificate,
        )

        var actions = try {
            orchestrator.handleEvent(event)
        } catch (e: Exception) {
            // A throw is the core refusing the event, not a session failing to read the message,
            // so there is no state to name in a decryption error. Recorded, so background fetch
            // does not re-process it forever. It used to send END_SESSION to the *account*.
            Log.e(TAG, "handleEvent threw for ${incoming.messageId.take(8)}… — dropped", e)
            effects.markProcessed(incoming.messageId, incoming.senderId)
            return ProcessingOutcome.Acked
        }

        // ACK cache miss after restart: Rust asks us to check the DB, then re-run.
        val single = actions.singleOrNull()
        if (single is CfeAction.CheckAckInDb) {
            val isProcessed = effects.isAckedInDb(single.messageId)
            // An empty follow-up is the answer, not a missing one. Putting `[CheckAckInDb]`
            // back is what turned a dropped duplicate into "no routing decision" (iOS,
            // 2026-08-04). The core now names a duplicate `DuplicateDropped`; emptiness must
            // still not be rewritten into the question it just answered.
            actions = try {
                orchestrator.handleEvent(
                    CfeIncomingEvent.AckDbResult(single.messageId, isProcessed),
                )
            } catch (e: Exception) {
                Log.e(TAG, "ackDbResult follow-up failed for ${single.messageId.take(8)}…", e)
                return ProcessingOutcome.Deferred
            }
        }

        return route(actions, incoming)
    }

    /**
     * Whether this is a per-device copy for another device. Decided from the certificate's key
     * alone: the tag is a MAC under the secret we share with the device that wrote it, and the
     * certificate names that device's key.
     *
     * Without a certificate nothing here can decide, and the copy is treated as ours — a wrong
     * attempt costs a failed decrypt, a wrong discard loses a message. Until 2026-09-27 this
     * fetched the sender account's bundles for every copy, which told the server whom each sealed
     * message was from.
     */
    private fun resolveCopyRoute(incoming: MessageRouter.IncomingMessage): CopyRouteResolution {
        val route = DeviceCopyRoute.parse(incoming.messageId) ?: return CopyRouteResolution.NotADeviceCopy
        val certificate = incoming.senderCertificate ?: return CopyRouteResolution.NotADeviceCopy
        val localDeviceId = cryptoManager.currentDeviceId() ?: return CopyRouteResolution.NotADeviceCopy
        val ours = runCatching {
            cryptoManager.deviceCopyTagMatches(
                tag = route.tag,
                baseMessageId = route.baseMessageId,
                ourDeviceId = localDeviceId,
                peerIdentityPublic = certificate.identityKey,
            )
        }.getOrDefault(true)
        // A validly shaped copy for another device is not a decrypt failure. ACK it without
        // burning a pre-key on a ciphertext we cannot open.
        return if (ours) CopyRouteResolution.Ours else CopyRouteResolution.Foreign
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
                is CfeAction.MessageQueuedPendingInit -> {
                    // The core queued it behind an open already under way; the open's drain
                    // decrypts it, and routes it by the envelope kept here.
                    held.hold(incoming)
                    Log.i(TAG, "message queued behind init ${action.contactId.take(8)}… count=${action.queuedCount} — holding cursor")
                    return ProcessingOutcome.Deferred
                }
                // Nothing held reads it and it carries no handshake: the core built a decryption
                // error to its writer (sealed messages) and recorded the message; the writer resends
                // it on the state it opens next. Acked — the cursor moves past this copy. Until
                // 2026-09-27 this was END_SESSION, and its cooldown held the cursor until the next
                // reconnect (`decisions/sessions-renew-by-sending.md`).
                is CfeAction.SendDecryptionError -> {
                    executeSideEffects(actions, incoming)
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    return ProcessingOutcome.Acked
                }
                // The same verdict with no error to send: an unsealed message names no writer to
                // seal one to. No delivered receipt — it was not delivered.
                is CfeAction.NotifyError -> if (action.code == DECRYPT_FAILED) {
                    Log.i(TAG, "unreadable ${incoming.messageId.take(8)}… — ${action.message}")
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    return ProcessingOutcome.Acked
                }
                is CfeAction.DuplicateDropped -> {
                    // Already in the ACK cache, our DB, or a ratchet position whose key was used.
                    // Record it so the next copy stops here, and move the cursor past it. A
                    // delivered receipt per copy is the storm a redelivery used to raise.
                    executeSideEffects(actions, incoming)
                    effects.markProcessed(incoming.messageId, incoming.senderId)
                    return ProcessingOutcome.Acked
                }
                is CfeAction.OpenReceiving -> {
                    // The message carries a handshake header the held session (if any) cannot
                    // read: the core queued it and asks for an open. Nothing is fetched — the
                    // queued messages open with the keys their certificates name, and a session
                    // held meanwhile is kept as a previous state
                    // (`decisions/sessions-renew-by-sending.md`).
                    held.hold(incoming)
                    return effects.openReceiving(action.contactId, incoming)
                }
                else -> Unit // keep scanning
            }
        }

        // No actionable routing decision. A duplicate is `DuplicateDropped` above, not this
        // branch — the parenthetical that used to list it here is what made healthy drops look
        // like a missing decision, and then sent a delivered receipt for each one.
        Log.i(
            TAG,
            "no routing decision for ${incoming.messageId.take(8)}… " +
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
                is CfeAction.MessageDecrypted -> effects.deliverDecrypted(
                    action,
                    if (action.messageId == incoming.messageId) incoming else held.take(action.messageId),
                )
                is CfeAction.CallSignalDecrypted ->
                    effects.onCallSignal(action.contactId, action.messageId, action.protoBytes)
                is CfeAction.SendReceipt -> effects.sendReceipt(action.messageId, incoming.senderId, action.status)
                is CfeAction.NotifyNewMessage -> effects.notifyNewMessage(action.chatId, action.preview)
                is CfeAction.MarkMessageDelivered -> effects.markDelivered(action.messageId)
                is CfeAction.PersistAck -> effects.markProcessed(action.messageId, incoming.senderId)
                is CfeAction.PruneAckStore -> effects.pruneAckStore(action.cutoffTs.toLong())
                is CfeAction.SaveToSecureStore ->
                    effects.saveSecureStore(action.slot, action.data)
                is CfeAction.ArchiveSession -> effects.archiveSession(action.contactId)
                is CfeAction.SendDecryptionError ->
                    effects.sendDecryptionError(action.contactId, action.messageId, action.payload)
                is CfeAction.SessionRetired ->
                    effects.sessionRetired(action.contactId, action.withoutOneTimePrekey)
                is CfeAction.ResendMessage ->
                    effects.resendMessage(action.contactId, action.messageId)
                is CfeAction.DuplicateDropped ->
                    Log.d(TAG, "duplicate ${action.messageId.take(8)}… — routing records it")
                is CfeAction.NotifySessionCreated ->
                    Log.i(TAG, "session created ${action.contactId.take(8)}…")
                is CfeAction.NotifyError ->
                    Log.e(TAG, "CFE ${action.code}: ${action.message}")
                is CfeAction.ScheduleTimer ->
                    timerBridge.schedule(action.timerId, action.delayMs)
                is CfeAction.CancelTimer ->
                    timerBridge.cancel(action.timerId)
                is CfeAction.MessageQueuedPendingInit,
                is CfeAction.CheckAckInDb,
                is CfeAction.DecryptMessage,
                is CfeAction.EncryptMessage,
                is CfeAction.InitSession,
                is CfeAction.SendEncryptedMessage,
                is CfeAction.OpenReceiving,
                -> Log.d(TAG, "CFE action consumed by routing layer: ${action::class.simpleName}")
                // The machine's answers about *opening* a session. This client does not ask it —
                // its session opening is still its own, so nothing here consumes these and the
                // honest record is a warning, not a "consumed by" line that would read as wired.
                // iOS acts on `OpenSession` (`SessionActionExecutor`, the PQXDH v2 upgrade sweep).
                is CfeAction.OpenSession,
                -> Log.w(TAG, "CFE session-open action not acted on by this client: ${action::class.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "MessageProcessor"

        /** The core's `DECRYPT_FAILED` code (`orchestrator.rs`), attached to every refused decrypt. */
        const val DECRYPT_FAILED = "decrypt_failed"
    }

    private sealed interface CopyRouteResolution {
        data object NotADeviceCopy : CopyRouteResolution
        data object Ours : CopyRouteResolution
        data object Foreign : CopyRouteResolution
    }
}

/** Cursor-control result — mirrors iOS `streamOutcome`. */
enum class ProcessingOutcome {
    /** Decrypted & routed — advance the stream cursor. */
    Processed,

    /** Acked as delivered without a user-visible message — advance cursor. */
    Acked,

    /** Queued for a session to open — hold the cursor until it drains. */
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
    suspend fun sendReceipt(messageId: String, toUserId: String, status: String)
    suspend fun notifyNewMessage(chatId: String, preview: String)
    suspend fun markDelivered(messageId: String)
    suspend fun markProcessed(messageId: String, senderId: String)
    suspend fun saveSecureStore(slot: uniffi.construct_core.CfeSecureStoreSlot, data: ByteArray)
    suspend fun pruneAckStore(cutoffTs: Long)
    suspend fun archiveSession(contactId: String)

    /** We could not read [messageId] from [contactId]: send the core's sealed [payload] to that
     * device as a DECRYPTION_ERROR. */
    suspend fun sendDecryptionError(contactId: String, messageId: String, payload: ByteArray)

    /** The peer could not read our current state with [contactId] and the core retired it; the
     * next send opens a new one — without a one-time prekey when [withoutOneTimePrekey]. */
    suspend fun sessionRetired(contactId: String, withoutOneTimePrekey: Boolean)

    /** The peer could not read [messageId]: send it again to [contactId]. */
    suspend fun resendMessage(contactId: String, messageId: String)

    /** Open a receiving session with [device] from what the core holds for it, execute what the
     * open produced, and say what became of [trigger]. Nothing is announced: the peer learns the
     * session opened from our next message, as it does on iOS since 2026-09-27. */
    suspend fun openReceiving(
        device: String,
        trigger: MessageRouter.IncomingMessage?,
    ): ProcessingOutcome

    /** The core gave these up (a failed open): acknowledge them so their redelivery is not
     * processed again and the cursor moves past them. */
    suspend fun release(messageIds: List<String>)

    /** Whether [messageId] is already recorded delivered in the local DB
     * (answers the CFE `checkAckInDb` round-trip after a restart). */
    fun isAckedInDb(messageId: String): Boolean
}

/**
 * A message the core decrypted, routed by the envelope it came in: a SENDER_SYNC is our own
 * outgoing copy, a SESSION_RESET_INIT is a handshake with nothing to show, anything else an
 * incoming message. Nothing sends SESSION_RESET_INIT since 2026-09-27; the arm stays for one
 * from a client that predates it, which must not become a bubble. [envelope] is null only when nothing is kept for the id (a restart dropped
 * the core's queue with it); the message is then taken as incoming.
 *
 * The SESSION_RESET_INIT arm is the one iOS needed on 2026-09-26: an init opened by
 * `open_receiving` comes back as an ordinary decrypt, and its plaintext — a `$<uuid>` nonce — was
 * saved as a bubble on both sides. Seen here on the Android↔iOS stand, 2026-09-27.
 */
internal suspend fun ProcessorEffects.deliverDecrypted(
    action: CfeAction.MessageDecrypted,
    envelope: MessageRouter.IncomingMessage?,
) {
    val contactId = action.contactId.ifEmpty { envelope?.senderId.orEmpty() }
    if (envelope?.contentType == ContentType.CONTENT_TYPE_SESSION_RESET_INIT) {
        markProcessed(action.messageId, envelope.senderId)
    } else if (envelope?.contentType == ContentType.CONTENT_TYPE_SENDER_SYNC) {
        onSenderSync(contactId, action.messageId, action.plaintext, envelope.timestampMs)
    } else {
        onDecrypted(contactId, action.messageId, action.plaintext)
    }
}
