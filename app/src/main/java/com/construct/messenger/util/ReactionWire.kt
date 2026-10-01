package com.construct.messenger.util

import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.ReactionAction
import shared.proto.messaging.v1.Content.ReactionMessage

/**
 * `MessageContent.reaction` inside a KNST frame. **Canon:** iOS `ReactionWire.encode`.
 *
 * The target is the reacted-to row's id — the UUID from that message's KNST header, lower-cased —
 * never an envelope id. `timestamp_ms` is the last-write-wins clock; 0 stays off the wire, which a
 * reader takes as "a peer from before the field".
 */
object ReactionWire {
    fun encode(targetMessageId: String, incoming: ReactionRules.Incoming, timestampMs: Long): ByteArray {
        val reaction = ReactionMessage.newBuilder().setTargetMessageId(targetMessageId.lowercase())
        when (incoming) {
            is ReactionRules.Incoming.Add -> reaction.setEmoji(incoming.emoji).setAction(ReactionAction.REACTION_ACTION_ADD)
            ReactionRules.Incoming.Remove -> reaction.setEmoji("").setAction(ReactionAction.REACTION_ACTION_REMOVE)
        }
        if (timestampMs > 0) reaction.setTimestampMs(timestampMs)
        return MessageContent.newBuilder().setReaction(reaction).build().toByteArray()
    }
}
