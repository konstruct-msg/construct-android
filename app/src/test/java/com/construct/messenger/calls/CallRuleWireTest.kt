package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.CallEndCause
import uniffi.construct_core.CallHangupReason
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

    /** Mutations: a server error read as a hangup or a local failure; an error code by the wrong number. */
    @Test
    fun howACallEndedReachesTheRuleAsItHappened() {
        assertEquals(
            CallEndCause.Hangup(CallHangupReason.DECLINED),
            CallRuleWire.cause(CallEndReason.Hangup(HangupReason.HANGUP_REASON_DECLINED)),
        )
        assertEquals(
            CallEndCause.ServerError(CallSignalError.CALLEE_BUSY),
            CallRuleWire.cause(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_CALLEE_BUSY)),
        )
        assertEquals(
            CallEndCause.ServerError(CallSignalError.CALL_EXPIRED),
            CallRuleWire.cause(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_CALL_EXPIRED)),
        )
        assertEquals(CallEndCause.Local, CallRuleWire.cause(CallEndReason.Local("ICE failed")))
    }
}
