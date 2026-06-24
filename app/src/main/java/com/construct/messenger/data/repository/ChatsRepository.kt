package com.construct.messenger.data.repository

import com.construct.messenger.data.model.ChatSummary
import kotlinx.coroutines.flow.StateFlow

interface ChatsRepository {
    val chats: StateFlow<List<ChatSummary>>

    fun suggestedContactId(): String
}
