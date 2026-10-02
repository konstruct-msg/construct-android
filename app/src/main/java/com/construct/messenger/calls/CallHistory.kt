package com.construct.messenger.calls

import shared.proto.signaling.v1.Webrtc.HangupReason

/** How a call in the history ended. **Canon:** iOS `CTCallRecord.Status`. */
enum class CallRecordStatus {
    /** Answered, then ended. */
    COMPLETED,
    /** Never answered — ours or theirs. */
    MISSED,
    /** An incoming call we declined. */
    DECLINED,
    /** An error or a local cancel before anyone answered. */
    FAILED,
}

/** Where [CallManager] leaves a call once it has ended. Recording must not block the machine. */
fun interface CallHistoryPort {
    fun record(
        session: CallSession,
        status: CallRecordStatus,
        startedAtMs: Long,
        endedAtMs: Long,
        durationSeconds: Int,
    )

    companion object {
        val None = CallHistoryPort { _, _, _, _, _ -> }
    }
}

object CallHistoryRules {
    /**
     * iOS `CallManager.endActiveCall`, rule for rule: a decline is ours on an incoming call and a
     * miss on an outgoing one, busy is a miss, and otherwise whether anyone answered decides.
     */
    fun status(incoming: Boolean, reason: CallEndReason, answered: Boolean): CallRecordStatus = when (reason) {
        is CallEndReason.Hangup -> when (reason.reason) {
            HangupReason.HANGUP_REASON_DECLINED -> if (incoming) CallRecordStatus.DECLINED else CallRecordStatus.MISSED
            HangupReason.HANGUP_REASON_BUSY -> CallRecordStatus.MISSED
            else -> if (answered) CallRecordStatus.COMPLETED else CallRecordStatus.MISSED
        }
        is CallEndReason.Error, is CallEndReason.Local ->
            if (answered) CallRecordStatus.COMPLETED else CallRecordStatus.FAILED
    }

    /** Whole seconds since it was answered; 0 for a call nobody answered. */
    fun durationSeconds(answeredAtMs: Long?, endedAtMs: Long): Int =
        answeredAtMs?.let { ((endedAtMs - it) / 1000).coerceAtLeast(0).toInt() } ?: 0
}
