package com.construct.messenger.util

import org.json.JSONObject
import shared.proto.signaling.v1.Presence.DeliveryReceipt

/**
 * E2E delivery receipt payload (KNST type 14). Consumer-first: proto, then legacy JSON.
 *
 * **Canon:** `decisions/delivery-receipt-binary-payload.md`.
 */
object IncomingReceipt {
    fun messageIds(plaintext: ByteArray): List<String> {
        val payload = payloadOf(plaintext) ?: return emptyList()
        if (payload.isEmpty()) return emptyList()
        if (payload[0] == '{'.code.toByte()) return parseJson(payload)
        return runCatching {
            val receipt = DeliveryReceipt.parseFrom(payload)
            if (receipt.hasDirect()) receipt.direct.messageIdsList else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun payloadOf(plaintext: ByteArray): ByteArray? {
        if (!IncomingPlaintext.isKnst(plaintext)) return plaintext
        if (plaintext.size < IncomingPlaintext.HEADER_SIZE + 4) return null
        val declared =
            ((plaintext[26].toInt() and 0xFF) shl 24) or
                ((plaintext[27].toInt() and 0xFF) shl 16) or
                ((plaintext[28].toInt() and 0xFF) shl 8) or
                (plaintext[29].toInt() and 0xFF)
        val end = IncomingPlaintext.HEADER_SIZE + declared
        if (end > plaintext.size) return null
        return plaintext.copyOfRange(IncomingPlaintext.HEADER_SIZE, end)
    }

    private fun parseJson(payload: ByteArray): List<String> = runCatching {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        val ids = obj.optJSONArray("message_ids") ?: return emptyList()
        buildList {
            for (i in 0 until ids.length()) add(ids.getString(i))
        }
    }.getOrDefault(emptyList())
}
