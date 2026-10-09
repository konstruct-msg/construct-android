package com.construct.messenger.service

import com.construct.messenger.transport.TransportRouter
import com.construct.messenger.data.auth.AuthSessionManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.crypto.KyberPrekeyService
import com.construct.messenger.data.api.MessageStreamService
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.api.StreamCursorTracker
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.model.IdentityIds
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.domain.usecase.RotateSignedPreKeyUseCase
import com.construct.messenger.domain.usecase.UploadPreKeysUseCase
import com.construct.messenger.stealth.BlindTokenService
import com.construct.messenger.stealth.IntakeCredentials
import com.construct.messenger.stealth.ServerKeysProvider
import com.construct.messenger.ui.components.ConnectionStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.EnvelopeOuterClass.SealedSenderEnvelope
import shared.proto.core.v1.Identity.UserId
import shared.proto.services.v1.MessagingServiceOuterClass.PendingMessage
import uniffi.construct_core.CfeIncomingEvent

/**
 * Process-scoped messaging lifecycle. Owns session restore, the receive pipeline,
 * and post-login stealth/OTPK bootstrap.
 *
 * **Not a ViewModel.** Splash / [com.construct.messenger.data.repository.AuthRepository]
 * call [start] after identity is ready; [start] is idempotent.
 *
 * Pipeline:
 * ```
 * import CFE sessions → hydrate ACK store → drain GetPendingMessages
 *   → MessageRouter.start + MessageProcessor collect
 *   → MessageStreamService.start
 *   → subscribe direct:<sorted ids> from Room
 * ```
 */
