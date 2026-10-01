package com.construct.messenger.data.repository

import com.construct.messenger.calls.CallEndReason
import com.construct.messenger.calls.CallSession
import com.construct.messenger.calls.CallState
import com.construct.messenger.data.model.CallUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode
import shared.proto.signaling.v1.Webrtc.HangupReason

class CallUiMappingTest {
    private val s = CallSession("c1", "peer", "Annie", CallSession.Direction.INCOMING)

    @Test
    fun `no call is no screen`() {
        assertNull(CallUiMapping.ui(CallState.Idle, false, false, false))
    }

    @Test
    fun `each state has its phase, and only an active call counts time`() {
        assertEquals(CallUi.Phase.INCOMING, CallUiMapping.ui(CallState.Incoming(s), false, false, false)!!.phase)
        assertEquals(CallUi.Phase.CALLING, CallUiMapping.ui(CallState.Dialing(s), false, false, false)!!.phase)
        assertEquals(CallUi.Phase.RINGING, CallUiMapping.ui(CallState.Ringing(s), false, false, false)!!.phase)
        assertEquals(CallUi.Phase.CONNECTING, CallUiMapping.ui(CallState.Connecting(s), false, false, false)!!.phase)
        val active = CallUiMapping.ui(CallState.Active(s, 42L), false, true, true)!!
        assertEquals(CallUi.Phase.ACTIVE, active.phase)
        assertEquals(42L, active.activeSinceMs)
        assertEquals(true, active.muted)
        assertEquals(true, active.speaker)
    }

    @Test
    fun `reconnecting is shown only on a call that was up`() {
        assertFalse(CallUiMapping.ui(CallState.Dialing(s), true, false, false)!!.reconnecting)
        assertEquals(true, CallUiMapping.ui(CallState.Active(s, 1), true, false, false)!!.reconnecting)
    }

    @Test
    fun `how a call ended reads as iOS says it`() {
        fun ended(reason: CallEndReason) = CallUiMapping.ui(CallState.Ended(s, reason), false, false, false)!!.ended
        assertEquals(CallUi.EndedAs.ENDED, ended(CallEndReason.Hangup(HangupReason.HANGUP_REASON_NORMAL)))
        assertEquals(CallUi.EndedAs.DECLINED, ended(CallEndReason.Hangup(HangupReason.HANGUP_REASON_DECLINED)))
        assertEquals(CallUi.EndedAs.BUSY, ended(CallEndReason.Hangup(HangupReason.HANGUP_REASON_BUSY)))
        assertEquals(CallUi.EndedAs.MISSED, ended(CallEndReason.Hangup(HangupReason.HANGUP_REASON_TIMEOUT)))
        assertEquals(CallUi.EndedAs.FAILED, ended(CallEndReason.Local("ICE connection failed")))
        assertEquals(CallUi.EndedAs.FAILED, ended(CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_CALL_EXPIRED)))
    }
}
