package com.construct.messenger.readsync

import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** What the receive path asks about "read" (TODO 153). [NONE] where nothing is synced. */
interface ReadState {
    /** [message] from [partner] is already read on one of this account's devices. */
    fun isRead(partner: String, message: ReadSync.Boundary): Boolean

    /** An incoming message from [partner] was stored; [onScreen]: its chat is open, so it is read. */
    suspend fun arrived(partner: String, message: ReadSync.Boundary, onScreen: Boolean)

    /** Read markers ([payload]) came in; only [fromOwnAccount] ones may do anything. */
    suspend fun markers(payload: ByteArray, fromOwnAccount: Boolean, myId: String)

    companion object {
        val NONE = object : ReadState {
            override fun isRead(partner: String, message: ReadSync.Boundary) = false
            override suspend fun arrived(partner: String, message: ReadSync.Boundary, onScreen: Boolean) = Unit
            override suspend fun markers(payload: ByteArray, fromOwnAccount: Boolean, myId: String) = Unit
        }
    }
}

/**
 * The receive side of read sync. A marker from another device of this account moves the chat's
 * boundary if it is later and lowers the unread count to what is past it; one from anyone else
 * changes nothing and is logged. **Canon:** iOS `MessageRouter.applyReadMarkers`.
 */
@Singleton
class ReadSyncState @Inject constructor(
    private val boundaries: ReadBoundaries,
    private val outbox: ReadSyncOutbox,
    private val chats: ChatStore,
    private val messages: MessageStore,
) : ReadState {
    override fun isRead(partner: String, message: ReadSync.Boundary): Boolean = boundaries.covers(partner, message)

    override suspend fun arrived(partner: String, message: ReadSync.Boundary, onScreen: Boolean) {
        boundaries.noteIncoming(partner, message)
        if (onScreen) outbox.noteRead(partner, message)
    }

    override suspend fun markers(payload: ByteArray, fromOwnAccount: Boolean, myId: String) {
        val markers = ReadSync.decode(payload) ?: run {
            Log.w(TAG, "read markers do not parse — dropped")
            return
        }
        if (!fromOwnAccount) {
            // A contact must not be able to clear my unread count.
            Log.e(TAG, "SECURITY[read_sync_from_contact]: ${markers.size} read marker(s) not from this account — dropped")
            return
        }
        for ((partner, boundary) in markers) {
            if (partner == myId.lowercase()) continue
            if (ReadSync.outcome(boundary, boundaries.boundary(partner), fromOwnAccount = true) != ReadSync.Outcome.APPLY) continue
            if (!boundaries.advance(partner, boundary)) continue
            val chatId = ConversationId.direct(myId, partner)
            val chat = chats.get(chatId) ?: continue
            val left = unreadLeft(chat.unreadCount, messages.observeChat(chatId).first(), boundary)
            if (left != chat.unreadCount) chats.setUnread(chatId, left)
            Log.i(TAG, "read up to ${boundary.messageId.take(8)}… on another device — unread ${chat.unreadCount} → $left")
        }
    }

    internal companion object {
        const val TAG = "ReadSync"

        /**
         * Of the [unread] newest incoming [rows], how many are past [boundary]. Only ever lower.
         *
         * By position when the boundary's message is here: what comes after it in the chat's order
         * is unread. When it is not (not arrived yet), by time — and this device keeps the time it
         * received a message, never earlier than its envelope's, so the count errs toward unread
         * rather than hiding one.
         */
        fun unreadLeft(unread: Int, rows: List<MessageRecord>, boundary: ReadSync.Boundary): Int {
            if (unread <= 0) return 0
            val incoming = rows.filter { !it.isSentByMe } // oldest first, as the store gives them
            val counted = incoming.takeLast(unread)
            val at = incoming.indexOfFirst { it.id.equals(boundary.messageId, ignoreCase = true) }
            val past = if (at >= 0) {
                counted.count { incoming.indexOf(it) > at }
            } else {
                counted.count { it.timestampMs > boundary.sentAtMs }
            }
            return minOf(unread, past)
        }
    }
}
