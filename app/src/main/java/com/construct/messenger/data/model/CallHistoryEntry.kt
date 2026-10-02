package com.construct.messenger.data.model

/** One row of the Calls tab. **Canon:** iOS `CallHistoryRow`. */
data class CallHistoryEntry(
    val id: String,
    val peerUserId: String,
    /** The contact's current name, else the one the call was made under. */
    val peerName: String,
    val peerAvatar: ByteArray?,
    val incoming: Boolean,
    val status: Status,
    val startedAtMs: Long,
    val durationSeconds: Int,
) {
    enum class Status { COMPLETED, MISSED, DECLINED, FAILED }

    // The avatar is display only; two rows are the same row when their ids are.
    override fun equals(other: Any?): Boolean =
        other is CallHistoryEntry && id == other.id && peerName == other.peerName &&
            status == other.status && peerAvatar.contentEquals(other.peerAvatar)

    override fun hashCode(): Int = id.hashCode()
}
