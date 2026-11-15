package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.HangupReason
import shared.proto.signaling.v1.SignalingServiceOuterClass.SignalErrorCode

/** The history row by the core's rule (todo 295), through this app's bridge. */
class CallHistoryRulesTest {
    private fun hangup(r: HangupReason) = CallEndReason.Hangup(r)

    @Test
    fun `a decline is ours on an incoming call and a miss on an outgoing one`() {
        assertEquals(CallRecordStatus.DECLINED, CallHistoryRules.status(true, hangup(HangupReason.HANGUP_REASON_DECLINED), false))
        assertEquals(CallRecordStatus.MISSED, CallHistoryRules.status(false, hangup(HangupReason.HANGUP_REASON_DECLINED), false))
    }

    @Test
    fun `busy is a miss, and otherwise whether anyone answered decides`() {
        assertEquals(CallRecordStatus.MISSED, CallHistoryRules.status(false, hangup(HangupReason.HANGUP_REASON_BUSY), false))
        assertEquals(CallRecordStatus.COMPLETED, CallHistoryRules.status(true, hangup(HangupReason.HANGUP_REASON_NORMAL), true))
        assertEquals(CallRecordStatus.MISSED, CallHistoryRules.status(true, hangup(HangupReason.HANGUP_REASON_TIMEOUT), false))
        assertEquals(CallRecordStatus.FAILED, CallHistoryRules.status(false, CallEndReason.Local("x"), false))
        assertEquals(CallRecordStatus.FAILED, CallHistoryRules.status(false, CallEndReason.Error(SignalErrorCode.SIGNAL_ERROR_CODE_UNSPECIFIED), false))
        assertEquals(CallRecordStatus.COMPLETED, CallHistoryRules.status(false, CallEndReason.Local("x"), true))
    }

    @Test
    fun `duration counts from the answer and is zero without one`() {
        assertEquals(0, CallHistoryRules.durationSeconds(null, 10_000))
        assertEquals(83, CallHistoryRules.durationSeconds(1_000, 84_999))
        assertEquals(0, CallHistoryRules.durationSeconds(5_000, 4_000))
    }

    /** Mutation: record a call taken on another of our devices. */
    @Test
    fun `a call taken on another of our devices leaves no row here`() {
        assertEquals(null, CallHistoryRules.status(true, hangup(HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE), false))
    }
}
