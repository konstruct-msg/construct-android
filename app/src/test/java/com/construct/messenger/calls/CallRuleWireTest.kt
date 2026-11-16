package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.CallEndCause
import uniffi.construct_core.CallHangupReason
import uniffi.construct_core.CallHangupRoute
import uniffi.construct_core.CallHangupSide
import uniffi.construct_core.CallOfferAction
import uniffi.construct_core.CallSignalError
import uniffi.construct_core.callOnOffer

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

    private fun incoming(id: String = "c1", from: String = "peer") = CallSession(id, from, "P", CallSession.Direction.INCOMING)
    private fun outgoing(id: String = "c0", to: String = "peer") = CallSession(id, to, "P", CallSession.Direction.OUTGOING)

    /** An offer for [callId] from [offerer], read through [CallRuleWire.held] into the core's rule. */
    private fun action(
        session: CallSession?,
        callId: String = "c1",
        offerer: String = "peer",
        me: String = "me",
        ended: Boolean = false,
        answered: Boolean = false,
    ): CallOfferAction = callOnOffer(ended, CallRuleWire.held(callId, offerer, session, answered), me, offerer)

    /** Mutations: `held` never names the same call; `held` reads every call as answered. */
    @Test
    fun anOfferForTheRingingCallIsHeldUntilTheAnswer() {
        assertEquals(CallOfferAction.HOLD_UNTIL_ANSWERED, action(incoming()))
    }

    /** Mutation: `held` reads every call as unanswered — an ICE restart would wait for a consent given long ago. */
    @Test
    fun anAnsweredCallRenegotiates() {
        assertEquals(CallOfferAction.RENEGOTIATE, action(incoming(), answered = true))
    }

    /** Mutation: `direction` reads outgoing as incoming. */
    @Test
    fun anOfferOnOurOwnCallRenegotiates() {
        assertEquals(CallOfferAction.RENEGOTIATE, action(outgoing(), callId = "c0"))
    }

    @Test
    fun anEndedCallStaysEndedAndANewOneRings() {
        assertEquals(CallOfferAction.IGNORE_ENDED, action(incoming(), ended = true))
        assertEquals(CallOfferAction.RING, action(null))
    }

    /** Mutation: `held` never with the offerer — both sides of glare would refuse each other as busy. */
    @Test
    fun glareKeepsOneCallOfTheTwo() {
        assertEquals(CallOfferAction.KEEP_OURS, action(outgoing(to = "a"), offerer = "a", me = "b"))
        assertEquals(CallOfferAction.REPLACE_OUTGOING, action(outgoing(to = "b"), offerer = "b", me = "a"))
    }

    /** Mutation: `held` always with the offerer — a second caller would replace the call in progress. */
    @Test
    fun aSecondCallerIsBusy() {
        assertEquals(CallOfferAction.BUSY, action(outgoing(to = "c"), offerer = "a", me = "b"))
        assertEquals(CallOfferAction.BUSY, action(incoming("c0")))
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
