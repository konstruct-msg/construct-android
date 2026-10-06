package com.construct.messenger.calls

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import shared.proto.signaling.v1.SignalingServiceOuterClass.GetTurnCredentialsRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.InitiateCallRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.InitiateCallResponse
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalResponse
import shared.proto.signaling.v1.Webrtc.CallType
import shared.proto.signaling.v1.Webrtc.TurnCredentials

/**
 * `SignalingService` (`services/signaling_service.proto`), on the authenticated channel.
 * **Canon:** iOS `SignalingServiceClient`.
 *
 * The server keeps the call registry and occupancy; it never sees SDP. `InitiateCall` registers a
 * call before its offer goes out by E2EE, the stream carries ringing / connected / hangup and
 * pings, and TURN credentials come from here.
 */
@Singleton
class SignalingClient @Inject constructor(
    private val grpc: GrpcClient,
) : CallSignalingPort {
    private val turn = TurnCache()

    /**
     * Register an outgoing call. The server checks contacts, blocks, busy and rate, keeps the call
     * 90 s, and tells the callee — by its stream or not at all; it holds no SDP.
     */
    override suspend fun initiateCall(callId: String, calleeUserId: String, callerName: String, video: Boolean): InitiateCallResponse =
        withTimeout(INITIATE_TIMEOUT_MS) {
            grpc.signaling.initiateCall(
                InitiateCallRequest.newBuilder()
                    .setCallId(callId)
                    .setCalleeUserId(calleeUserId)
                    .setCallerName(callerName)
                    .setCallType(if (video) CallType.CALL_TYPE_VIDEO else CallType.CALL_TYPE_AUDIO)
                    .build(),
            )
        }

    /**
     * TURN credentials — the user's, not the call's (coturn REST: HMAC over `expiry:userId`), so
     * one valid set serves every call until it is about to expire. Fetching per call ran into the
     * per-user rate limit on iOS (back-to-back calls fell back to STUN and connected silent).
     */
    override suspend fun turnCredentials(callId: String?): TurnCredentials = turn.get {
        withTimeout(TURN_TIMEOUT_MS) {
            grpc.signaling.getTurnCredentials(
                GetTurnCredentialsRequest.newBuilder().apply { callId?.let(::setCallId) }.build(),
            ).credentials
        }
    }

    /**
     * The signalling stream: what goes into [outbound] is sent, with a ping every 10 s; what the
     * server sends comes out. Ends when the server closes it or the collector stops — a closed
     * stream is not a hung-up call (iOS `signalingStreamClosedDisposition`), the caller decides.
     */
    override fun stream(outbound: Channel<SignalRequest>): Flow<SignalResponse> = channelFlow {
        val requests = merge(
            outbound.consumeAsFlow(),
            flow {
                while (true) {
                    emit(CallSignalWire.ping(System.currentTimeMillis()))
                    delay(CallSignalWire.PING_INTERVAL_MS)
                }
            },
        )
        grpc.signaling.signal(requests).collect { send(it) }
    }

    /** One cached set of credentials, renewed [skewMs] before it expires. [nowMs] is for tests. */
    class TurnCache(private val skewMs: Long = TURN_SKEW_MS, private val nowMs: () -> Long = System::currentTimeMillis) {
        private val lock = Mutex()
        private var cached: TurnCredentials? = null

        fun valid(): TurnCredentials? {
            val c = cached ?: return null
            // expires_at 0: a server that does not say — kept, as iOS keeps it.
            return c.takeIf { it.expiresAt == 0L || it.expiresAt - nowMs() > skewMs }
        }

        suspend fun get(fetch: suspend () -> TurnCredentials): TurnCredentials = lock.withLock {
            valid() ?: fetch().also {
                cached = it
                Log.i("Signaling", "TURN credentials: ${it.urlsCount} url(s), expire in ${(it.expiresAt - nowMs()) / 1000}s")
            }
        }
    }

    private companion object {
        const val INITIATE_TIMEOUT_MS = 20_000L
        const val TURN_TIMEOUT_MS = 15_000L
        const val TURN_SKEW_MS = 60_000L
    }
}
