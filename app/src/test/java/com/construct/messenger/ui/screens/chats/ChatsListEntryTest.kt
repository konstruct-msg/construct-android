package com.construct.messenger.ui.screens.chats

import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.ChatSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Notes stand among the chats as one row, placed by the newest note as a chat is placed by its
 * last message. **Canon:** iOS `NotesInChatsTests`.
 */
class ChatsListEntryTest {
    private fun chat(id: String, at: Long?, pinned: Boolean = false) =
        ChatSummary(contactId = id, displayName = id, lastMessageTime = at, isPinned = pinned)

    private fun note(id: String, at: Long, pinned: Boolean = false) =
        Note(id = id, body = ByteArray(0), createdAtMs = at, isPinned = pinned)

    /** The store's order: pinned first, then most recent, chats with no message last. */
    private val chats = listOf(chat("P", 500, pinned = true), chat("A", 400), chat("B", 200), chat("C", null))

    private fun order(newest: Note?) =
        ChatsListEntry.merged(chats, newest).map { if (it is ChatsListEntry.Notes) "notes" else it.key }

    @Test fun theRowStandsByTheTimeOfTheNewestNote() =
        assertEquals(listOf("P", "A", "notes", "B", "C"), order(note("n", 300)))

    @Test fun aNoteNewerThanEveryChatComesRightAfterThePinned() =
        assertEquals(listOf("P", "notes", "A", "B", "C"), order(note("n", 900)))

    @Test fun anOldNoteStillComesBeforeChatsWithNoMessage() =
        assertEquals(listOf("P", "A", "B", "notes", "C"), order(note("n", 100)))

    @Test fun withNoNoteTheRowIsLast() =
        assertEquals(listOf("P", "A", "B", "C", "notes"), order(null))

    @Test fun theRowIsThereWithNoChatAtAll() =
        assertEquals(listOf(ChatsListEntry.NOTES_KEY), ChatsListEntry.merged(emptyList(), null).map { it.key })

    @Test fun theNewestIsTheNewestUnpinnedOrAnOlderPinnedOne() =
        assertEquals("u1", ChatsListEntry.newestOf(listOf(note("p-old", 50, pinned = true), note("u1", 300), note("u2", 200)))?.id)

    @Test fun aPinnedNoteNewerThanTheRestIsTheNewest() =
        assertEquals("p-new", ChatsListEntry.newestOf(listOf(note("p-new", 900, pinned = true), note("u1", 300)))?.id)

    @Test fun noNoteIsNull() = assertNull(ChatsListEntry.newestOf(emptyList()))
}
