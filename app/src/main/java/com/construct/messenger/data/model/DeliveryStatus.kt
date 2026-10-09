package com.construct.messenger.data.model

/**
 * Delivery state of an E2EE message.
 *
 * **Canon:** iOS `Message.deliveryStatus` and the core's store (0 sending, 1 sent, 2 delivered,
 * 4 failed). There is no "read": the owner decided on 2026-10-09 that it is not needed at all —
 * no read receipts, so nothing to store.
 */
enum class DeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
}
