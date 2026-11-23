package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.util.MediaWire
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalNote
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * What a person keeps for themselves, on this device only (`decisions/notes-stay-on-the-device.md`,
 * vault todo 279). Not a message: no chat, no peer, nothing sent. Its [body] is a message body
 * (CTM1), so it is drawn by what draws a message. **Canon:** iOS `Note`.
 */
data class Note(
    val id: String = UUID.randomUUID().toString().lowercase(),
    /** CTM1 ([LocalMessagePayload]). */
    val body: ByteArray,
    val createdAtMs: Long = System.currentTimeMillis(),
    val editedAtMs: Long? = null,
    val isPinned: Boolean = false,
    /** Whose message it was saved from, as they were called then. Null for one's own words. */
    val sourceName: String? = null,
    /** When that message was sent. */
    val sourceAtMs: Long? = null,
) {
    private val fields: LocalMessagePayload.Fields by lazy { LocalMessagePayload.decode(body) }

    /** The words: the text, or a caption. */
    val text: String get() = fields.text.trim()
    val media: MessageMedia? by lazy { MediaWire.decode(fields.mediaType, fields.mediaPayload) }

    /** A note of words only. One that holds media is what it holds: its caption is part of it. */
    val isEditable: Boolean get() = fields.mediaType == null && text.isNotEmpty()

    /** As the message bubbles and a send read it: ours, in no chat. */
    fun asMessage() = Message(
        id = id, chatId = "", body = text, isOutgoing = true, timestamp = createdAtMs,
        deliveryStatus = DeliveryStatus.DELIVERED, media = media,
    )

    override fun equals(other: Any?): Boolean =
        other is Note && id == other.id && body.contentEquals(other.body) && createdAtMs == other.createdAtMs &&
            editedAtMs == other.editedAtMs && isPinned == other.isPinned && sourceName == other.sourceName &&
            sourceAtMs == other.sourceAtMs

    override fun hashCode(): Int = 31 * id.hashCode() + body.contentHashCode()

    companion object {
        fun ofText(text: String, id: String = UUID.randomUUID().toString().lowercase(), createdAtMs: Long = System.currentTimeMillis()) =
            Note(id = id, body = textBody(text), createdAtMs = createdAtMs)

        /** The body of a text note, as every client writes it. */
        fun textBody(text: String): ByteArray = LocalMessagePayload.envelope(LocalMessagePayload.KIND_TEXT, text.toByteArray())
    }
}

interface NoteStore {
    /** The first [limit] notes: pinned first, each part newest first; again after every change. */
    fun observe(limit: Int): Flow<List<Note>>

    fun observe(id: String): Flow<Note?>

    /** In the list's order, again after every change. Fewer than three characters find nothing. */
    fun search(query: String, limit: Int): Flow<List<Note>>

    suspend fun get(id: String): Note?

    /** The media ids of every file a note holds. */
    suspend fun heldMediaIds(): Set<String>

    /** True when added; one already held is left as it is. */
    suspend fun insert(note: Note): Boolean

    /** What the note says now. The media ids neither a note nor a message holds any more; null for no such note. */
    suspend fun edit(id: String, body: ByteArray, editedAtMs: Long): List<String>?

    suspend fun setPinned(id: String, pinned: Boolean): Boolean

    /** The media ids neither a note nor a message holds any more: the copies to delete. */
    suspend fun delete(ids: List<String>): List<String>
}

/** [NoteStore] on the core's tables (construct-core 0.81.0). */
class CoreNoteStore(private val feed: LocalStoreFeed) : NoteStore {
    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(feed.store) }

    override fun observe(limit: Int): Flow<List<Note>> =
        feed.watch(LocalStoreTable.NOTES) { s -> s.notes(null, limit.toUInt()).map { it.note() } }

    override fun observe(id: String): Flow<Note?> = feed.watch(LocalStoreTable.NOTES) { s -> s.note(id)?.note() }

    override fun search(query: String, limit: Int): Flow<List<Note>> =
        feed.watch(LocalStoreTable.NOTES) { s -> s.searchNotes(query, limit.toUInt()).map { it.note() } }

    override suspend fun get(id: String): Note? = io { it.note(id)?.note() }

    override suspend fun heldMediaIds(): Set<String> = io { s -> s.notesMedia().mapTo(HashSet()) { it.mediaId } }

    override suspend fun insert(note: Note): Boolean = io { s ->
        s.insertNote(note.local(), words(note.body), media(note.body)) == LocalInsert.INSERTED
    }

    override suspend fun edit(id: String, body: ByteArray, editedAtMs: Long): List<String>? = io { s ->
        s.editNote(id, body, words(body), media(body), editedAtMs)?.let { heldByNoMessage(s, it) }
    }

    override suspend fun setPinned(id: String, pinned: Boolean): Boolean = io { it.setNotePinned(id, pinned) }

    override suspend fun delete(ids: List<String>): List<String> = io { s -> heldByNoMessage(s, s.deleteNotes(ids)) }

    /** A chat's bubble may show the same file: it is the chat's then, under the cache's rules. */
    private fun heldByNoMessage(s: LocalStore, mediaIds: List<String>): List<String> {
        if (mediaIds.isEmpty()) return mediaIds
        val held = s.mediaOwners(mediaIds).mapTo(HashSet()) { it.mediaId }
        return mediaIds.filterNot { it in held }
    }

    private fun words(body: ByteArray): String? = LocalMessagePayload.decode(body).text.ifBlank { null }

    private fun media(body: ByteArray) = MessageMediaIndex.entries(body).orEmpty()
}

private fun LocalNote.note() = Note(id, body, createdAt, editedAt, isPinned, sourceName, sourceAt)

private fun Note.local() = LocalNote(id, body, createdAtMs, editedAtMs, isPinned, sourceName, sourceAtMs)
