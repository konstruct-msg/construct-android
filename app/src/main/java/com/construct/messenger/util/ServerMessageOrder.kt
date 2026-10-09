package com.construct.messenger.util

/**
 * One sortable form of the server's total message order — the message's `order_key` in the core's
 * store. **Canon:** iOS `ServerMessageOrder`, character for character: both clients write one
 * store format (TODO 136), so a key must mean the same whichever app wrote it.
 *
 * The transport order is `(server millisecond, sequence)`, not the sender's clock. Each part is
 * a fixed-width decimal, so the string's ordinary sort is the numeric one:
 * `00000001760000000000-00000000000000000042`.
 */
object ServerMessageOrder {
    private const val WIDTH = 20
    private const val SEPARATOR = '-'
    private val PENDING = "9".repeat(WIDTH)

    /** From a Redis stream cursor (`serverMs-seq`). Null when it does not start with a number. */
    fun key(cursor: String): String? {
        val (ms, seq) = components(cursor) ?: return null
        return key(ms, seq)
    }

    /** From server metadata or a send acknowledgement; null without a server time. */
    fun key(serverTimestampMs: Long, sequence: Long): String? {
        if (serverTimestampMs <= 0) return null
        return key(serverTimestampMs.toULong(), sequence.toULong())
    }

    /**
     * From an envelope's server metadata, the stream cursor standing in for a server that did not
     * fill it. The cursor's sequence is the mailbox's exact tie-breaker; the message number is the
     * fallback a send acknowledgement has.
     */
    fun key(serverTimestampMs: Long, sequence: Long, cursor: String?): String? {
        val parts = cursor?.let(::components)
        val ms = if (serverTimestampMs > 0) serverTimestampMs.toULong() else parts?.first ?: return null
        return key(ms, parts?.second ?: sequence.toULong())
    }

    /**
     * A row of ours not yet acknowledged: after every real key, so the bubble sits at the bottom
     * until the acknowledgement moves it to its place.
     */
    fun pending(localMessageId: String): String = "$PENDING$SEPARATOR$PENDING$SEPARATOR${localMessageId.lowercase()}"

    /**
     * A row with no server position and none to come — imported, written locally, or from before
     * the key existed: at its own time. The id makes the key total; two rows of one millisecond
     * would otherwise compare equal and order by chance.
     */
    fun local(timestampMs: Long, messageId: String): String =
        "${padded(maxOf(timestampMs, 1).toULong())}$SEPARATOR${padded(0u)}$SEPARATOR${messageId.lowercase()}"

    private fun key(ms: ULong, sequence: ULong) = "${padded(ms)}$SEPARATOR${padded(sequence)}"

    private fun components(cursor: String): Pair<ULong, ULong>? {
        val parts = cursor.split(SEPARATOR, limit = 2).filter { it.isNotEmpty() }
        val ms = parts.firstOrNull()?.toULongOrNull() ?: return null
        return ms to (parts.getOrNull(1)?.toULongOrNull() ?: 0u)
    }

    private fun padded(value: ULong): String = value.toString().padStart(WIDTH, '0')
}
