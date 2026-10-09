package com.construct.messenger.data.local

import com.construct.messenger.data.model.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalChat
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [ChatStore] on the core's encrypted `LocalStore` (TODO 136, step 3) — held to the same
 * [ChatStoreContract] as [RoomChatStore]. Not wired into the app yet: Room stays the store until
 * the import moves its rows here.
 *
 * Every write is one of the core's field writes; the preview's forward-only rule is the core's.
 *
 * [observeActivity] counts each chat's visible messages by walking them — the core has no per-chat
 * count yet. Correct, and slow on a long history: a core operation is to replace it before this
 * store is switched on.
 */
class CoreChatStore(private val feed: LocalStoreFeed) : ChatStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    override fun observeAll(): Flow<List<ChatRecord>> = feed.watch(LocalStoreTable.CHATS) { s -> s.chats().map { it.record() } }

    override fun observeActivity(): Flow<List<ChatActivity>> =
        combine(feed.watch(LocalStoreTable.CHATS) { it.chats() }, feed.watch(LocalStoreTable.MESSAGES) { }) { chats, _ ->
            chats.map { c -> ChatActivity(c.peerId, visibleCount(c.id), c.lastMessageTime, c.unreadCount) }
        }

    private fun visibleCount(chatId: String): Int {
        var count = 0
        var page = store.messagesBefore(chatId, null, null, PAGE)
        while (page.isNotEmpty()) {
            count += page.count { it.contentType.toInt() == 0 }
            if (page.size.toUInt() < PAGE) break
            page = store.messagesBefore(chatId, page.first().orderKey, page.first().id, PAGE)
        }
        return count
    }

    override suspend fun get(id: String): ChatRecord? = io { it.chat(id)?.record() }

    override suspend fun insert(chat: ChatRecord): Boolean = io { s ->
        s.insertChat(
            LocalChat(
                id = chat.id,
                peerId = chat.peerId,
                lastMessageText = chat.lastMessageText,
                lastMessageTime = chat.lastMessageTimeMs,
                isPinned = chat.isPinned,
                unreadCount = chat.unreadCount,
            ),
        ) == LocalInsert.INSERTED
    }

    override suspend fun advancePreview(id: String, text: String, timeMs: Long) {
        io { it.advanceChatPreview(id, text, timeMs) }
    }

    override suspend fun setPreview(id: String, text: String?, timeMs: Long?) {
        io { it.setChatPreview(id, text, timeMs) }
    }

    override suspend fun incrementUnread(id: String) {
        io { it.incrementUnread(id) }
    }

    override suspend fun setUnread(id: String, count: Int) {
        io { it.setUnread(id, count) }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        io { it.setChatPinned(id, pinned) }
    }

    override suspend fun delete(id: String) = io { it.deleteChat(id) }

    private companion object {
        val PAGE = 500u
    }
}

private fun LocalChat.record() = ChatRecord(
    id = id,
    peerId = peerId,
    lastMessageText = lastMessageText,
    lastMessageTimeMs = lastMessageTime,
    unreadCount = unreadCount,
    isPinned = isPinned,
)
