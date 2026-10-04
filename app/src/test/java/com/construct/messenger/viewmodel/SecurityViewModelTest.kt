package com.construct.messenger.viewmodel

import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.Lockdown
import com.construct.messenger.data.repository.SecuritySettingsRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class SecurityViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `no alias means nothing to be found by`() {
        val vm = securityViewModel(FakeAccountRepository(username = ""))
        assertFalse(vm.uiState.value.hasUsername)
    }

    @Test
    fun `an applied change shows as the new state`() {
        val vm = securityViewModel(FakeAccountRepository(username = "fox"))
        vm.setDiscoverable(true)
        assertTrue(vm.uiState.value.discoverable)
        assertFalse(vm.uiState.value.failed)
    }

    @Test
    fun `a refused change keeps the old state and says it failed`() {
        val repo = FakeAccountRepository(username = "fox").apply { discoverableApplies = false }
        val vm = securityViewModel(repo)
        vm.setDiscoverable(true)
        assertFalse(vm.uiState.value.discoverable)
        assertTrue(vm.uiState.value.failed)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun `lockdown approves the contacts of the moment it is switched on`() {
        val settings = securitySettings()
        val contacts = mock<ContactsRepository> {
            on { this.contacts } doReturn flowOf(listOf(Contact("alice", "Alice"), Contact("bob", "Bob")))
        }
        val vm = securityViewModel(FakeAccountRepository(username = "fox"), settings, contacts)
        vm.setLockdown(true)
        verify(settings).enableLockdown(setOf("alice", "bob"))
    }

    private fun securitySettings() = mock<SecuritySettingsRepository> {
        on { lockdown } doReturn MutableStateFlow(Lockdown())
        on { senderAnonymity } doReturn true
    }

    private fun securityViewModel(
        account: AccountRepository,
        settings: SecuritySettingsRepository = securitySettings(),
        contacts: ContactsRepository = mock(),
    ) = SecurityViewModel(account, mock(), mock(), settings, contacts, mock<com.construct.messenger.security.KtLog>().also {
        org.mockito.kotlin.whenever(it.tally).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(com.construct.messenger.security.KtTally()))
    })
}
