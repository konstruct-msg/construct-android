package com.construct.messenger.calls

import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.CallEnded
import uniffi.construct_core.CallHangupReason
import uniffi.construct_core.CallHangupRoute
import uniffi.construct_core.CallHangupSide
import uniffi.construct_core.CallHistoryStatus
import uniffi.construct_core.CallMachine
import uniffi.construct_core.CallMachinePhase
import uniffi.construct_core.callAdmits
import uniffi.construct_core.callHangupReasonFromWire
import uniffi.construct_core.callHangupReasonWire
import uniffi.construct_core.callHangupRoute
import uniffi.construct_core.callOfferDevices
import uniffi.construct_core.callSignalErrorFromWire
import uniffi.construct_core.callSignalTargets

/**
 * Which of the other person's devices each call signal goes to and comes from (vault
 * `decisions/a-call-rings-every-device.md`). The rule is the core's (`construct-rules` `calls`,
 * construct-core 0.73.0, todo 295), shared with iOS and construct-client; this object only carries
 * this app's values to it.
 *
 * - The offer goes to every device of theirs the core plans a send to and we hold a session with.
 * - Until someone answers, everything we send goes to all of them.
 * - The first answer picks the device; from then on we talk to it alone, and the others are told
 *   the call was taken elsewhere (`HANGUP_REASON_ACCEPTED_ELSEWHERE`).
 * - A device is known by the session the core opened its signal under (`CallSignalDecrypted`'s
 *   contact id), never by the `sender_device_id` the sender writes itself.
 *
 * Until 2026-10-21 every signal went to the peer's pinned device: a second device of the caller
 * never heard the answer, and a second device of the callee never rang.
 *
 * Device ids are `CryptoDeviceId`s throughout.
 */
object CallDeviceRouting {
    /**
     * Who a call signal is encrypted for. [chosen]: the device this call talks to — the one that
     * sent the offer (callee side) or answered first (caller side). [offered]: the devices our
     * offer went to. [pinned]: the device the peer's pinned key names, for a call that knows
     * neither.
     */
    fun targets(chosen: String?, offered: List<String>, pinned: String?): List<String> =
        callSignalTargets(chosen, offered, pinned)

    /**
     * The devices an offer goes to: those the core planned a send to that we hold a session with,
     * in the plan's order; the pinned device when none has one.
     */
    fun offerDevices(planned: List<String>, hasSession: (String) -> Boolean, pinned: String?): List<String> =
        callOfferDevices(planned, planned.filter(hasSession), pinned)

    /** Before a device is chosen, any of the peer's devices may speak (an answer, a decline); after, only the chosen one. */
    fun admits(device: String, chosen: String?): Boolean = callAdmits(device, chosen)
}

/**
 * This app's call values in the core rule's types. The wire enums map by their numbers, so an
 * unknown value reads as the rule's `UNSPECIFIED`, never as a guess.
 */
object CallRuleWire {
    fun reason(r: HangupReason): CallHangupReason =
        callHangupReasonFromWire(if (r == HangupReason.UNRECOGNIZED) 0 else r.number)

    fun proto(r: CallHangupReason): HangupReason =
        HangupReason.forNumber(callHangupReasonWire(r)) ?: HangupReason.HANGUP_REASON_UNSPECIFIED

    /** What the call screen shows for the machine's [phase] of [session]; [sinceMs]: when it was answered. */
    fun state(phase: CallMachinePhase, session: CallSession, sinceMs: Long): CallState = when (phase) {
        CallMachinePhase.INCOMING -> CallState.Incoming(session)
        CallMachinePhase.DIALING -> CallState.Dialing(session)
        CallMachinePhase.RINGING -> CallState.Ringing(session)
        CallMachinePhase.CONNECTING -> CallState.Connecting(session)
        CallMachinePhase.ACTIVE -> CallState.Active(session, sinceMs)
    }

    /**
     * End the machine's call for [reason]: a server's hangup or busy (over the stream, so nothing
     * goes back), a server's error, or a failure of ours. A hangup we send, or one the peer sent end
     * to end, ends through `hangUp` / `hangupFrom`, which also say where it goes. Null when the
     * machine holds no call, or for a rate limit, which ends nothing.
     */
    fun end(reason: CallEndReason, machine: CallMachine, nowMs: Long): CallEnded? = when (reason) {
        is CallEndReason.Hangup -> machine.endedByServer(reason(reason.reason), nowMs)
        is CallEndReason.Error -> machine.serverError(
            callSignalErrorFromWire(if (reason.code == SignalErrorCode.UNRECOGNIZED) 0 else reason.code.number),
            nowMs,
        )
        is CallEndReason.Local -> machine.fail(nowMs)
    }

    /** Which channels a hangup with this wire reason is written to (`callHangupRoute`). */
    fun hangupRoute(side: CallHangupSide, r: HangupReason): CallHangupRoute = callHangupRoute(side, reason(r))

    /** The history status as this app stores it (the same codes 0–3 as the core's and iOS's). */
    fun record(s: CallHistoryStatus): CallRecordStatus = when (s) {
        CallHistoryStatus.COMPLETED -> CallRecordStatus.COMPLETED
        CallHistoryStatus.MISSED -> CallRecordStatus.MISSED
        CallHistoryStatus.DECLINED -> CallRecordStatus.DECLINED
        CallHistoryStatus.FAILED -> CallRecordStatus.FAILED
    }
}
