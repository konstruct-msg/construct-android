package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.CallEndCause
import uniffi.construct_core.CallHangupReason
import uniffi.construct_core.CallHangupRoute
import uniffi.construct_core.CallHangupSide
import uniffi.construct_core.CallMachine
import uniffi.construct_core.CallMachinePhase
import uniffi.construct_core.CallSignalError

/** The bridge between this app's call values and the core rule's types (todo 295). */
class CallRuleWireTest {
    /** Mutation: any wrong number in the mapping — each proto reason must come back as itself. */
    @Test
    fun everyHangupReasonCrossesTheBridgeAndBack() {
        for (r in HangupReason.values().filter { it != HangupReason.UNRECOGNIZED }) {
            assertEquals("$r", r, CallRuleWire.proto(CallRuleWire.reason(r)))
        }
        assertEquals(CallHangupReason.ACCEPTED_ELSEWHERE, CallRuleWire.reason(HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE))
        assertEquals(CallHangupReason.BUSY, CallRuleWire.reason(HangupReason.HANGUP_REASON_BUSY))
    }

    @Test
    fun aReasonFromANewerPeerReadsAsUnspecified() {
        assertEquals(CallHangupReason.UNSPECIFIED, CallRuleWire.reason(HangupReason.UNRECOGNIZED))
    }

    private val session = CallSession("c1", "peer", "P", CallSession.Direction.INCOMING)

    /** Mutation: any two phases swapped in `CallRuleWire.state`, or the answer time dropped. */
    @Test
    fun theScreenShowsTheMachinesPhase() {
        assertEquals(CallState.Incoming(session), CallRuleWire.state(CallMachinePhase.INCOMING, session, 7))
        assertEquals(CallState.Dialing(session), CallRuleWire.state(CallMachinePhase.DIALING, session, 7))
        assertEquals(CallState.Ringing(session), CallRuleWire.state(CallMachinePhase.RINGING, session, 7))
        assertEquals(CallState.Connecting(session), CallRuleWire.state(CallMachinePhase.CONNECTING, session, 7))
        assertEquals(CallState.Active(session, 7), CallRuleWire.state(CallMachinePhase.ACTIVE, session, 7))
    }

    private fun dialled() = CallMachine().also { it.dial("c1", "peer", 0) }

    /**
     * How the call ended reaches the machine as it happened. Mutations: a server's hangup ended as a
     * local failure; an error read by the wrong number; a local failure ended as a hangup.
     */
    @Test
    fun theEndReachesTheMachineAsItHappened() {
        assertEquals(
            CallEndCause.Hangup(CallHangupReason.BUSY),
            CallRuleWire.end(CallEndReason.Hangup(HangupReason.HANGUP_REASON_BUSY), dialled(), 0)?.cause,
        )
        assertEquals(
            CallEndCause.ServerError(CallSignalError.CALLEE_BUSY),
            CallRuleWire.end(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_CALLEE_BUSY), dialled(), 0)?.cause,
        )
        assertEquals(
            CallEndCause.ServerError(CallSignalError.CALL_EXPIRED),
            CallRuleWire.end(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_CALL_EXPIRED), dialled(), 0)?.cause,
        )
        val limited = dialled()
        assertNull(CallRuleWire.end(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_RATE_LIMITED), limited, 0))
        assertNotNull("a rate limit drops one signal and ends nothing", limited.active())
        assertEquals(CallEndCause.Local, CallRuleWire.end(CallEndReason.Local("ICE failed"), dialled(), 0)?.cause)
        assertNull(CallRuleWire.end(CallEndReason.Local("x"), CallMachine(), 0))
    }

    /** Mutation: `hangupRoute` passes a fixed reason. "Accepted elsewhere" on the stream ends the call for the device that took it. */
    @Test
    fun takenElsewhereNeverReachesTheStream() {
        assertEquals(CallHangupRoute(e2ee = true, stream = false), CallRuleWire.hangupRoute(CallHangupSide.LOCAL, HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE))
        assertEquals(CallHangupRoute(e2ee = false, stream = false), CallRuleWire.hangupRoute(CallHangupSide.REMOTE, HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE))
    }

    /** Mutation: `hangupRoute` passes a fixed side. */
    @Test
    fun ourHangupGoesBothWaysAReceivedOneOnlyToTheServer() {
        assertEquals(CallHangupRoute(e2ee = true, stream = true), CallRuleWire.hangupRoute(CallHangupSide.LOCAL, HangupReason.HANGUP_REASON_NORMAL))
        assertEquals(CallHangupRoute(e2ee = false, stream = true), CallRuleWire.hangupRoute(CallHangupSide.REMOTE, HangupReason.HANGUP_REASON_NORMAL))
    }
}
