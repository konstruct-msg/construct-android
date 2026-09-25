package com.construct.messenger.service

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.crypto.KyberPrekeyService
import com.construct.messenger.data.api.MessageStreamService
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.model.IdentityIds
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.domain.usecase.RotateSignedPreKeyUseCase
import com.construct.messenger.domain.usecase.SessionControlUseCase
import com.construct.messenger.domain.usecase.UploadPreKeysUseCase
import com.construct.messenger.stealth.BlindTokenService
import com.construct.messenger.stealth.ServerKeysProvider
import com.construct.messenger.ui.components.ConnectionStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val sessionControl: SessionControlUseCase,
    private val uploadPreKeys: UploadPreKeysUseCase,
    private val serverKeys: ServerKeysProvider,
    private val blindTokens: BlindTokenService,
    private val rotateSignedPreKey: RotateSignedPreKeyUseCase,
    private val timerBridge: CfeTimerBridge,
    private val kyberPrekeys: KyberPrekeyService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    @Volatile
    var isStarted: Boolean = false
        private set

    private var processorJob: Job? = null
    private var subscriptionJob: Job? = null

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
        drainPending()

        if (processorJob?.isActive != true) {
            processorJob = scope.launch { collectRouted() }
        }
        router.start(scope)
        stream.start(scope)
        timerBridge.start()
        if (subscriptionJob?.isActive != true) {
            subscriptionJob = scope.launch {
                chatDao.observeAll().collect { chats ->
                    stream.updateSubscriptions(chats.map { it.id })
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
        if (sessions.isNotEmpty()) {
            runCatching { sessionManager.importSessions(sessions) }
                .onFailure { Log.e(TAG, "session import failed (${sessions.size} blobs)", it) }
        }
        Log.i(TAG, "restored ${sessions.size} session blob(s) and core snapshots")
    }

    private suspend fun drainPending() {
        runCatching {
            val response = messagingService.getPendingMessages()
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
                is MessageRouter.RoutedEvent.Incoming ->
                    runCatching { processor.process(event.message) }
                        .onFailure { Log.e(TAG, "process incoming failed", it) }
                is MessageRouter.RoutedEvent.Control ->
                    runCatching { handleControl(event.message) }
                        .onFailure { Log.e(TAG, "process control failed", it) }
                is MessageRouter.RoutedEvent.Receipt -> {
                    val ids = if (event.receipt.hasDirect()) {
                        event.receipt.direct.messageIdsList
                    } else {
                        emptyList()
                    }
                    applyTransportReceipts(ids)
                }
                is MessageRouter.RoutedEvent.Typing -> Unit
                is MessageRouter.RoutedEvent.ConnectionChanged -> {
                    if (event.connected) {
                        timerBridge.onNetworkReconnected()
                    }
                    Log.i(TAG, "stream connected=${event.connected}")
                }
            }
        }
    }

    private suspend fun handleControl(message: MessageRouter.IncomingMessage) {
        when (message.contentType) {
            ContentType.CONTENT_TYPE_SESSION_RESET -> {
                sessionControl.inboundEndSession(message.senderId)
                ackStore.markProcessed(message.messageId, message.senderId)
            }
            ContentType.CONTENT_TYPE_SESSION_RESET_INIT,
            ContentType.CONTENT_TYPE_KEY_EXCHANGE,
            ContentType.CONTENT_TYPE_SENDER_SYNC,
            -> processor.process(message)
            else -> {
                Log.d(TAG, "control ${message.contentType} ${message.messageId.take(8)}… — acked, not rendered")
                ackStore.markProcessed(message.messageId, message.senderId)
            }
        }
    }

    private suspend fun applyTransportReceipts(ids: List<String>) {
        for (id in ids) {
            runCatching { messageDao.updateDeliveryStatus(id, DeliveryStatus.DELIVERED.name) }
        }
    }

    private fun launchBackgroundBootstrap() {
        scope.launch {
            runCatching { serverKeys.prefetch() }
                .onFailure { Log.w(TAG, "stealth key prefetch failed", it) }
            runCatching { blindTokens.bootstrapInitialBatch() }
                .onFailure { Log.w(TAG, "privacy-pass bootstrap failed", it) }
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
        }
    }

    private companion object {
        const val TAG = "MessagingRuntime"
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
