package com.construct.messenger.calls

import com.construct.messenger.calls.CallRules.Glare
import com.construct.messenger.calls.CallRules.HangupChannel
import com.construct.messenger.calls.CallRules.HangupOrigin
import com.construct.messenger.calls.CallRules.IceRestartDisposition
import com.construct.messenger.calls.CallRules.NewCallDisposition
import com.construct.messenger.calls.CallRules.OfferDisposition
import com.construct.messenger.calls.CallRules.OutgoingRequest
import com.construct.messenger.calls.CallRules.RemoteOfferDisposition
import com.construct.messenger.calls.CallRules.StreamClosedDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.HangupReason

/** iOS `CallOfferOrderingTests` and `CallSetupHolesTests`, for the rules Android has a carrier for. */
class CallRulesTest {

    @Test
    fun `an offer after the answer resumes it, before the answer is stored`() {
        assertEquals(OfferDisposition.RESUME_ANSWER, CallRules.offerDisposition(false, true, true))
        assertEquals(OfferDisposition.STORE_FOR_ANSWER, CallRules.offerDisposition(false, true, false))
    }

    @Test
    fun `an ended call outranks everything, an unknown id rings`() {
        assertEquals(OfferDisposition.IGNORE_ENDED, CallRules.offerDisposition(true, true, true))
        assertEquals(OfferDisposition.IGNORE_ENDED, CallRules.offerDisposition(true, false, false))
        assertEquals(OfferDisposition.NEW_CALL, CallRules.offerDisposition(false, false, false))
        // Awaiting with nothing matched is not something to resume.
        assertEquals(OfferDisposition.NEW_CALL, CallRules.offerDisposition(false, false, true))
    }

    @Test
    fun `only consent separates holding from renegotiating`() {
        assertEquals(RemoteOfferDisposition.HOLD_UNTIL_ANSWERED, CallRules.remoteOfferDisposition(isIncomingCall = true, hasAnswered = false))
        assertEquals(RemoteOfferDisposition.RENEGOTIATE, CallRules.remoteOfferDisposition(isIncomingCall = true, hasAnswered = true))
        assertEquals(RemoteOfferDisposition.RENEGOTIATE, CallRules.remoteOfferDisposition(isIncomingCall = false, hasAnswered = false))
    }

    @Test
    fun `an empty offer is not an offer`() {
        assertFalse(CallRules.offerSdpIsUsable(null))
        assertFalse(CallRules.offerSdpIsUsable(""))
        assertTrue(CallRules.offerSdpIsUsable("v=0"))
    }

    @Test
    fun `a closed stream reconnects while it can, then ends only an unbounded call`() {
        assertEquals(StreamClosedDisposition.RECONNECT, CallRules.streamClosedDisposition(false, false, canReconnect = true))
        assertEquals(StreamClosedDisposition.KEEP_ON_MESSAGE_PATH, CallRules.streamClosedDisposition(true, false, false))
        assertEquals(StreamClosedDisposition.KEEP_ON_MESSAGE_PATH, CallRules.streamClosedDisposition(false, true, false))
        assertEquals(StreamClosedDisposition.END_CALL, CallRules.streamClosedDisposition(false, false, false))
    }

    @Test
    fun `reconnect budgets differ before and after media`() {
        assertTrue(CallRules.canReconnectStream(false, streamRetries = 2, postMediaReconnects = 9))
        assertFalse(CallRules.canReconnectStream(false, streamRetries = 3, postMediaReconnects = 0))
        assertTrue(CallRules.canReconnectStream(true, streamRetries = 9, postMediaReconnects = 4))
        assertFalse(CallRules.canReconnectStream(true, streamRetries = 0, postMediaReconnects = 5))
    }

    @Test
    fun `a local hangup goes to the peer and the server, a remote one only to the server`() {
        assertEquals(listOf(HangupChannel.E2EE, HangupChannel.SIGNALING_STREAM), CallRules.hangupChannels(HangupOrigin.LOCAL))
        assertEquals(listOf(HangupChannel.SIGNALING_STREAM), CallRules.hangupChannels(HangupOrigin.REMOTE))
    }

    @Test
    fun `glare is decided the same way on both sides`() {
        val a = "aaaaaaaa-0000-0000-0000-000000000000"
        val b = "bbbbbbbb-0000-0000-0000-000000000000"
        assertEquals(Glare.KEEP_OURS, CallRules.glare(b, a))
        assertEquals(Glare.YIELD, CallRules.glare(a, b))
    }

    @Test
    fun `a new call while another is up is busy, unless it is glare`() {
        assertEquals(NewCallDisposition.RING, CallRules.newCallDisposition(false, false, null))
        assertEquals(NewCallDisposition.BUSY, CallRules.newCallDisposition(true, false, null))
        assertEquals(NewCallDisposition.IGNORE_GLARE, CallRules.newCallDisposition(true, true, Glare.KEEP_OURS))
        assertEquals(NewCallDisposition.REPLACE_OUTGOING, CallRules.newCallDisposition(true, true, Glare.YIELD))
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
