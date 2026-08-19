package com.construct.messenger.viewmodel

import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.repository.AcceptInviteResult
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SynapsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun acceptPasteAddsContactAndClearsField() = runTest {
        val repo = FakeContacts()
        val viewModel = SynapsViewModel(repo, PendingInviteStore())
        viewModel.onPasteChange("konstruct://add?invite=abc")
        viewModel.accept()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.paste)
        assertEquals("swift fox", viewModel.uiState.value.status)
        assertEquals(1, repo.accepted.size)
    }

    @Test
    fun shareInviteStoresLink() = runTest {
        val repo = FakeContacts()
        val viewModel = SynapsViewModel(repo, PendingInviteStore())
        viewModel.shareInvite()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.lastMintedLink!!.startsWith("konstruct://add"))
    }
}

private class FakeContacts : ContactsRepository {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val accepted = mutableListOf<String>()
    override suspend fun mintLink(includeUsername: Boolean) = MintedInvite(
        jti = "jti",
        issuedAtEpochSec = 1,
        ttlSeconds = 300,
        payload = "payload",
        deepLink = "konstruct://add?invite=payload",
    )
    override suspend fun accept(raw: String): AcceptInviteResult {
        accepted += raw
        val contact = Contact("u1", "swift fox")
        contacts.value = listOf(contact)
        return AcceptInviteResult.Ok(contact)
    }
    override suspend fun revoke(jti: String) = true
}
