package com.construct.messenger.data.local

import com.construct.messenger.data.model.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalChat
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [ChatStore] on the core's encrypted `LocalStore` (TODO 136, step 3), the only store since
 * Room went (2026-10-19), held to [ChatStoreContract].
 *
 * Every write is one of the core's field writes; the preview's forward-only rule is the core's.
 *
 * [observeActivity] counts a chat's messages with the core's `chat_message_count` (0.39.0). It
 * counts every row: a control message is consumed before it is stored.
 *
 * Our own account is not a peer, so a chat with it is in no list ([CoreContactStore]).
 */
class CoreChatStore(private val feed: LocalStoreFeed, private val myUserId: () -> String?) : ChatStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    private fun LocalStore.listed(): List<LocalChat> = chats().filterNot { it.peerId.equals(myUserId(), ignoreCase = true) }

    override fun observeAll(): Flow<List<ChatRecord>> = feed.watch(LocalStoreTable.CHATS) { s -> s.listed().map { it.record() } }

    override fun observeActivity(): Flow<List<ChatActivity>> =
        combine(feed.watch(LocalStoreTable.CHATS) { it.listed() }, feed.changes(LocalStoreTable.MESSAGES)) { chats, _ ->
            chats.map { c -> ChatActivity(c.peerId, store.chatMessageCount(c.id).toInt(), c.lastMessageTime, c.unreadCount) }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

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
}

private fun LocalChat.record() = ChatRecord(
    id = id,
    peerId = peerId,
    lastMessageText = lastMessageText,
    lastMessageTimeMs = lastMessageTime,
    unreadCount = unreadCount,
    isPinned = isPinned,
)
