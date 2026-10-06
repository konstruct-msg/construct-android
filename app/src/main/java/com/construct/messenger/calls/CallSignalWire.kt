package com.construct.messenger.calls

import com.construct.messenger.util.KnstFrame
import java.util.UUID
import shared.proto.signaling.v1.SignalingServiceOuterClass.RoutedWebRtcSignal
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalPing
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalRequest
import shared.proto.signaling.v1.Webrtc.CallConnected
import shared.proto.signaling.v1.Webrtc.CallHangup
import shared.proto.signaling.v1.Webrtc.CallRinging
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.IceCandidateBatch
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/**
 * The bytes of call signalling. **Canon:** iOS `CallSignalCrypto`, `CallManager`
 * (`sendCallSignalProto`, `sendIceCandidate`, `makePing`, `makeRoutedSignal`) and `CallTypes`
 * (`signalStreamAdmission`). Pure, so every rule here has a test that needs no session.
 */
object CallSignalWire {

    /**
     * A `WebRTCSignal` as the core encrypts it: one whole KNST frame, content type 12 in byte 5 —
     * inside the ciphertext, so nothing on the wire says "call". Never chunked: an offer can be
     * longer than a chunk, and this is exactly one message.
     *
     * Whole, not [KnstFrame.pack]: an offer with a video section is ~3.9 KB, past the 3770-byte
     * chunk payload `pack` refuses, and every video call failed before ringing ("offer not sent",
     * Redmi, 0.17.0). iOS frames it the same way (`ChunkedMessageCodec.frameWhole`).
     */
    fun frame(signal: WebRTCSignal, messageId: UUID): ByteArray =
        KnstFrame.whole(signal.toByteArray(), KnstFrame.TYPE_CALL_SIGNAL, messageId)

    /**
     * ICE candidates of one flush, as signals that each stay well under the core's 65 536-byte
     * padding cap — a 134 KB batch failed whole on an iOS device (`CALL_SIGNAL_ENCRYPT_FAILED`).
     * Each candidate is a whole wire payload, so the bound is on what they carry, plus a margin
     * for the proto around it.
     */
    fun batches(candidates: List<IceCandidate>, maxBytes: Int = MAX_SIGNAL_BYTES): List<List<IceCandidate>> {
        val out = mutableListOf<List<IceCandidate>>()
        var current = mutableListOf<IceCandidate>()
        var bytes = 0
        for (c in candidates) {
            val size = c.candidate.size() + c.sdpMid.toByteArray(Charsets.UTF_8).size + 16
            if (current.isNotEmpty() && bytes + size > maxBytes) {
                out += current
                current = mutableListOf()
                bytes = 0
            }
            current += c
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** One flushed batch as a signal: a lone candidate in `ice_candidate`, more in `ice_candidates`. */
    fun iceSignal(callId: String, deviceId: String, batch: List<IceCandidate>, nowMs: Long): WebRTCSignal {
        val signal = base(callId, deviceId, nowMs)
        if (batch.size == 1) signal.iceCandidate = batch[0] else signal.iceCandidates = IceCandidateBatch.newBuilder().addAllCandidates(batch).build()
        return signal.build()
    }

    fun hangup(callId: String, deviceId: String, reason: HangupReason, nowMs: Long): WebRTCSignal =
        base(callId, deviceId, nowMs).setHangup(
            CallHangup.newBuilder().setReason(reason).setDeviceId(deviceId).setHangupAt(nowMs),
        ).build()

    // ── The signalling stream ───────────────────────────────────────────────

    /** Every 10 s — under the server's 15 s staleness reaper (iOS `NetworkTiming`). */
    const val PING_INTERVAL_MS = 10_000L

    fun ping(nowMs: Long): SignalRequest =
        SignalRequest.newBuilder().setPing(SignalPing.newBuilder().setTimestamp(nowMs)).build()

    /** Routed by the server's call registry: the route stays empty, as iOS leaves it. */
    fun routed(signal: WebRTCSignal): SignalRequest =
        SignalRequest.newBuilder().setRoutedSignal(RoutedWebRtcSignal.newBuilder().setSignal(signal)).build()

    /** The callee answered (sent on the stream after answering, not when the ring starts). */
    fun ringing(callId: String, deviceId: String, nowMs: Long): WebRTCSignal =
        base(callId, deviceId, nowMs).setRinging(CallRinging.newBuilder().setDeviceId(deviceId).setRingingAt(nowMs)).build()

    /**
     * Media is up. The server stamps `answered_at_ms` from it and moves the call from the
     * ringing reaper to the lenient keepalive one.
     */
    fun connected(callId: String, deviceId: String, nowMs: Long): WebRTCSignal =
        base(callId, deviceId, nowMs).setConnected(CallConnected.newBuilder().setDeviceId(deviceId).setConnectedAt(nowMs)).build()

    /**
     * What the stream may carry: ringing, busy, connected, hangup, ICE. Never an offer or an
     * answer — SDP comes only out of the Double Ratchet, and a server that put one on the stream
     * would be choosing the call's media keys (iOS `signalStreamAdmission`, 2026-08-21).
     */
    fun admittedOnStream(signal: WebRTCSignal): Boolean = when (signal.signalCase) {
        WebRTCSignal.SignalCase.OFFER, WebRTCSignal.SignalCase.ANSWER -> false
        WebRTCSignal.SignalCase.SIGNAL_NOT_SET -> false
        else -> true
    }

    private fun base(callId: String, deviceId: String, nowMs: Long) =
        WebRTCSignal.newBuilder().setCallId(callId).setSenderDeviceId(deviceId).setTimestamp(nowMs)

    const val MAX_SIGNAL_BYTES = 40_000
}

/**
 * An encrypted ICE candidate: `[0x04][the core's wire payload]` in `IceCandidate.candidate`.
 * **Canon:** iOS `CallSignalFrame`. The version byte is how a malformed value is refused rather
 * than handed to the core; no older version is read.
 */
object CallSignalFrame {
    const val VERSION: Byte = 0x04

    fun encode(wirePayload: ByteArray): ByteArray = byteArrayOf(VERSION) + wirePayload

    fun decode(frame: ByteArray): ByteArray? =
        if (frame.size > 1 && frame[0] == VERSION) frame.copyOfRange(1, frame.size) else null
}
