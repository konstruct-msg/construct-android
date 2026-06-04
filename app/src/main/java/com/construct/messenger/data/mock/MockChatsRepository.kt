package com.construct.messenger.data.mock

import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockChatsRepository @Inject constructor() : ChatsRepository {
    private val mutableChats = MutableStateFlow<List<ChatSummary>>(emptyList())

    override val chats: StateFlow<List<ChatSummary>> = mutableChats.asStateFlow()

    override fun suggestedContactId(): String = "test_contact"
}
