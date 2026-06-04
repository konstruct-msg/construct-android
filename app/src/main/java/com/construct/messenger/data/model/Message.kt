package com.construct.messenger.data.model

data class Message(
    val id: String,
    val chatId: String,
    val body: String,
    val isOutgoing: Boolean
)
