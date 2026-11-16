package com.construct.messenger.calls

import com.construct.messenger.calls.CallRules.IceRestartDisposition
import com.construct.messenger.calls.CallRules.OutgoingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.HangupReason

/**
 * iOS `CallOfferOrderingTests` and `CallSetupHolesTests`, for the rules Android has a carrier for.
 * The offer, stream-closed and hangup-route rules are the core's: [CallRuleWireTest].
 */
class CallRulesTest {

    @Test
    fun `an empty offer is not an offer`() {
        assertFalse(CallRules.offerSdpIsUsable(null))
        assertFalse(CallRules.offerSdpIsUsable(""))
        assertTrue(CallRules.offerSdpIsUsable("v=0"))
    }

    @Test
    fun `reconnect budgets differ before and after media`() {
        assertTrue(CallRules.canReconnectStream(false, streamRetries = 2, postMediaReconnects = 9))
        assertFalse(CallRules.canReconnectStream(false, streamRetries = 3, postMediaReconnects = 0))
        assertTrue(CallRules.canReconnectStream(true, streamRetries = 9, postMediaReconnects = 4))
        assertFalse(CallRules.canReconnectStream(true, streamRetries = 0, postMediaReconnects = 5))
    }

    @Test
    fun `calling someone who is calling us answers them`() {
        assertEquals(OutgoingRequest.START, CallRules.outgoingRequest(false, false))
        assertEquals(OutgoingRequest.ANSWER_INSTEAD, CallRules.outgoingRequest(true, true))
        assertEquals(OutgoingRequest.BUSY, CallRules.outgoingRequest(true, false))
    }

    @Test
    fun `only the caller restarts ICE, after media, three times`() {
        assertEquals(IceRestartDisposition.SKIP, CallRules.iceRestartDisposition(false, true, false, 0))
        assertEquals(IceRestartDisposition.CALLEE_WAITS, CallRules.iceRestartDisposition(true, false, false, 0))
        assertEquals(IceRestartDisposition.SCHEDULE, CallRules.iceRestartDisposition(true, true, false, 2))
        assertEquals(IceRestartDisposition.SKIP, CallRules.iceRestartDisposition(true, true, false, 3))
        assertEquals(IceRestartDisposition.SKIP, CallRules.iceRestartDisposition(true, true, true, 0))
    }

    @Test
    fun `declining is ending a call that still rings`() {
        assertEquals(HangupReason.HANGUP_REASON_DECLINED, CallRules.localHangupReason(isIncoming = true, stillRinging = true))
        assertEquals(HangupReason.HANGUP_REASON_NORMAL, CallRules.localHangupReason(isIncoming = true, stillRinging = false))
        assertEquals(HangupReason.HANGUP_REASON_NORMAL, CallRules.localHangupReason(isIncoming = false, stillRinging = true))
    }

    @Test
    fun `an ended call is remembered for two minutes`() {
        var now = 1_000L
        val ended = EndedCalls { now }
        ended.remember("c1")
        now += CallTiming.ENDED_CALL_MEMORY_MS - 1
        assertTrue(ended.contains("c1"))
        now += 1
        assertFalse(ended.contains("c1"))
        assertFalse(ended.contains("c2"))
    }
}
