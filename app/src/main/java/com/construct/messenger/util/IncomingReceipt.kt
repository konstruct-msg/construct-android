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

    /** A bare payload as is; a frame's body as the core reads it. */
    private fun payloadOf(plaintext: ByteArray): ByteArray? {
        if (!IncomingPlaintext.isKnst(plaintext)) return plaintext
        return KnstFrame.parse(plaintext)?.body()
    }

    private fun parseJson(payload: ByteArray): List<String> = runCatching {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        val ids = obj.optJSONArray("message_ids") ?: return emptyList()
        buildList {
            for (i in 0 until ids.length()) add(ids.getString(i))
        }
    }.getOrDefault(emptyList())
}
