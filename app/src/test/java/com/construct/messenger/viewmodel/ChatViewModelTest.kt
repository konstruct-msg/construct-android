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

    @Test
    fun sendCarriesTheReplyAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock())

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
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock())
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
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock())
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
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock())
        val original = Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true)
        viewModel.startEdit(original)
        viewModel.delete(original)
        advanceUntilIdle()

        assertEquals(listOf("mine"), messages.deleted)
        assertNull(viewModel.uiState.value.editingOriginal)
        assertEquals("", viewModel.uiState.value.draft)
    }
}

private class FakeMessagesRepository : MessagesRepository {
    val sent = mutableListOf<Sent>()
    private val flow = MutableStateFlow<List<Message>>(emptyList())
    override fun observeContact(contactId: String): Flow<List<Message>> = flow.asStateFlow()
    override suspend fun send(contactId: String, text: String, reply: ReplyRef?): SendOutcome {
        sent += Sent(contactId, text, reply)
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
    override suspend fun chatShown(contactId: String) = Unit
    override fun chatHidden(contactId: String) = Unit
}

private class FakeContactsRepository : ContactsRepository {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override suspend fun mintLink(includeUsername: Boolean): MintedInvite =
        MintedInvite("j", 0, 300, "p", "konstruct://add?invite=p")
    override suspend fun mintQr(): MintedInvite = mintLink(false)
    override suspend fun accept(raw: String): AcceptInviteResult =
        AcceptInviteResult.Failed("unused")
    override suspend fun revoke(jti: String): Boolean = false
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
