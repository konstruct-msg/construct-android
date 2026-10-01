package com.construct.messenger.calls

import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.KnstFrame
import com.google.protobuf.ByteString
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.CallAnswer
import shared.proto.signaling.v1.Webrtc.CallOffer
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.TurnCredentials
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

class CallSignalWireTest {

    private fun candidate(bytes: Int) = IceCandidate.newBuilder()
        .setCandidate(ByteString.copyFrom(ByteArray(bytes)))
        .setSdpMid("0")
        .build()

    /** Type 12 inside the frame, the signal whole as its payload — what iOS reads back. */
    @Test fun `a signal travels as one type-12 frame`() {
        val signal = CallSignalWire.hangup("call-1", "dev", HangupReason.HANGUP_REASON_NORMAL, 5)
        val id = UUID.randomUUID()
        val frame = CallSignalWire.frame(signal, id)
        assertEquals(12, frame[5].toInt())
        assertEquals(KnstFrame.TYPE_CALL_SIGNAL, frame[5].toInt())
        assertArrayEquals(signal.toByteArray(), IncomingPlaintext.knstPayload(frame))
        assertFalse("a call signal is never a bubble", IncomingPlaintext.decode(frame).isUserVisible)
    }

    /**
     * Mutation: drop the size check — the 24 candidates go in one signal of ~74 KB, over the
     * core's 65 536-byte cap, and the whole flush is lost (iOS device log, 134 KB batch).
     */
    @Test fun `a burst of candidates is split under the cap, in order, none lost`() {
        val burst = (0 until 24).map { candidate(3_000 + it) }
        val batches = CallSignalWire.batches(burst)
        assertTrue(batches.size > 1)
        batches.forEach { b -> assertTrue(b.sumOf { it.candidate.size() + 17 } <= CallSignalWire.MAX_SIGNAL_BYTES) }
        assertEquals(burst, batches.flatten())
    }

    @Test fun `one candidate is ice_candidate, more are ice_candidates`() {
        val one = CallSignalWire.iceSignal("c", "d", listOf(candidate(10)), 1)
        assertEquals(WebRTCSignal.SignalCase.ICE_CANDIDATE, one.signalCase)
        val two = CallSignalWire.iceSignal("c", "d", listOf(candidate(10), candidate(11)), 1)
        assertEquals(WebRTCSignal.SignalCase.ICE_CANDIDATES, two.signalCase)
        assertEquals(2, two.iceCandidates.candidatesCount)
        assertEquals("c", two.callId)
        assertEquals("d", two.senderDeviceId)
    }

    /** SDP only ever comes out of the Double Ratchet; a server must not pick the call's keys. */
    @Test fun `the stream admits no offer and no answer`() {
        val offer = WebRTCSignal.newBuilder().setCallId("c").setOffer(CallOffer.newBuilder().setSdp("v=0")).build()
        val answer = WebRTCSignal.newBuilder().setCallId("c").setAnswer(CallAnswer.newBuilder().setSdp("v=0")).build()
        assertFalse(CallSignalWire.admittedOnStream(offer))
        assertFalse(CallSignalWire.admittedOnStream(answer))
        assertFalse(CallSignalWire.admittedOnStream(WebRTCSignal.getDefaultInstance()))
        assertTrue(CallSignalWire.admittedOnStream(CallSignalWire.ringing("c", "d", 1)))
        assertTrue(CallSignalWire.admittedOnStream(CallSignalWire.connected("c", "d", 1)))
        assertTrue(CallSignalWire.admittedOnStream(CallSignalWire.hangup("c", "d", HangupReason.HANGUP_REASON_NORMAL, 1)))
    }

    @Test fun `stream requests are a ping or a routed signal with no route`() {
        assertEquals(42, CallSignalWire.ping(42).ping.timestamp)
        val routed = CallSignalWire.routed(CallSignalWire.connected("c", "d", 7)).routedSignal
        assertFalse(routed.hasRoute())
        assertEquals("c", routed.signal.callId)
    }

    @Test fun `a candidate frame is version 4 and nothing else is read`() {
        val wire = byteArrayOf(1, 2, 3)
        val frame = CallSignalFrame.encode(wire)
        assertEquals(0x04, frame[0].toInt())
        assertArrayEquals(wire, CallSignalFrame.decode(frame))
        assertNull(CallSignalFrame.decode(byteArrayOf(0x03, 1, 2)))
        assertNull(CallSignalFrame.decode(byteArrayOf(0x04)))
        assertNull(CallSignalFrame.decode(ByteArray(0)))
    }

    /** One set serves every call until a minute before it expires; then a new one is fetched. */
    @Test fun `TURN credentials are reused until close to expiry`() = runTest {
        var now = 1_000_000L
        val cache = SignalingClient.TurnCache(skewMs = 60_000, nowMs = { now })
        var fetches = 0
        fun creds() = TurnCredentials.newBuilder().addUrls("turn:x").setExpiresAt(now + 3_600_000).build().also { fetches++ }
        val first = cache.get { creds() }
        assertSame(first, cache.get { creds() })
        now += 3_600_000 - 59_000
        cache.get { creds() }
        assertEquals(2, fetches)
    }

    @Test fun `only a contact who is not blocked may ring`() {
        assertTrue(CallSignalInbox.admits(isContact = true, isBlocked = false))
        assertFalse(CallSignalInbox.admits(isContact = true, isBlocked = true))
        assertFalse(CallSignalInbox.admits(isContact = false, isBlocked = false))
    }
}
