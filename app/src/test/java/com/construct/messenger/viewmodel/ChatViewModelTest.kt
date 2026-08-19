package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.model.Message
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
        )
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(1, messages.sent.size)
        assertEquals("hello", messages.sent.single().second)
        assertTrue(viewModel.uiState.value.messages.single().isOutgoing)
    }
}

private class FakeMessagesRepository : MessagesRepository {
    val sent = mutableListOf<Pair<String, String>>()
    private val flow = MutableStateFlow<List<Message>>(emptyList())
    override fun observeContact(contactId: String): Flow<List<Message>> = flow.asStateFlow()
    override suspend fun send(contactId: String, text: String): SendOutcome {
        sent += contactId to text
        flow.value = flow.value + Message(
            id = "m-${sent.size}",
            chatId = contactId,
            body = text,
            isOutgoing = true,
        )
        return SendOutcome.Sent(flow.value.last().id)
    }
}

private class FakeContactsRepository : ContactsRepository {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override suspend fun mintLink(includeUsername: Boolean): MintedInvite =
        MintedInvite("j", 0, 300, "p", "konstruct://add?invite=p")
    override suspend fun accept(raw: String): AcceptInviteResult =
        AcceptInviteResult.Failed("unused")
    override suspend fun revoke(jti: String): Boolean = false
    override val incomingRequests = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    override suspend fun findByUsername(username: String): FindUserResult = FindUserResult.NotFound
    override suspend fun sendContactRequest(userId: String) = false
    override suspend fun refreshRequests() = Unit
    override suspend fun acceptRequest(requestId: String, fromUserId: String) = false
}
