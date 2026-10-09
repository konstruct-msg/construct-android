package com.construct.messenger.data.model

/**
 * One chat as the Synapses cloud sees it: whom it is with, how many messages it holds, when the
 * last one came and how many are unread. Read straight from the database; nothing leaves the device.
 */
data class ChatActivity(
    val contactId: String,
    /** User-visible messages only (`contentType = 0`), as the chat shows them. */
    val messages: Int,
    val lastMessageTime: Long?,
    val unreadCount: Int,
)
