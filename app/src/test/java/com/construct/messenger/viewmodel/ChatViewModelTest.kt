package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.repository.AcceptInviteResult
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.FindUserResult
import com.construct.messenger.data.repository.IncomingContactRequest
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun sendClearsDraftAndRecordsOutgoing() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(
            handle,
            messages,
            FakeContactsRepository(),
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
        )
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(1, messages.sent.size)
        assertEquals("hello", messages.sent.single().text)
        assertNull(messages.sent.single().reply)
        assertTrue(viewModel.uiState.value.messages.single().isOutgoing)
    }

    /**
     * The field is empty while the send is still on the wire (testers, 2026-10-03: the text sat in
     * a locked field until the server answered). Mutation: clear only on Sent — this reddens.
     */
    @Test
    fun `the composer is free before the send ends`() = runTest {
        val messages = FakeMessagesRepository().also { it.gate = kotlinx.coroutines.CompletableDeferred() }
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        viewModel.startReply(Message(id = "q", chatId = "peer-1", body = "quoted", isOutgoing = false))
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.replyingTo)
        assertEquals("hello", messages.sent.single().text)
        viewModel.onDraftChange("next")
        messages.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("next", viewModel.uiState.value.draft)
    }

    /**
     * The header names the peer as iOS does: the shared name, in capitals — not `@username`.
     * Mutation: put the username branch back ahead of the name — this reddens.
     */
    @Test
    fun `the header shows the shared name, not the username`() = runTest {
        val contacts = FakeContactsRepository()
        contacts.contacts.value = listOf(Contact(userId = "peer-1", displayName = "Alice", username = "alice"))
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, FakeMessagesRepository(), contacts, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        advanceUntilIdle()

        assertEquals("ALICE", viewModel.uiState.value.title)
        assertEquals("Alice", viewModel.uiState.value.contactName)
    }

    /** Refused before a row was written, nothing on screen holds the text: it comes back. */
    @Test
    fun `a send refused before any bubble gives the text back`() = runTest {
        val messages = FakeMessagesRepository().also { it.refuseSend = true }
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        viewModel.onDraftChange("hello ")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("hello ", viewModel.uiState.value.draft)
    }

    @Test
    fun sendCarriesTheReplyAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())

        viewModel.startReply(
            Message(id = "ABC", chatId = "peer-1", body = "  original  ", isOutgoing = false),
        )
        advanceUntilIdle()
        assertEquals("abc", viewModel.uiState.value.replyingTo?.messageId)
        assertEquals("original", viewModel.uiState.value.replyingTo?.preview)

        viewModel.onDraftChange("answer")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.replyingTo)
        assertEquals(ReplyRef.of("abc", "original"), messages.sent.single().reply)
    }

    @Test
    fun sendEditsTheTargetAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        val original = Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true)

        viewModel.startEdit(original)
        advanceUntilIdle()
        assertEquals("hello", viewModel.uiState.value.draft)
        assertEquals("hello", viewModel.uiState.value.editingOriginal)
        assertNull(viewModel.uiState.value.replyingTo)

        viewModel.onDraftChange("hello there")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.editingOriginal)
        assertTrue(messages.sent.isEmpty())
        assertEquals("mine", messages.edits.single().messageId)
        assertEquals("hello there", messages.edits.single().text)
    }

    @Test
    fun aFailedEditKeepsTheDraft() = runTest {
        val messages = FakeMessagesRepository().also { it.failEdit = true }
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        viewModel.startEdit(Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true))
        viewModel.onDraftChange("hello there")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("hello there", viewModel.uiState.value.draft)
        assertEquals("hello", viewModel.uiState.value.editingOriginal)
    }

    @Test
    fun deleteDropsTheRowAndAnOpenEditOfIt() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        val original = Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true)
        viewModel.startEdit(original)
        viewModel.delete(original)
        advanceUntilIdle()

        assertEquals(listOf("mine"), messages.deleted)
        assertNull(viewModel.uiState.value.editingOriginal)
        assertEquals("", viewModel.uiState.value.draft)
    }
    /** Picked photos go with the typed text as their caption, and the composer is free at once. */
    @Test
    fun `picked photos are sent with the draft as caption`() = runTest {
        val handle = SavedStateHandle().apply { set("contactId", "peer") }
        val messages = FakeMessagesRepository()
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        val a = org.mockito.kotlin.mock<android.net.Uri>()
        val b = org.mockito.kotlin.mock<android.net.Uri>()
        viewModel.attach(listOf(a, b, a))
        viewModel.onDraftChange("sea")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(listOf(a, b) to "sea"), messages.photos)
        assertTrue(messages.sent.isEmpty())
        assertEquals(emptyList<android.net.Uri>(), viewModel.uiState.value.attachments)
        assertEquals("", viewModel.uiState.value.draft)
    }

    /** iOS Quote & Reply: the selected part is the quote, the message is still the one replied to. */
    @Test
    fun `a selected quote replaces the preview of the reply`() = runTest {
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, FakeMessagesRepository(), FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock())
        val message = Message(id = "M-1", chatId = "c", body = "one two three", isOutgoing = false)
        viewModel.startReply(message, "two")
        advanceUntilIdle()
        assertEquals("m-1", viewModel.uiState.value.replyingTo?.messageId)
        assertEquals("two", viewModel.uiState.value.replyingTo?.preview)
        viewModel.startReply(message)
        advanceUntilIdle()
        assertEquals("one two three", viewModel.uiState.value.replyingTo?.preview)
    }
}

