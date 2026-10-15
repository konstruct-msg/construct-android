package com.construct.messenger.readsync

import shared.proto.core.v1.EnvelopeOuterClass.ReadMarker
import shared.proto.core.v1.EnvelopeOuterClass.ReadMarkers

/**
 * "Read" agrees between this account's own devices, and only them (construct-docs TODO 153,
 * `decisions/read-state-syncs-between-own-devices.md`). The rule, apart from any storage, so the
 * conformance vectors (`knst_read_markers.json`) can hold it. **Canon:** iOS `ReadSync`.
 *
 * A chat's boundary is the last read incoming message, named by what every device of the reader
 * holds alike: its id from inside the ciphertext and its envelope time. Never a server position:
 * each device's mailbox numbers its copy differently.
 */
object ReadSync {
    /** A point in a chat: [sentAtMs] (ms since the epoch), then [messageId] (lowercased) as bytes. */
    data class Boundary(val sentAtMs: Long, val messageId: String) {
        companion object {
            /** Ids are compared lowercased, as every device stores them. */
            fun of(sentAtMs: Long, messageId: String) = Boundary(sentAtMs, messageId.lowercase())
        }
    }

    enum class Outcome { APPLY, IGNORE, DROP }

    /** [a] strictly after [b]: a later time, or the same time and a greater id by its UTF-8 bytes. */
    fun isLater(a: Boundary, b: Boundary): Boolean {
        if (a.sentAtMs != b.sentAtMs) return java.lang.Long.compareUnsigned(a.sentAtMs, b.sentAtMs) > 0
        return compareBytes(a.messageId.toByteArray(), b.messageId.toByteArray()) > 0
    }

    /** An incoming [message] counts as read when it is at or before [boundary]. */
    fun covers(boundary: Boundary, message: Boundary): Boolean = !isLater(message, boundary)

    /**
     * What a received [marker] does to the [held] boundary. One not from this account is dropped
     * whatever it says — otherwise a contact could zero my unread count.
     */
    fun outcome(marker: Boundary, held: Boundary?, fromOwnAccount: Boolean): Outcome = when {
        !fromOwnAccount -> Outcome.DROP
        held == null || isLater(marker, held) -> Outcome.APPLY
        else -> Outcome.IGNORE
    }

    /** [markers] (partner ServerUserId → boundary) as a `ReadMarkers` payload, partners in order. */
    fun encode(markers: Map<String, Boundary>): ByteArray =
        ReadMarkers.newBuilder().apply {
            markers.toSortedMap().forEach { (partner, b) ->
                addMarkers(
                    ReadMarker.newBuilder()
                        .setPartnerUserId(partner.lowercase())
                        .setLastReadMessageId(b.messageId)
                        .setLastReadSentAtMs(b.sentAtMs),
                )
            }
        }.build().toByteArray()

    /** The markers in [payload], in its order; null when it does not parse. Empty names are skipped. */
    fun decode(payload: ByteArray): List<Pair<String, Boundary>>? {
        val markers = runCatching { ReadMarkers.parseFrom(payload) }.getOrNull() ?: return null
        return markers.markersList
            .filter { it.partnerUserId.isNotEmpty() && it.lastReadMessageId.isNotEmpty() }
            .map { it.partnerUserId.lowercase() to Boundary.of(it.lastReadSentAtMs, it.lastReadMessageId) }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
