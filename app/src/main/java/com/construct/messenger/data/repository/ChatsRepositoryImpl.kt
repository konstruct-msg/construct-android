package com.construct.messenger.data.repository

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.resolvedName
import com.construct.messenger.data.model.ChatActivity
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class ChatsRepositoryImpl @Inject constructor(
    private val chatStore: ChatStore,
    contacts: ContactStore,
    private val keystoreManager: KeystoreManager,
) : ChatsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val chats: StateFlow<List<ChatSummary>> = combine(
        chatStore.observeAll(),
        contacts.observeAll(),
    ) { chats, users ->
        val byId = users.associateBy { it.id }
        chats.map { it.toSummary(byId[it.peerId]) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val activity: Flow<List<ChatActivity>> = chatStore.observeActivity()

    override fun suggestedContactId(): String = chats.value.firstOrNull()?.contactId.orEmpty()

    override suspend fun setPinned(contactId: String, pinned: Boolean) {
        chatId(contactId)?.let { chatStore.setPinned(it, pinned) }
    }

    override suspend fun setUnread(contactId: String, unread: Boolean) {
        chatId(contactId)?.let { chatStore.setUnread(it, if (unread) 1 else 0) }
    }

    private fun chatId(contactId: String): String? =
        keystoreManager.getUserId()?.let { ConversationId.direct(it, contactId) }
}

private fun ChatRecord.toSummary(user: ContactRecord?): ChatSummary {
    val name = user.resolvedName(peerId)
    return ChatSummary(
        contactId = peerId,
        displayName = name,
        username = user?.username.orEmpty(),
        lastMessagePreview = lastMessageText,
        lastMessageTime = lastMessageTimeMs,
        unreadCount = unreadCount,
        isPinned = isPinned,
        avatar = user?.avatarData,
        alerted = user != null && com.construct.messenger.data.model.ContactTrustAlert.of(
            com.construct.messenger.data.model.SecurityNotice.of(user.securityNotice),
            com.construct.messenger.data.model.KtStatus.of(user.ktStatus),
        ) != null,
    )
}
