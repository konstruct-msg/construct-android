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
    private val mutableChats = MutableStateFlow<List<ChatSummary>>(
        listOf(
            ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000001",
                displayName = "Silent Fox",
                username = "silent_fox",
                lastMessagePreview = "The keys are rotated.",
                lastMessageTime = System.currentTimeMillis() - 5 * 60 * 1000,
                unreadCount = 2,
            ),
            ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000002",
                displayName = "Swift Wolf",
                lastMessagePreview = "See you in the mesh.",
                lastMessageTime = System.currentTimeMillis() - 47 * 60 * 1000,
                unreadCount = 0,
            ),
            ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000003",
                displayName = "Deprecated Printer",
                username = "deprecated_printer",
                lastMessagePreview = "Out of toner, send help.",
                lastMessageTime = System.currentTimeMillis() - 3 * 60 * 60 * 1000,
                unreadCount = 1,
                isPinned = true,
            ),
            ChatSummary(
                contactId = "14f28d31-aaaa-bbbb-cccc-000000000004",
                displayName = "Recursive Rabbit",
                lastMessagePreview = "-stack overflow-",
                lastMessageTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000,
                unreadCount = 0,
            ),
        )
    )

    override val chats: StateFlow<List<ChatSummary>> = mutableChats.asStateFlow()

    override fun suggestedContactId(): String = "test_contact"

    override suspend fun setPinned(contactId: String, pinned: Boolean) = update(contactId) { it.copy(isPinned = pinned) }

    override suspend fun setUnread(contactId: String, unread: Boolean) =
        update(contactId) { it.copy(unreadCount = if (unread) 1 else 0) }

    private fun update(contactId: String, change: (ChatSummary) -> ChatSummary) {
        mutableChats.value = mutableChats.value
            .map { if (it.contactId == contactId) change(it) else it }
            .sortedWith(compareByDescending<ChatSummary> { it.isPinned }.thenByDescending { it.lastMessageTime })
    }
}
