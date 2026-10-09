package com.construct.messenger.data.repository

import com.construct.messenger.data.model.ChatActivity
import com.construct.messenger.data.model.ChatSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ChatsRepository {
    val chats: StateFlow<List<ChatSummary>>

    /** Each chat's message count, last message and unread — what places a contact in Synapses. */
    val activity: Flow<List<ChatActivity>>

    fun suggestedContactId(): String

    /** Pinned chats sort first. */
    suspend fun setPinned(contactId: String, pinned: Boolean)

    /** iOS "mark unread" sets the count to 1, "mark read" to 0; nothing is sent either way. */
    suspend fun setUnread(contactId: String, unread: Boolean)
}
