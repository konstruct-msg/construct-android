package com.construct.messenger.data.local

import com.construct.messenger.data.model.ChatActivity
import kotlinx.coroutines.flow.Flow

/**
 * A conversation with one peer: its list preview, unread count and pin.
 *
 * Our own type — not Room's `ChatEntity`, not the core's `LocalChat` (TODO 136, the seam).
 * [peerId] is the peer's **ServerUserId**; one chat per peer.
 */
data class ChatRecord(
    val id: String,
    val peerId: String,
    /** The list's preview, as the client formatted it. */
    val lastMessageText: String? = null,
    val lastMessageTimeMs: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
)

/**
 * The chats this device holds — the only way in to their rows (TODO 136, step 1). Writes change
 * their named fields of one chat, as `LocalStore`'s do in the core: the preview and the unread
 * count are written by every arriving message, from whichever coroutine received it, and a read,
 * a change and a write back would lose one of two. A write to a chat that does not exist changes
 * nothing.
 *
 * A chat's peer must have a contact row first ([ContactStore.ensure]) — the core holds the chat to
 * it.
 */
interface ChatStore {
    /** The chats list: pinned first, then most recent. */
    fun observeAll(): Flow<List<ChatRecord>>

    /** Each chat with its count of user-visible messages — how active each contact is (Synapses). */
    fun observeActivity(): Flow<List<ChatActivity>>

    suspend fun get(id: String): ChatRecord?

    /** Adds [chat] unless the chat is already there. False: it was, and nothing changed. */
    suspend fun insert(chat: ChatRecord): Boolean

    /**
     * Moves the preview to a message unless the one shown is newer — messages arrive out of order,
     * and an older one must not replace a newer preview. An equal time moves it (an edit).
     */
    suspend fun advancePreview(id: String, text: String, timeMs: Long)

    /** Sets the preview whatever it was — after a deletion; both null when no message is left. */
    suspend fun setPreview(id: String, text: String?, timeMs: Long?)

    suspend fun incrementUnread(id: String)

    suspend fun setUnread(id: String, count: Int)

    suspend fun setPinned(id: String, pinned: Boolean)

    /** The chat row. Its messages are the messages domain's (the core takes them with it). */
    suspend fun delete(id: String)
}

/**
 * A message for the chat with [peerId] was stored: the chat is created with it as the preview, or
 * the preview moves forward to it; [unread] counts it. The peer's contact row must exist first.
 */
suspend fun ChatStore.noteMessage(id: String, peerId: String, preview: String, timeMs: Long, unread: Boolean) {
    if (insert(ChatRecord(id, peerId, preview, timeMs, unreadCount = if (unread) 1 else 0))) return
    advancePreview(id, preview, timeMs)
    if (unread) incrementUnread(id)
}

/**
 * The chat with [peerId], created empty when there is none — before its first message is stored,
 * which the core holds to it. [noteMessage] then sets its preview.
 */
suspend fun ChatStore.ensure(id: String, peerId: String) {
    insert(ChatRecord(id, peerId))
}
