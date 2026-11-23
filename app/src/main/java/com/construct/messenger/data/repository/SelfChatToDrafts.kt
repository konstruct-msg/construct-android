package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.model.Draft
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The chat with ourselves that builds before 2026-11-23 allowed becomes notes in Drafts, where
 * what one writes to oneself lives (`decisions/notes-stay-on-the-device.md`). Read once at launch:
 * the chat goes with its last message, and nothing makes another.
 *
 * Only text: a note has nowhere to keep a photo, a file or a voice message yet (todo/279). Those
 * rows stay in the store, in a chat no list shows. Our own contact row stays too, unlisted: the
 * core deletes a contact's devices with it, and these are ours.
 *
 * Remove after [LAST_DAY]; `SelfChatToDraftsTest` fails then.
 */
@Singleton
class SelfChatToDrafts @Inject constructor(
    private val keystore: KeystoreManager,
    private val chats: ChatStore,
    private val messages: MessageStore,
    private val drafts: DraftsRepository,
) {
    suspend fun move() {
        try {
            val me = keystore.getUserId() ?: return
            val chatId = ConversationId.direct(me, me)
            if (chats.get(chatId) == null) return
            val rows = messages.observeChat(chatId).first()
            val text = rows.filter(::isText)
            val notes = notesOf(text)
            // Nothing is deleted before the notes are in the store.
            if (notes.isNotEmpty() && !drafts.adopt(notes)) return
            if (text.size == rows.size) chats.delete(chatId) else text.forEach { messages.delete(it.id) }
            Log.i(TAG, "chat with ourselves — ${notes.size} note(s) to Drafts, ${rows.size - text.size} media row(s) kept")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "not moved, next launch tries again", e)
        }
    }

    internal companion object {
        const val TAG = "SelfChatToDrafts"
        const val LAST_DAY = "2027-03-01"

        private fun isText(row: MessageRecord) = row.mediaType == null && row.text.isNotBlank()

        /**
         * One note per thing written. Every send also came back as an incoming row, so a received
         * text stands for a sent one of the same text; one left over was sent from another device
         * and has no row of ours.
         */
        fun notesOf(text: List<MessageRecord>): List<Draft> {
            val sent = text.filter { it.isSentByMe }
            val paired = sent.groupingBy { it.text.trim() }.eachCount().toMutableMap()
            val unpaired = text.filterNot { it.isSentByMe }.filter { row ->
                val left = paired[row.text.trim()] ?: 0
                if (left > 0) paired[row.text.trim()] = left - 1
                left == 0
            }
            return (sent + unpaired).map { Draft(it.id, it.text.trim(), it.timestampMs) }
        }
    }
}
