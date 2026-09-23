package com.construct.messenger.util

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Routing prefix for an encrypted sender-sync copy.
 *
 * The relay intentionally delivers a sender-sync envelope as account -> account, so after
 * delivery neither the peer nor the conversation can be recovered from the outer envelope.
 * The partner account therefore travels inside the ciphertext, before the KNST frame.
 *
 * **Canon:** `architecture/WIRE_FORMAT.md` → `SSR1`.
 */
object SenderSyncRouting {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    const val PREFIX_SIZE = 20

    data class Decoded(
        val partnerUserId: String,
        val payload: ByteArray,
    )

    fun encode(partnerUserId: String, payload: ByteArray): ByteArray {
        val uuid = UUID.fromString(partnerUserId)
        require(uuid.mostSignificantBits != 0L || uuid.leastSignificantBits != 0L) {
            "sender-sync partner UUID must not be zero"
        }
        val out = ByteArray(PREFIX_SIZE + payload.size)
        MAGIC.copyInto(out, destinationOffset = 0)
        ByteBuffer.wrap(out, MAGIC.size, 16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
        payload.copyInto(out, destinationOffset = PREFIX_SIZE)
        return out
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < PREFIX_SIZE || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size, 16)
        val uuid = UUID(buffer.long, buffer.long)
        if (uuid.mostSignificantBits == 0L && uuid.leastSignificantBits == 0L) return null
        return Decoded(
            partnerUserId = uuid.toString().lowercase(),
            payload = bytes.copyOfRange(PREFIX_SIZE, bytes.size),
        )
    }
}
