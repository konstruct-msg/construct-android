package com.construct.messenger.data.model

data class Message(
    val id: String,
    val chatId: String,
    val body: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENT,
)

