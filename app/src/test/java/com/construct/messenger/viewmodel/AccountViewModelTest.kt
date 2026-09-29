package com.construct.messenger.viewmodel

import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.data.repository.UsernameChange
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.construct.messenger.data.model.AuthState
import com.construct.messenger.domain.usecase.RegistrationStep

internal class FakeAccountRepository(
    username: String = "",
    discoverable: Boolean = false,
) : AccountRepository {
    val state = MutableStateFlow<OwnAccount?>(
        OwnAccount("u1", "soft lion", username, discoverable, "630D CD29 66C4 3366"),
    )
    override val account = state
    var nextUsernameResult: UsernameChange? = null
    var discoverableApplies = true
    val usernameCalls = mutableListOf<String>()

    override suspend fun refresh() = Unit

    override suspend fun changeUsername(raw: String): UsernameChange {
        usernameCalls += raw
        val result = nextUsernameResult ?: UsernameChange.Saved(raw)
        if (result is UsernameChange.Saved) state.value = state.value?.copy(username = result.username)
        return result
    }

    override suspend fun setDiscoverable(enabled: Boolean): Boolean {
        if (discoverableApplies) state.value = state.value?.copy(discoverable = enabled)
        return discoverableApplies
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = GatedAuth()

    @Test
    fun `the draft is lowercased, has no spaces and stops at 20`() {
        val vm = AccountViewModel(FakeAccountRepository(), auth)
        vm.startEditing()
        vm.onDraftChange("Silent Fox_With_A_Very_Long_Name")
        assertEquals("silentfox_with_a_ver", vm.uiState.value.draftUsername)
    }

    @Test
    fun `saving an unchanged alias leaves edit mode without asking the server`() {
        val repo = FakeAccountRepository(username = "fox")
        val vm = AccountViewModel(repo, auth)
        vm.startEditing()
        vm.save()
        assertFalse(vm.uiState.value.editing)
        assertTrue(repo.usernameCalls.isEmpty())
    }

    @Test
    fun `a saved alias shows and ends editing`() {
        val repo = FakeAccountRepository()
        val vm = AccountViewModel(repo, auth)
        vm.startEditing()
        vm.onDraftChange("fox")
        vm.save()
        assertFalse(vm.uiState.value.editing)
        assertNull(vm.uiState.value.usernameError)
        assertEquals("fox", vm.uiState.value.account?.username)
    }

    @Test
    fun `a taken alias keeps the field open and says why`() {
        val repo = FakeAccountRepository().apply { nextUsernameResult = UsernameChange.Unavailable("taken") }
        val vm = AccountViewModel(repo, auth)
        vm.startEditing()
        vm.onDraftChange("fox")
        vm.save()
        assertTrue(vm.uiState.value.editing)
        assertEquals(UsernameError.UNAVAILABLE, vm.uiState.value.usernameError)
        assertEquals("fox", vm.uiState.value.draftUsername)
    }

    @Test
    fun `sign out runs once and then reports it`() = runTest {
        val vm = AccountViewModel(FakeAccountRepository(), auth)
        val signedOut = CompletableDeferred<AccountEvent>()
        val collector = launch { signedOut.complete(vm.eventsFlow.first()) }
        runCurrent()

        vm.signOut()
        vm.signOut()
        auth.release.complete(Unit)

        assertEquals(AccountEvent.SignedOut, signedOut.await())
        assertEquals(1, auth.logouts)
        collector.cancel()
    }
}

/** Logout that waits for [release], so a second tap lands while the first is in flight. */
private class GatedAuth : AuthRepository {
    val release = CompletableDeferred<Unit>()
    var logouts = 0
    override val authState = MutableStateFlow(AuthState())
    override suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit) = Unit
    override suspend fun restoreSession() = true
    override suspend fun recoverAccount(identifier: String, phrase: String) = Unit
    override suspend fun logout() {
        logouts++
        release.await()
    }
}
