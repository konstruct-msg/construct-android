package com.construct.messenger.calls

import com.google.protobuf.ByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.SignalingServiceOuterClass.InitiateCallResponse
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalRequest
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalResponse
import shared.proto.signaling.v1.Webrtc.CallAnswer
import shared.proto.signaling.v1.Webrtc.CallHangup
import shared.proto.signaling.v1.Webrtc.CallOffer
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.Webrtc.IceCandidate
import shared.proto.signaling.v1.Webrtc.TurnCredentials
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/**
 * The call machine against fakes for the core, the server and WebRTC. Each test is one of the
 * orderings iOS lost a call to, or a rule Android had to decide on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {

    private val me = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val third = "dddddddd-dddd-dddd-dddd-dddddddddddd"

    private class FakeSignals : CallSignalPort {
        val sent = mutableListOf<Pair<String, WebRTCSignal>>()
        override suspend fun send(peerAccountId: String, signal: WebRTCSignal): Boolean {
            sent += peerAccountId to signal
            return true
        }
        override suspend fun sealCandidate(peerAccountId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String): IceCandidate =
            IceCandidate.newBuilder().setCandidate(ByteString.copyFromUtf8("sealed:$candidate")).setSdpMid(sdpMid).setSdpMLineIndex(sdpMLineIndex).build()
        override suspend fun openCandidate(deviceId: String, ice: IceCandidate): String? =
            ice.candidate.toStringUtf8().takeIf { it.startsWith("sealed:") }?.removePrefix("sealed:")
        override suspend fun peerDevice(peerAccountId: String): String = "dev-$peerAccountId"
    }

    private class FakeSignaling : CallSignalingPort {
        val initiated = mutableListOf<String>()
        val requests = mutableListOf<SignalRequest>()
        var streams = 0
        var responses: Channel<SignalResponse>? = null
        override suspend fun initiateCall(callId: String, calleeUserId: String, callerName: String): InitiateCallResponse {
            initiated += callId
            return InitiateCallResponse.getDefaultInstance()
        }
        override suspend fun turnCredentials(callId: String?): TurnCredentials = TurnCredentials.newBuilder().addUrls("turn:example:3478").build()
        override fun stream(outbound: Channel<SignalRequest>): Flow<SignalResponse> = channelFlow {
            streams += 1
            val inbound = Channel<SignalResponse>(Channel.UNLIMITED).also { responses = it }
            // As grpc-kotlin does: the call ends when the server ends it, whatever is still being written.
            val writer = launch { for (r in outbound) requests += r }
            for (r in inbound) send(r)
            writer.cancel()
        }

        /** The server ends the stream. */
        fun close() = responses!!.close()

        fun streamSignals() = requests.filter { it.hasRoutedSignal() }.map { it.routedSignal.signal.signalCase }
    }

    private class FakeMedia(val role: CallMedia.Role, val listener: CallMedia.Listener) : CallMedia {
        val log = mutableListOf<String>()
        override suspend fun createOffer() = "local-offer".also { log += "createOffer" }
        override suspend fun setRemoteOffer(sdp: String) { log += "setRemoteOffer:$sdp" }
        override suspend fun createAnswer() = "local-answer".also { log += "createAnswer" }
        override suspend fun setRemoteAnswer(sdp: String) { log += "setRemoteAnswer:$sdp" }
        override suspend fun addRemoteCandidate(candidate: CallIce) { log += "add:${candidate.sdp}" }
        override suspend fun restartIce() = "restart-offer".also { log += "restartIce" }
        override fun setMuted(muted: Boolean) { log += "muted:$muted" }
        override fun close() { log += "close" }
    }

    private class FakePeers(private val me: String, private val contacts: Set<String>) : CallPeers {
        override fun myUserId() = me
        override fun myDeviceId() = "my-device"
        override suspend fun isCallable(userId: String) = userId in contacts
        override suspend fun name(userId: String) = "name-${userId.take(4)}"
    }

    private inner class Rig(val test: TestScope, myId: String = me) {
        val signals = FakeSignals()
        val signaling = FakeSignaling()
        val inbox = CallSignalInbox()
        val media = mutableListOf<FakeMedia>()
        val history = mutableListOf<Triple<String, CallRecordStatus, Int>>()
        val calls = CallManager(
            signals, signaling, FakePeers(myId, setOf(peer, third)),
            CallMedia.Factory { role, _, listener -> FakeMedia(role, listener).also { media += it } },
            inbox, test.backgroundScope, { test.testScheduler.currentTime },
            { session, status, _, _, seconds -> history += Triple(session.id, status, seconds) },
        )

        init {
            calls.start()
            test.runCurrent()
        }

        fun deliver(signal: WebRTCSignal, from: String = peer) {
            inbox.deliver(CallSignalInbox.Incoming(from, "dev-$from", signal))
            test.runCurrent()
        }

        fun sentCases(to: String = peer) = signals.sent.filter { it.first == to }.map { it.second.signalCase }
        val state get() = calls.state.value
    }

    private fun offer(callId: String, sdp: String = "remote-offer") =
        WebRTCSignal.newBuilder().setCallId(callId).setOffer(CallOffer.newBuilder().setSdp(sdp)).build()

    private fun answerSignal(callId: String) =
        WebRTCSignal.newBuilder().setCallId(callId).setAnswer(CallAnswer.newBuilder().setSdp("remote-answer")).build()

    private fun hangup(callId: String) =
        WebRTCSignal.newBuilder().setCallId(callId).setHangup(CallHangup.newBuilder().setReason(HangupReason.HANGUP_REASON_NORMAL)).build()

    private fun candidate(callId: String, line: String) =
        WebRTCSignal.newBuilder().setCallId(callId)
            .setIceCandidate(IceCandidate.newBuilder().setCandidate(ByteString.copyFromUtf8("sealed:$line")).setSdpMid("0")).build()

    private fun Rig.outgoingCallId(): String = (state as? CallState.Dialing ?: state as CallState.Active).let {
        (it as? CallState.Dialing)?.session?.id ?: (it as CallState.Active).session.id
    }

    @Test
    fun `an ended call goes to the history once — declined, or answered with its length`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.calls.end()
        runCurrent()
        assertEquals(listOf(Triple("c1", CallRecordStatus.DECLINED, 0)), rig.history)

        advanceTimeBy(CallTiming.ENDED_AUTO_CLEAR_MS + 1)
        rig.deliver(offer("c2"))
        rig.calls.answer()
        runCurrent()
        advanceTimeBy(65_000)
        rig.deliver(hangup("c2"))
        assertEquals(Triple("c2", CallRecordStatus.COMPLETED, 65), rig.history.last())
        assertEquals(2, rig.history.size)
    }

    @Test
    fun `an offer rings and negotiates nothing until the user answers`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        assertTrue(rig.state is CallState.Incoming)
        assertTrue("no media before consent", rig.media.isEmpty())
        assertTrue("nothing sent before consent", rig.signals.sent.isEmpty())
    }

    @Test
    fun `answering applies the held offer, then the candidates that waited, then answers`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.deliver(candidate("c1", "cand-1"))
        rig.calls.answer()
        runCurrent()
        val media = rig.media.single()
        assertEquals(CallMedia.Role.CALLEE, media.role)
        assertEquals(listOf("setRemoteOffer:remote-offer", "add:cand-1", "createAnswer"), media.log)
        assertEquals(listOf(WebRTCSignal.SignalCase.ANSWER), rig.sentCases())
        assertTrue(rig.state is CallState.Active)
        // Presence on the stream, or the server reaps the call as callee-offline.
        assertEquals(listOf(WebRTCSignal.SignalCase.RINGING), rig.signaling.streamSignals())
    }

    @Test
    fun `an empty offer never rings, and one for a ringing call ends it`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1", sdp = ""))
        assertEquals(CallState.Idle, rig.state)

        rig.deliver(offer("c2"))
        rig.deliver(offer("c2", sdp = ""))
        assertEquals(CallEndReason.Local("Offer handling failed"), (rig.state as CallState.Ended).reason)
    }

    @Test
    fun `a re-offer before the answer replaces the held one`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1", sdp = "first"))
        rig.deliver(offer("c1", sdp = "second"))
        rig.calls.answer()
        runCurrent()
        assertEquals("setRemoteOffer:second", rig.media.single().log.first())
    }

    @Test
    fun `an offer re-delivered after its call ended does not ring again`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.deliver(hangup("c1"))
        assertTrue(rig.state is CallState.Ended)
        rig.deliver(offer("c1"))
        assertTrue("still ended, not ringing", rig.state is CallState.Ended)
        advanceTimeBy(CallTiming.ENDED_AUTO_CLEAR_MS + 1)
        assertEquals(CallState.Idle, rig.state)
        rig.deliver(offer("c1"))
        assertEquals(CallState.Idle, rig.state)
    }

    @Test
    fun `a remote hangup is not encrypted back, it goes to the server`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.calls.answer()
        runCurrent()
        rig.deliver(hangup("c1"))
        assertEquals(listOf(WebRTCSignal.SignalCase.ANSWER), rig.sentCases())
        assertEquals(listOf(WebRTCSignal.SignalCase.RINGING, WebRTCSignal.SignalCase.HANGUP), rig.signaling.streamSignals())
        assertEquals(CallEndReason.Hangup(HangupReason.HANGUP_REASON_NORMAL), (rig.state as CallState.Ended).reason)
    }

    @Test
    fun `declining tells the peer and the server`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.calls.end()
        runCurrent()
        val sent = rig.signals.sent.single().second
        assertEquals(HangupReason.HANGUP_REASON_DECLINED, sent.hangup.reason)
        assertEquals(listOf(WebRTCSignal.SignalCase.HANGUP), rig.signaling.streamSignals())
        assertTrue(rig.media.isEmpty())
    }

    @Test
    fun `an outgoing call registers, opens the stream and sends one offer`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        val id = rig.outgoingCallId()
        assertEquals(listOf(id), rig.signaling.initiated)
        assertEquals(1, rig.signaling.streams)
        assertEquals(CallMedia.Role.CALLER, rig.media.single().role)
        assertEquals(listOf(WebRTCSignal.SignalCase.OFFER), rig.sentCases())
        assertEquals("local-offer", rig.signals.sent.single().second.offer.sdp)

        rig.deliver(answerSignal(id))
        assertTrue(rig.state is CallState.Active)
        assertEquals("setRemoteAnswer:remote-answer", rig.media.single().log.last())
    }

    @Test
    fun `calling a non-contact sends nothing`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall("eeeeeeee-0000-0000-0000-000000000000")
        runCurrent()
        assertEquals(CallError.NOT_A_CONTACT, rig.calls.lastError.value)
        assertTrue(rig.signaling.initiated.isEmpty())
        assertEquals(CallState.Idle, rig.state)
    }

    @Test
    fun `glare - the smaller id yields to the peer's call`() = runTest {
        val rig = Rig(this, myId = me) // me < peer
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        rig.deliver(offer("theirs"))
        assertEquals("theirs", (rig.state as CallState.Incoming).session.id)
    }

    @Test
    fun `glare - the larger id keeps its call`() = runTest {
        val rig = Rig(this, myId = "cccccccc-cccc-cccc-cccc-cccccccccccc") // > peer
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        val ours = rig.outgoingCallId()
        rig.deliver(offer("theirs"))
        assertEquals(ours, (rig.state as CallState.Dialing).session.id)
    }

    @Test
    fun `a second caller is told busy and the call in progress stays`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.calls.answer()
        runCurrent()
        rig.deliver(offer("c2"), from = third)
        assertEquals("c1", (rig.state as CallState.Active).session.id)
        val refusal = rig.signals.sent.single { it.first == third }.second
        assertEquals("c2", refusal.callId)
        assertEquals(HangupReason.HANGUP_REASON_BUSY, refusal.hangup.reason)
        // And c2's later offers are an ended call's, not a new ring.
        rig.deliver(offer("c2"), from = third)
        assertEquals(1, rig.signals.sent.count { it.first == third })
    }

    @Test
    fun `an offer on the signalling stream is refused`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        val id = rig.outgoingCallId()
        rig.signaling.responses!!.send(SignalResponse.newBuilder().setSignal(answerSignal(id)).build())
        runCurrent()
        assertTrue("an answer from the server is never applied", rig.state is CallState.Dialing)
        assertTrue(rig.media.single().log.none { it.startsWith("setRemoteAnswer") })
    }

    @Test
    fun `before media, the stream closing a fourth time ends the call`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        repeat(CallTiming.MAX_STREAM_RETRIES) {
            rig.signaling.close()
            runCurrent()
            assertTrue(rig.state is CallState.Dialing)
        }
        rig.signaling.close()
        runCurrent()
        assertEquals(CallEndReason.Local("Signal stream closed"), (rig.state as CallState.Ended).reason)
        assertEquals(1 + CallTiming.MAX_STREAM_RETRIES, rig.signaling.streams)
    }

    @Test
    fun `after media, the call outlives its stream`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        rig.deliver(answerSignal(rig.outgoingCallId()))
        rig.media.single().listener.onConnected()
        runCurrent()
        repeat(CallTiming.MAX_POST_MEDIA_RECONNECTS + 2) {
            rig.signaling.close()
            runCurrent()
        }
        assertTrue(rig.state is CallState.Active)
        assertEquals(1 + CallTiming.MAX_POST_MEDIA_RECONNECTS, rig.signaling.streams)
    }

    @Test
    fun `an unanswered call ends after the ring timeout and says so`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        advanceTimeBy(CallTiming.RING_TIMEOUT_MS - 1)
        assertTrue(rig.state is CallState.Incoming)
        advanceTimeBy(2)
        assertEquals(CallEndReason.Hangup(HangupReason.HANGUP_REASON_TIMEOUT), (rig.state as CallState.Ended).reason)
        assertEquals(HangupReason.HANGUP_REASON_TIMEOUT, rig.signals.sent.single().second.hangup.reason)
    }

    @Test
    fun `local candidates are coalesced, sealed and sent as one signal`() = runTest {
        val rig = Rig(this)
        rig.calls.startOutgoingCall(peer)
        runCurrent()
        val listener = rig.media.single().listener
        listOf("a", "b", "c").forEach { listener.onLocalCandidate(CallIce(it, "0", 0)) }
        advanceTimeBy(CallTiming.ICE_FLUSH_MS + 1)
        val ice = rig.signals.sent.last().second
        assertEquals(WebRTCSignal.SignalCase.ICE_CANDIDATES, ice.signalCase)
        assertEquals(listOf("sealed:a", "sealed:b", "sealed:c"), ice.iceCandidates.candidatesList.map { it.candidate.toStringUtf8() })
    }

    @Test
    fun `the caller restarts ICE two seconds after a disconnect, the callee waits`() = runTest {
        val caller = Rig(this)
        caller.calls.startOutgoingCall(peer)
        runCurrent()
        caller.deliver(answerSignal(caller.outgoingCallId()))
        val media = caller.media.single()
        media.listener.onConnected()
        media.listener.onQuality(CallQuality.RECONNECTING)
        advanceTimeBy(CallTiming.ICE_RESTART_GRACE_MS + 1)
        assertTrue("restartIce" in media.log)
        assertEquals("restart-offer", caller.signals.sent.last().second.offer.sdp)

        val callee = Rig(this)
        callee.deliver(offer("c1"))
        callee.calls.answer()
        runCurrent()
        val calleeMedia = callee.media.single()
        calleeMedia.listener.onConnected()
        calleeMedia.listener.onQuality(CallQuality.RECONNECTING)
        advanceTimeBy(CallTiming.ICE_RESTART_GRACE_MS + 1)
        assertTrue("restartIce" !in calleeMedia.log)
    }

    @Test
    fun `a renegotiation answers without re-stamping the call`() = runTest {
        val rig = Rig(this)
        rig.deliver(offer("c1"))
        rig.calls.answer()
        runCurrent()
        rig.deliver(offer("c1", sdp = "restart-offer"))
        assertEquals(listOf(WebRTCSignal.SignalCase.ANSWER, WebRTCSignal.SignalCase.ANSWER), rig.sentCases())
        assertTrue(rig.state is CallState.Active)
        assertNull(rig.calls.lastError.value)
    }
}
