package com.construct.messenger.ui.screens.chats

import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.ChatSummary

/**
 * One row of the chat list: a chat, or the Notes row. Notes stand among the chats as one row,
 * placed by the newest note as a chat is placed by its last message (owner, 2026-11-26, as people
 * know it from other messengers; a Settings row was the place before). **Canon:** iOS
 * `ChatsListEntry`.
 */
sealed interface ChatsListEntry {
    val key: String

    data class Chat(val chat: ChatSummary) : ChatsListEntry {
        override val key: String get() = chat.contactId
    }

    data class Notes(val newest: Note?) : ChatsListEntry {
        override val key: String get() = NOTES_KEY
    }

    companion object {
        const val NOTES_KEY = "konstruct.notes"

        /**
         * The list with the Notes row in it: after the pinned chats, then by the time of the newest
         * note among the chats' last messages; with no note, at the end. [chats] come in the
         * store's order — pinned first, then most recent, chats with no message last.
         */
        fun merged(chats: List<ChatSummary>, newest: Note?): List<ChatsListEntry> {
            val at = newest?.let { note ->
                chats.indexOfFirst { chat ->
                    !chat.isPinned && (chat.lastMessageTime?.let { it < note.createdAtMs } ?: true)
                }.takeIf { it >= 0 }
            } ?: chats.size
            val entries: MutableList<ChatsListEntry> = chats.mapTo(ArrayList()) { Chat(it) }
            entries.add(at, Notes(newest))
            return entries
        }

        /**
         * The note written last, from the store's first page: pinned first, each part newest first,
         * so it is the first unpinned note or one of the pinned before it. **Canon:** iOS
         * `NotesLatest.latest`.
         */
        fun newestOf(page: List<Note>): Note? {
            var newest: Note? = null
            for (note in page) {
                if (newest == null || note.createdAtMs > newest.createdAtMs) newest = note
                if (!note.isPinned) return newest
            }
            return newest
        }
    }
}