private class FakeMessagesRepository : MessagesRepository {
    val sent = mutableListOf<Sent>()
    private val flow = MutableStateFlow<List<Message>>(emptyList())
    override fun observeContact(contactId: String): Flow<List<Message>> = flow.asStateFlow()
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var refuseSend = false
    override suspend fun send(contactId: String, text: String, reply: ReplyRef?): SendOutcome {
        sent += Sent(contactId, text, reply)
        if (refuseSend) return SendOutcome.Failed("", "not authenticated")
        gate?.await()
        flow.value = flow.value + Message(
            id = "m-${sent.size}",
            chatId = contactId,
            body = text,
            isOutgoing = true,
            replyToId = reply?.messageId,
            replyPreview = reply?.preview,
        )
        return SendOutcome.Sent(flow.value.last().id)
    }

    data class Sent(val contactId: String, val text: String, val reply: ReplyRef?)
    val photos = mutableListOf<Pair<List<android.net.Uri>, String>>()
    override suspend fun sendFiles(contactId: String, uris: List<android.net.Uri>, caption: String) = SendOutcome.Sent("f")
    override suspend fun openable(item: com.construct.messenger.data.model.MediaItem, name: String): android.net.Uri = org.mockito.kotlin.mock()
    override fun describe(uri: android.net.Uri) = "a.pdf" to 10L
    override suspend fun sendVoice(contactId: String, recording: java.io.File, durationMs: Long, waveform: List<Float>) = SendOutcome.Sent("v")
    override suspend fun sendVideoNote(contactId: String, take: com.construct.messenger.media.VideoNoteTake) = SendOutcome.Sent("n")
    override suspend fun mediaBytes(item: com.construct.messenger.data.model.MediaItem) = ByteArray(0)
    override suspend fun saveToGallery(item: com.construct.messenger.data.model.MediaItem) = Unit
    override suspend fun sendPhotos(contactId: String, uris: List<android.net.Uri>, caption: String, reply: ReplyRef?): SendOutcome {
        photos += uris to caption
        return SendOutcome.Sent("p-${photos.size}")
    }
    val edits = mutableListOf<Edit>()
    val deleted = mutableListOf<String>()
    var failEdit = false
    data class Edit(val messageId: String, val text: String)
    override suspend fun edit(contactId: String, messageId: String, newText: String): SendOutcome {
        edits += Edit(messageId, newText)
        if (failEdit) return SendOutcome.Failed(messageId, "no")
        flow.value = flow.value.map { row ->
            if (row.id == messageId) row.copy(body = newText, isEdited = true) else row
        }
        return SendOutcome.Sent(messageId)
    }
    override suspend fun delete(contactId: String, messageId: String) {
        deleted += messageId
        flow.value = flow.value.filter { it.id != messageId }
    }
    override suspend fun sendSticker(contactId: String, ref: com.construct.messenger.stickers.StickerReference) = SendOutcome.Sent("s")
    val retried = mutableListOf<String>()
    override suspend fun retry(contactId: String, messageId: String): SendOutcome {
        retried += messageId
        return SendOutcome.Sent(messageId)
    }
    val reacted = mutableListOf<Pair<String, String>>()
    override suspend fun react(contactId: String, messageId: String, emoji: String): Boolean {
        reacted += messageId to emoji
        return true
    }
    override suspend fun chatShown(contactId: String) = Unit
    override fun chatHidden(contactId: String) = Unit
}

private class FakeContactsRepository : ContactsRepository {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override suspend fun mintLink(): MintedInvite =
        MintedInvite("j", 0, 300, "p", "konstruct://add?invite=p")
    override suspend fun mintQr(sitting: String): MintedInvite = mintLink()
    override suspend fun accept(raw: String): AcceptInviteResult =
        AcceptInviteResult.Failed("unused")
    override suspend fun revoke(jti: String) = com.construct.messenger.data.repository.InviteRevocation.UNCONFIRMED
    override val incomingRequests = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    override suspend fun findByUsername(username: String): FindUserResult = FindUserResult.NotFound
    override suspend fun sendContactRequest(userId: String) = false
    override suspend fun refreshRequests() = Unit
    override suspend fun acceptRequest(requestId: String, fromUserId: String) = false
    override suspend fun checkUsername(username: String) =
        com.construct.messenger.data.repository.UsernameAvailability(true)
    override suspend fun setDiscoverable(enabled: Boolean) = true
    override suspend fun getProfile(userId: String) = null
    override val issuedInvites =
        MutableStateFlow<List<com.construct.messenger.data.repository.IssuedInvite>>(emptyList())
}
