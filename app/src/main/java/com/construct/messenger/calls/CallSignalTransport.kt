package com.construct.messenger.calls

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessageStreamService
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.service.SessionManager
import com.google.protobuf.ByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.WebRTCSignal
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * Call signals out, over the E2EE message path, and ICE candidates sealed to the session.
 * **Canon:** iOS `CallManager.sendCallSignalProto` / `callSignalSendChain` and `CallSignalCrypto`.
 *
 * Every SDP, candidate and hangup goes this way and only this way — the signalling stream never
 * carries SDP. Sends are one at a time, in call order: the core encrypts them in order and the
 * server must receive them so, or an ICE batch could overtake the offer it belongs to.
 */
@Singleton
class CallSignalTransport @Inject constructor(
    private val sendMessage: SendMessageUseCase,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val sessions: SessionStateStore,
    // Lazy: the stream feeds the processor that feeds the call machine that holds this.
    private val stream: dagger.Lazy<MessageStreamService>,
) : CallSignalPort {
    private val chain = Mutex()

    /** Signals arrive on the message stream; ask it to prove it is alive. */
    override fun checkInbound() = stream.get().probe()

    /** [signal] to [peerAccountId]'s pinned device. True when the server took it. */
    override suspend fun send(peerAccountId: String, signal: WebRTCSignal): Boolean = chain.withLock {
        val messageId = UUID.randomUUID()
        val sent = sendMessage.sendCallSignal(peerAccountId, messageId.toString(), CallSignalWire.frame(signal, messageId))
        Log.i(TAG, "signal ${signal.signalCase} call=${signal.callId.take(8)}… to ${peerAccountId.take(8)}… — ${if (sent) "sent" else "NOT sent"}")
        sent
    }

    /**
     * One candidate line ("candidate:1 1 UDP … typ host" — an address and a port) sealed to the
     * session with the peer's pinned device, the same ratchet the offer named, so the signalling
     * server forwards what it cannot read. The core pads it to a fixed block: every candidate is
     * the same size.
     */
    override suspend fun sealCandidate(peerAccountId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String): IceCandidate {
        val device = sessionManager.ensureSession(peerAccountId).deviceId
        val wire = cryptoManager.encryptToWire(device, candidate.toByteArray(Charsets.UTF_8))
        persist(device)
        return IceCandidate.newBuilder()
            .setCandidate(ByteString.copyFrom(CallSignalFrame.encode(wire)))
            .setSdpMid(sdpMid)
            .setSdpMLineIndex(sdpMLineIndex.coerceAtLeast(0))
            .build()
    }

    /** The candidate line inside [ice], from [deviceId]; null when it is not a frame or does not open. */
    override suspend fun openCandidate(deviceId: String, ice: IceCandidate): String? {
        val wire = CallSignalFrame.decode(ice.candidate.toByteArray()) ?: return null
        return runCatching { cryptoManager.decryptWirePayload(deviceId, wire) }
            .onFailure { Log.w(TAG, "candidate from ${deviceId.take(8)}… did not open: ${it.message}") }
            .getOrNull()
            ?.also { persist(deviceId) }
            ?.toString(Charsets.UTF_8)
    }

    override suspend fun peerDevice(peerAccountId: String): String? = try {
        sessionManager.ensureSession(peerAccountId).deviceId
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "no session with ${peerAccountId.take(8)}…: ${e.message}")
        null
    }

    /** The ratchet moved: the session is written before anything it produced is used. */
    private suspend fun persist(deviceId: String) {
        sessions.saveSecureStore(CfeSecureStoreSlot.Session(deviceId), cryptoManager.exportSessionBytes(deviceId))
    }

    private companion object {
        const val TAG = "CallSignal"
    }
}
