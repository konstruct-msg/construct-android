package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.LocalMessagePayload
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The chat with ourselves that builds before 2026-11-23 allowed becomes notes, where what one
 * writes to oneself lives (`decisions/notes-stay-on-the-device.md`): texts, photos, files, voice and
 * stickers. Read once at launch; nothing makes another.
 *
 * Our own contact row stays, unlisted: the core deletes a contact's devices with it, and these are
 * ours. Remove after [LAST_DAY]; `SelfChatToNotesTest` fails then.
 */
@Singleton
class SelfChatToNotes @Inject constructor(
    private val keystore: KeystoreManager,
    private val chats: ChatStore,
    private val messages: MessageStore,
    private val notes: NoteStore,
) {
    suspend fun move() {
        try {
            val me = keystore.getUserId() ?: return
            val chatId = ConversationId.direct(me, me)
            if (chats.get(chatId) == null) return
            val made = notesOf(messages.observeChat(chatId).first())
            // The chat goes once every note is in; run again, a note already there is left.
            made.forEach { notes.insert(it) }
            chats.delete(chatId)
            Log.i(TAG, "chat with ourselves — ${made.size} note(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "not moved, next launch tries again", e)
        }
    }

    internal companion object {
        const val TAG = "SelfChatToNotes"
        const val LAST_DAY = "2027-03-01"

        /**
         * One note per thing sent. Every send also came back as an incoming row, so a received row
         * stands for a sent one of the same content; one left over was sent from another device and
         * has no row of ours.
         */
        fun notesOf(rows: List<MessageRecord>): List<Note> {
            val written = rows.filter { it.mediaType != null || it.text.isNotBlank() }
            val sent = written.filter { it.isSentByMe }
            val paired = sent.groupingBy(::content).eachCount().toMutableMap()
            val unpaired = written.filterNot { it.isSentByMe }.filter { row ->
                val left = paired[content(row)] ?: 0
                if (left > 0) paired[content(row)] = left - 1
                left == 0
            }
            return (sent + unpaired).map { Note(id = it.id, body = LocalMessagePayload.encode(it), createdAtMs = it.timestampMs) }
        }

        /** What makes two rows one thing: the words, and the media by id — its bytes differ per copy. */
        private fun content(row: MessageRecord): List<String> = listOf(row.text.trim(), row.mediaType.orEmpty()) +
            when (val media = MediaWire.decode(row.mediaType, row.mediaPayload)) {
                is MessageMedia.Album -> media.items.map { it.mediaId }
                is MessageMedia.Voice -> listOf(media.audio.mediaId)
                is MessageMedia.Sticker -> listOf(media.ref.packHex, media.ref.index.toString())
                // Media nothing here reads: each row is its own.
                null -> if (row.mediaType == null) emptyList() else listOf(row.id)
            }
    }
}
