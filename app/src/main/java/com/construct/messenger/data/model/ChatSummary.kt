package com.construct.messenger.data.model

/**
 * Repository-facing summary of a conversation for the chats list.
 */
data class ChatSummary(
    val contactId: String,
    val displayName: String,
    val username: String = "",
    val lastMessagePreview: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
)
