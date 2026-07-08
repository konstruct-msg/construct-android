package com.construct.messenger.data.model

/**
 * Delivery state of an E2EE message.
 *
 * **Canon:** iOS `Message.deliveryStatus`.
 */
enum class DeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
