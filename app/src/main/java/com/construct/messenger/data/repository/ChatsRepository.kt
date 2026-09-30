package com.construct.messenger.data.repository

import com.construct.messenger.data.model.ChatSummary
import kotlinx.coroutines.flow.StateFlow

interface ChatsRepository {
    val chats: StateFlow<List<ChatSummary>>

    fun suggestedContactId(): String

    /** Pinned chats sort first. */
    suspend fun setPinned(contactId: String, pinned: Boolean)

    /** iOS "mark unread" sets the count to 1, "mark read" to 0; nothing is sent either way. */
    suspend fun setUnread(contactId: String, unread: Boolean)
}