@Singleton
class MessagingRuntime @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val sessionStateStore: SessionStateStore,
    private val ackStore: AckStore,
    private val stream: MessageStreamService,
    private val router: MessageRouter,
    private val processor: MessageProcessor,
    private val messagingService: MessagingService,
    private val chats: ChatStore,
    private val uploadPreKeys: UploadPreKeysUseCase,
    private val serverKeys: ServerKeysProvider,
    private val blindTokens: BlindTokenService,
    private val intake: IntakeCredentials,
    private val rotateSignedPreKey: RotateSignedPreKeyUseCase,
    private val timerBridge: CfeTimerBridge,
    private val cursorTracker: StreamCursorTracker,
    private val kyberPrekeys: KyberPrekeyService,
    private val authSession: AuthSessionManager,
    private val transportRouter: TransportRouter,
    private val pendingResends: PendingResends,
    private val deviceMetadata: DeviceMetadataPublisher,
    private val contactAvatars: ContactAvatars,
    // Lazy: the profile is sent through the send path, which reaches back into the runtime.
    private val shareProfile: dagger.Lazy<com.construct.messenger.domain.usecase.ShareProfileUseCase>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reorder = ReorderDiagnostics(cryptoManager::reorderStats)
    private var reorderJob: Job? = null
    private var refreshJob: Job? = null
    private val startMutex = Mutex()

    @Volatile
    var isStarted: Boolean = false
        private set

    private var processorJob: Job? = null
    private var subscriptionJob: Job? = null

    val lastHeartbeatAt: StateFlow<Long?> get() = stream.lastHeartbeatAt

    val connectionStatus: StateFlow<ConnectionStatus> = stream.isConnected
        .map { connected -> if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus.UNKNOWN)

    /**
     * Bring the receive path up. No-op if already started or if the orchestrator
     * is not ready ([CryptoManager.setLocalUserId] has not run).
     */
    suspend fun start() = startMutex.withLock {
        if (isStarted) return
        if (!cryptoManager.isMessagingReady) {
            Log.w(TAG, "start skipped — orchestrator not ready (setLocalUserId first)")
            return
        }

        restoreSessions()
        ackStore.hydrate()
        // Every RPC below carries the stored token; one that expired overnight was refused
        // across the board (device log 2026-09-29).
        authSession.ensureFresh()
        if (refreshJob?.isActive != true) refreshJob = scope.launch { authSession.keepFresh() }
        // Before the first RPC: with VEIL on, the route machine starts the proxy now.
        transportRouter.start()

        // The collector must be subscribed before drainPending(): MessageRouter.routed is a
        // SharedFlow with no replay, and tryEmit with no subscriber drops the event. Drained
        // first, every message queued while offline was fetched and discarded without a trace
        // (seen on a device, 2026-09-24: "drained 2 pending message(s)", no chat). UNDISPATCHED
        // runs the launch up to its first suspension — inside collect, after subscribing.
        if (processorJob?.isActive != true) {
            processorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { collectRouted() }
        }
        drainPending()
        router.start(scope)
        stream.start(scope)
        timerBridge.start()
        if (subscriptionJob?.isActive != true) {
            subscriptionJob = scope.launch {
                chats.observeAll().collect { rows ->
                    stream.updateSubscriptions(rows.map { it.id })
                }
            }
        }

        if (reorderJob?.isActive != true) {
            reorderJob = scope.launch {
                while (true) {
                    runCatching { reorder.sample() }
                    delay(REORDER_SAMPLE_MS)
                }
            }
        }

        isStarted = true
        Log.i(TAG, "messaging runtime started")
        launchBackgroundBootstrap()
    }

    fun stop() {
        stream.stop()
        timerBridge.stop()
        router.stop()
        processorJob?.cancel()
        processorJob = null
        subscriptionJob?.cancel()
        subscriptionJob = null
        reorderJob?.cancel()
        reorderJob = null
        isStarted = false
    }

    private suspend fun restoreSessions() {
        val blobs = sessionStateStore.loadAllSessions()
        if (blobs.isEmpty()) return

        // The orchestrator owns more than the ratchet. Restore its coordination
        // snapshots before any queued message reaches CFE; importing every Room
        // row as a session would feed archive/PQ/core state into the wrong API.
        blobs[SessionStateStore.ORCHESTRATOR_STATE_KEY]?.let { bytes ->
            runCatching { cryptoManager.importOrchestratorState(bytes) }
                .onFailure { Log.e(TAG, "orchestrator state restore failed", it) }
        }
        // Rows builds before PQXDH v2 wrote for the ML-KEM-768 layer (Kyber session state, a
        // Kyber SPK slot, deferred PQ contributions). Nothing reads them: the Kyber prekeys are
        // the core's own store now, restored with the orchestrator.
        blobs.keys.filter { SessionStateStore.isLegacyPqKey(it) }.forEach { key ->
            runCatching { sessionStateStore.removeSession(key) }
        }

        val sessions = blobs
            .filterKeys {
                it.startsWith(SessionStateStore.SESSION_KEY_PREFIX) &&
                    IdentityIds.isCryptoDeviceId(it.removePrefix(SessionStateStore.SESSION_KEY_PREFIX))
            }
            .mapKeys { (key, _) -> key.removePrefix(SessionStateStore.SESSION_KEY_PREFIX) }
        val refused = sessionManager.importSessions(sessions)
        // Left on disk, a refused blob would be refused again on every start — and iOS learned
        // that an unusable one can resurrect a stale session later (2026-08-17).
        refused.forEach { runCatching { sessionStateStore.removeSession(SessionStateStore.SESSION_KEY_PREFIX + it) } }
        Log.i(TAG, "restored ${sessions.size - refused.size} of ${sessions.size} session blob(s) and core snapshots")
    }

    private suspend fun drainPending() {
        runCatching {
            // From the committed cursor, one page, and nothing committed from the answer: the
            // server trims up to the cursor it is sent, and a page boundary says nothing about
            // which of the page's messages reached a durable end. The stream's replay from the
            // same cursor re-delivers these entries and advances over them properly.
            // Canon: iOS BackgroundFetchManager.
            val response = messagingService.getPendingMessages(sinceCursor = cursorTracker.committedCursor())
            for (pending in response.messagesList) {
                router.ingest(pending.toEnvelope())
            }
            if (response.messagesCount > 0) {
                Log.i(TAG, "drained ${response.messagesCount} pending message(s)")
            }
        }.onFailure { Log.w(TAG, "pending-message drain failed — stream will catch up", it) }
    }

    private suspend fun collectRouted() {
        router.routed.collect { event ->
            when (event) {
                // A throw leaves the entry pending: the cursor stalls and the server re-delivers,
                // which is safe. Advancing on a failure would be the loss the tracker prevents.
                is MessageRouter.RoutedEvent.Incoming ->
                    runCatching { processor.process(event.message) }
                        .onSuccess { reportCursor(event.message.messageId, it) }
                        .onFailure { Log.e(TAG, "process incoming failed", it) }
                is MessageRouter.RoutedEvent.Control ->
                    runCatching { handleControl(event.message) }
                        .onSuccess { reportCursor(event.message.messageId, it) }
                        .onFailure { Log.e(TAG, "process control failed", it) }
                is MessageRouter.RoutedEvent.Typing -> Unit
                is MessageRouter.RoutedEvent.ConnectionChanged -> {
                    if (event.connected) {
                        timerBridge.onNetworkReconnected()
                        // Resends the network refused; the core will not ask for them again.
                        pendingResends.drainSoon()
                        // Profiles: avatars that did not arrive, and a rebroadcast whose avatar did not upload.
                        contactAvatars.retryPending()
                        shareProfile.get().rebroadcastIfOwed()
                    }
                    Log.i(TAG, "stream connected=${event.connected}")
                }
            }
        }
    }

    private suspend fun handleControl(message: MessageRouter.IncomingMessage): ProcessingOutcome =
        when (message.contentType) {
            // END_SESSION (21) from a build before 2026-09-27: acknowledged and nothing else. It
            // named no state, so nothing here could tell whether it was about the one we hold —
            // which is what archived the session opened after it, on every start, on the stand
            // that day (the redelivery fix `5c24a06` was the half-measure).
            ContentType.CONTENT_TYPE_SESSION_RESET -> {
                Log.i(TAG, "END_SESSION ${message.messageId.take(8)}… — retired type, acknowledged and ignored")
                ackStore.markProcessed(message.messageId, message.senderId)
                ProcessingOutcome.Acked
            }
            // DECRYPTION_ERROR (28): the peer could not read something we sent it. The core opens
            // it and answers — retire our current state when the error names it, resend the named
            // message once, or nothing when it is stale. Canon: iOS
            // `SessionCoordinator.messageRouter(_:receivedDecryptionError:payload:opened:)`. Out of a
            // session envelope the payload is the error itself (`opened`), not a box to open.
            ContentType.CONTENT_TYPE_DECRYPTION_ERROR -> {
                val device = message.senderDeviceId
                if (device.isEmpty()) {
                    // Unsealed: nothing names the device, and a record is per device.
                    Log.i(TAG, "DECRYPTION_ERROR ${message.messageId.take(8)}… names no device — ignored")
                } else {
                    runCatching {
                        cryptoManager.handleEvent(
                            CfeIncomingEvent.DecryptionErrorReceived(
                                device,
                                message.encryptedPayload,
                                opened = message.envelopeSession != null,
                            ),
                        )
                    }.onSuccess { actions ->
                        Log.i(TAG, "DECRYPTION_ERROR from ${device.take(8)}… — ${if (actions.isEmpty()) "stale, nothing to do" else "${actions.size} action(s)"}")
                        timerBridge.execute(actions)
                    }.onFailure { Log.e(TAG, "DECRYPTION_ERROR from ${device.take(8)}… not delivered to the core", it) }
                }
                ackStore.markProcessed(message.messageId, message.senderId)
                ProcessingOutcome.Acked
            }
            ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
            ContentType.CONTENT_TYPE_KEY_EXCHANGE,
            ContentType.CONTENT_TYPE_SENDER_SYNC,
            -> processor.process(message)
            else -> {
                Log.d(TAG, "control ${message.contentType} ${message.messageId.take(8)}… — acked, not rendered")
                ackStore.markProcessed(message.messageId, message.senderId)
                ProcessingOutcome.Acked
            }
        }

    private fun reportCursor(messageId: String, outcome: ProcessingOutcome) {
        cursorTracker.report(
            messageId,
            when (outcome) {
                ProcessingOutcome.Processed, ProcessingOutcome.Acked -> StreamCursorTracker.Outcome.Durable
                ProcessingOutcome.Deferred -> StreamCursorTracker.Outcome.Deferred
            },
        )
        if (outcome == ProcessingOutcome.Deferred) {
            cursorTracker.headBlocker()?.let { Log.i(TAG, "cursor held by $it") }
        }
    }

    private fun launchBackgroundBootstrap() {
        scope.launch {
            runCatching { serverKeys.prefetch() }
                .onFailure { Log.w(TAG, "stealth key prefetch failed", it) }
            runCatching { blindTokens.bootstrapInitialBatch() }
                .onFailure { Log.w(TAG, "privacy-pass bootstrap failed", it) }
            // After the server keys: nothing here seals, but a start that has them is a start
            // that reached the server.
            intake.publishIfNeeded()
            val deviceId = keystoreManager.getDeviceId()
            if (deviceId != null) {
                // Before the replenishment: one-time Kyber keys ride on the classic upload only
                // once this has published the hybrid identity.
                kyberPrekeys.publishIfNeeded(deviceId)
                runCatching { uploadPreKeys.replenishIfNeeded(deviceId) }
                    .onFailure { Log.w(TAG, "OTPK replenish failed", it) }
            }
            runCatching { rotateSignedPreKey.rotateIfNeeded() }
                .onFailure { Log.w(TAG, "SPK rotation failed", it) }
            // The own device set is known from here on; it is what invalidates the description.
            deviceMetadata.publishIfNeeded()
        }
    }

    private companion object {
        const val TAG = "MessagingRuntime"
        /** iOS `RuntimeDiagnostics.Config.sampleInterval`. */
        const val REORDER_SAMPLE_MS = 30_000L
    }
}

private fun PendingMessage.toEnvelope(): Envelope = Envelope.newBuilder().apply {
    setMessageId(messageId)
    setTimestamp(timestamp)
    setContentType(contentType)
    if (sealedInnerData.size() > 0) {
        sealedSender = SealedSenderEnvelope.newBuilder()
            .setSealedInner(sealedInnerData)
            .build()
    } else {
        sender = UserId.newBuilder().setUserId(senderId).build()
        setEncryptedPayload(encryptedPayload)
    }
}.build()
