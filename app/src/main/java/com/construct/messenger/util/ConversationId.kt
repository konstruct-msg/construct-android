package com.construct.messenger.util

/**
 * Canonical conversation id.
 *
 * **Canon:** iOS `ConversationId.swift`. Proto (`envelope.proto`) uses
 * `direct:{user1}:{user2}` with the two server UUIDs sorted lexicographically
 * so both parties produce the same id regardless of who initiated.
 *
 * Used for Room [com.construct.messenger.data.local.ChatRecord.id] and for
 * [com.construct.messenger.data.api.MessageStreamService] subscriptions.
 * Never written onto an unsealed envelope — see `docs/WIRE_FORMAT_RULES.md`.
 */
object ConversationId {
    fun direct(myUserId: String, theirUserId: String): String {
        val (a, b) = if (myUserId < theirUserId) myUserId to theirUserId else theirUserId to myUserId
        return "direct:$a:$b"
    }

    /** Contact id (the other party) from a `direct:` conversation id, or null if malformed. */
    fun otherUserId(conversationId: String, myUserId: String): String? {
        if (!conversationId.startsWith(DIRECT_PREFIX)) return null
        val rest = conversationId.removePrefix(DIRECT_PREFIX)
        val sep = rest.indexOf(':')
        if (sep <= 0 || sep == rest.lastIndex) return null
        val a = rest.substring(0, sep)
        val b = rest.substring(sep + 1)
        return when (myUserId) {
            a -> b
            b -> a
            else -> null
        }
    }

    private const val DIRECT_PREFIX = "direct:"
}
