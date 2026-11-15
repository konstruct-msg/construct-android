package com.construct.messenger.calls

import uniffi.construct_core.callDurationSeconds
import uniffi.construct_core.callHistoryStatus

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

/** The history row, by the core's rule (`call_history_status`, construct-core 0.73.0, todo 295). */
object CallHistoryRules {
    /**
     * How the call is recorded here, or null when it is not: a call taken on another of our devices
     * is recorded by that device, and here it was neither missed nor declined.
     */
    fun status(incoming: Boolean, reason: CallEndReason, answered: Boolean): CallRecordStatus? =
        callHistoryStatus(CallRuleWire.direction(incoming), CallRuleWire.cause(reason), answered)
            ?.let(CallRuleWire::record)

    /** Whole seconds since it was answered; 0 for a call nobody answered. */
    fun durationSeconds(answeredAtMs: Long?, endedAtMs: Long): Int =
        callDurationSeconds(answeredAtMs, endedAtMs).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
