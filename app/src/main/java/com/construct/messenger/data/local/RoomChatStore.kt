package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.model.ChatActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ChatStore] on Room's `chats` table — until the core's `LocalStore` takes it (TODO 136).
 * Built by `DatabaseModule`, which provides no `ChatDao`: nothing else can reach the table.
 */
class RoomChatStore(private val dao: ChatDao) : ChatStore {
    override fun observeAll(): Flow<List<ChatRecord>> = dao.observeAll().map { rows -> rows.map { it.record() } }

    override fun observeActivity(): Flow<List<ChatActivity>> = dao.observeActivity()

    override suspend fun get(id: String): ChatRecord? = dao.getById(id)?.record()

    override suspend fun insert(chat: ChatRecord): Boolean = dao.insertIfAbsent(chat.entity()) != -1L

    override suspend fun advancePreview(id: String, text: String, timeMs: Long) = dao.advancePreview(id, text, timeMs)

    override suspend fun setPreview(id: String, text: String?, timeMs: Long?) = dao.setPreview(id, text, timeMs)

    override suspend fun incrementUnread(id: String) = dao.incrementUnreadCount(id)

    override suspend fun setUnread(id: String, count: Int) = dao.updateUnreadCount(id, count)

    override suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    override suspend fun delete(id: String) = dao.delete(id)
}

private fun ChatEntity.record() = ChatRecord(
    id = id,
    peerId = otherUserId,
    lastMessageText = lastMessageText,
    lastMessageTimeMs = lastMessageTime,
    unreadCount = unreadCount,
    isPinned = isPinned,
)

private fun ChatRecord.entity() = ChatEntity(
    id = id,
    otherUserId = peerId,
    lastMessageText = lastMessageText,
    lastMessageTime = lastMessageTimeMs,
    unreadCount = unreadCount,
    isPinned = isPinned,
)
