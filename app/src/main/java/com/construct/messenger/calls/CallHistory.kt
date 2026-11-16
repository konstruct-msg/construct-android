package com.construct.messenger.calls

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
