package com.construct.messenger.data.local

import com.construct.messenger.data.model.ChatActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [ChatStore] in a map, for tests; [ChatStoreContract] holds it to Room's behaviour. */
internal class FakeChatStore : ChatStore {
    val rows = linkedMapOf<String, ChatRecord>()

    private fun change(id: String, f: (ChatRecord) -> ChatRecord) {
        rows[id]?.let { rows[id] = f(it) }
    }

    override fun observeAll(): Flow<List<ChatRecord>> = flow {
        emit(rows.values.sortedWith(compareByDescending<ChatRecord> { it.isPinned }.thenByDescending { it.lastMessageTimeMs ?: Long.MIN_VALUE }))
    }

    override fun observeActivity(): Flow<List<ChatActivity>> = flow { emit(emptyList()) }
    override suspend fun get(id: String): ChatRecord? = rows[id]

    override suspend fun insert(chat: ChatRecord): Boolean {
        if (chat.id in rows) return false
        rows[chat.id] = chat
        return true
    }

    override suspend fun advancePreview(id: String, text: String, timeMs: Long) = change(id) {
        val shown = it.lastMessageTimeMs
        if (shown != null && shown > timeMs) it else it.copy(lastMessageText = text, lastMessageTimeMs = timeMs)
    }

    override suspend fun setPreview(id: String, text: String?, timeMs: Long?) =
        change(id) { it.copy(lastMessageText = text, lastMessageTimeMs = timeMs) }

    override suspend fun incrementUnread(id: String) = change(id) { it.copy(unreadCount = it.unreadCount + 1) }
    override suspend fun setUnread(id: String, count: Int) = change(id) { it.copy(unreadCount = count) }
    override suspend fun setPinned(id: String, pinned: Boolean) = change(id) { it.copy(isPinned = pinned) }
    override suspend fun delete(id: String) { rows.remove(id) }
}
