package com.construct.messenger.data.repository

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.DisplayNameGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class ChatsRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    userDao: UserDao,
    private val keystoreManager: KeystoreManager,
) : ChatsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val chats: StateFlow<List<ChatSummary>> = combine(
        chatDao.observeAll(),
        userDao.observeAll(),
    ) { chats, users ->
        val byId = users.associateBy { it.id }
        chats.map { it.toSummary(byId[it.otherUserId]) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun suggestedContactId(): String = chats.value.firstOrNull()?.contactId.orEmpty()

    override suspend fun setPinned(contactId: String, pinned: Boolean) {
        chatId(contactId)?.let { chatDao.setPinned(it, pinned) }
    }

    override suspend fun setUnread(contactId: String, unread: Boolean) {
        chatId(contactId)?.let { chatDao.updateUnreadCount(it, if (unread) 1 else 0) }
    }

    private fun chatId(contactId: String): String? =
        keystoreManager.getUserId()?.let { ConversationId.direct(it, contactId) }
}

private fun ChatEntity.toSummary(user: UserEntity?): ChatSummary {
    val name = user?.displayName?.takeIf { it.isNotBlank() }
        ?: DisplayNameGenerator.generate(otherUserId)
    return ChatSummary(
        contactId = otherUserId,
        displayName = name,
        username = user?.username.orEmpty(),
        lastMessagePreview = lastMessageText,
        lastMessageTime = lastMessageTime,
        unreadCount = unreadCount,
        isPinned = isPinned,
    )
}
