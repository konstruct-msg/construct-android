package com.construct.messenger.util

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.signaling.v1.Presence.DeliveryReceipt
import shared.proto.signaling.v1.Presence.DirectReceipt
import shared.proto.signaling.v1.Presence.ReceiptStatus

class IncomingReceiptTest {

    @Test
    fun protoInsideKnst() {
        val inner = DeliveryReceipt.newBuilder()
            .setDirect(
                DirectReceipt.newBuilder()
                    .addMessageIds("aaaa")
                    .addMessageIds("bbbb")
                    .setStatus(ReceiptStatus.RECEIPT_STATUS_DELIVERED),
            )
            .build()
            .toByteArray()
        val framed = KnstFrame.pack(inner, ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE, UUID(0, 1))
        assertEquals(listOf("aaaa", "bbbb"), IncomingReceipt.messageIds(framed))
    }

    @Test
    fun legacyJson() {
        val json = """{"type":"delivery_receipt","message_ids":["x"]}""".toByteArray()
        assertEquals(listOf("x"), IncomingReceipt.messageIds(json))
    }
}
