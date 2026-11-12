package com.construct.messenger.readsync

import shared.proto.core.v1.EnvelopeOuterClass.ReadMarker
import shared.proto.core.v1.EnvelopeOuterClass.ReadMarkers
import uniffi.construct_core.ReadBoundary
import uniffi.construct_core.ReadMarkerOutcome
import uniffi.construct_core.ReadMarkerState
import uniffi.construct_core.readBoundaryCovers
import uniffi.construct_core.readBoundaryIsLater

/**
 * "Read" agrees between this account's own devices, and only them (construct-docs TODO 153,
 * `decisions/read-state-syncs-between-own-devices.md`). The rule, apart from any storage, so the
 * conformance vectors (`knst_read_markers.json`) can hold it. The rule is the core's (construct-rules
 * `read_sync`), one for every client (todo 300); the payload codec stays here.
 *
 * A chat's boundary is the last read incoming message, named by what every device of the reader
 * holds alike: its id from inside the ciphertext and its envelope time. Never a server position:
 * each device's mailbox numbers its copy differently.
 */
object ReadSync {
    /** A point in a chat: [sentAtMs] (ms since the epoch), then [messageId] (lowercased) as bytes. */
    data class Boundary(val sentAtMs: Long, val messageId: String) {
        val core: ReadBoundary get() = ReadBoundary(sentAtMs.toULong(), messageId)

        companion object {
            /** Ids are compared lowercased, as every device stores them. */
            fun of(sentAtMs: Long, messageId: String) = Boundary(sentAtMs, messageId.lowercase())

            fun of(core: ReadBoundary) = Boundary(core.sentAtMs.toLong(), core.messageId)
        }
    }

    enum class Outcome { APPLY, IGNORE, DROP }

    internal val ReadMarkerOutcome.kt: Outcome
        get() = when (this) {
            ReadMarkerOutcome.APPLY -> Outcome.APPLY
            ReadMarkerOutcome.IGNORE -> Outcome.IGNORE
            ReadMarkerOutcome.DROP -> Outcome.DROP
        }

    /** [a] strictly after [b]: a later time, or the same time and a greater id by its UTF-8 bytes. The core's. */
    fun isLater(a: Boundary, b: Boundary): Boolean = readBoundaryIsLater(a.core, b.core)

    /** An incoming [message] counts as read when it is at or before [boundary]. The core's. */
    fun covers(boundary: Boundary, message: Boundary): Boolean = readBoundaryCovers(boundary.core, message.core)

    /**
     * What a received [marker] does to the [held] boundary — the core's answer through a throwaway
     * state. One not from this account is dropped whatever it says.
     */
    fun outcome(marker: Boundary, held: Boundary?, fromOwnAccount: Boolean): Outcome {
        val state = ReadMarkerState()
        if (held != null) state.advance("p", held.core)
        return state.applyMarker("p", marker.core, fromOwnAccount, "").kt
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
}
