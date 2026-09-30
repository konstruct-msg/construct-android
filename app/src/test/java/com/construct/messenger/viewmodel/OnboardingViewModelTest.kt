package com.construct.messenger.viewmodel

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.domain.usecase.RegistrationStep
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initializeIdentityReachesCompleteStep_andContinueEmitsNavigateToMain() = runTest {
        val viewModel = OnboardingViewModel(MockAuthRepository(), FakeOnboardingContacts())
        val event = async { viewModel.events.first() }

        viewModel.initializeIdentity("alice")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isInitializing)
        assertTrue(viewModel.uiState.value.step is RegistrationStep.Complete)

        viewModel.continueToMain()
        assertEquals(OnboardingEvent.NavigateToMain, event.await())
    }

    @Test
    fun initializeIdentityFailureSetsErrorAndDoesNotEmitNavigation() = runTest {
        val errorMessage = "identity init failed"
        val viewModel = OnboardingViewModel(ThrowingAuthRepository(errorMessage), FakeOnboardingContacts())
        val event = async { withTimeoutOrNull(100) { viewModel.events.first() } }

        viewModel.initializeIdentity("alice")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isInitializing)
        assertEquals(errorMessage, viewModel.uiState.value.errorMessage)
        assertNull(event.await())
    }

    @Test
    fun initializeIdentityIgnoresRapidDuplicateCalls() = runTest {
        val repository = DelayedCountingAuthRepository()
        val viewModel = OnboardingViewModel(repository, FakeOnboardingContacts())

        viewModel.initializeIdentity("alice")
        viewModel.initializeIdentity("alice")
        advanceUntilIdle()

        assertEquals(1, repository.initializeCalls)
        assertTrue(viewModel.uiState.value.step is RegistrationStep.Complete)
    }

    private class ThrowingAuthRepository(
        private val errorMessage: String
    ) : AuthRepository {
        private val mutableAuthState = MutableStateFlow(AuthState())

        override val authState: StateFlow<AuthState> = mutableAuthState

        override suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit) {
            throw IllegalStateException(errorMessage)
        }

        override suspend fun restoreSession(): Boolean = false
        override suspend fun recoverAccount(identifier: String, phrase: String) = Unit
        override suspend fun logout(allDevices: Boolean) = Unit
        override suspend fun deleteAccount() = Unit
    }

    private class DelayedCountingAuthRepository : AuthRepository {
        private val mutableAuthState = MutableStateFlow(AuthState())
        var initializeCalls: Int = 0
            private set

        override val authState: StateFlow<AuthState> = mutableAuthState

        override suspend fun initializeIdentity(username: String?, onStep: (RegistrationStep) -> Unit) {
            initializeCalls += 1
            onStep(RegistrationStep.GeneratingKeys)
            delay(250)
            mutableAuthState.value = AuthState(isInitialized = true, username = username)
        }

        override suspend fun restoreSession(): Boolean = mutableAuthState.value.isInitialized
        override suspend fun recoverAccount(identifier: String, phrase: String) = Unit
        override suspend fun logout(allDevices: Boolean) = Unit
        override suspend fun deleteAccount() = Unit
    }
}

private class FakeOnboardingContacts : com.construct.messenger.data.repository.ContactsRepository {
    override val contacts = MutableStateFlow<List<com.construct.messenger.data.model.Contact>>(emptyList())
    override suspend fun mintLink(includeUsername: Boolean) =
        com.construct.messenger.invite.MintedInvite("j", 0, 300, "p", "l")
    override suspend fun mintQr(sitting: String): com.construct.messenger.invite.MintedInvite = mintLink(false)
    override suspend fun accept(raw: String) =
        com.construct.messenger.data.repository.AcceptInviteResult.Failed("unused")
    override suspend fun revoke(jti: String) = com.construct.messenger.data.repository.InviteRevocation.UNCONFIRMED
    override val incomingRequests =
        MutableStateFlow<List<com.construct.messenger.data.repository.IncomingContactRequest>>(emptyList())
    override suspend fun findByUsername(username: String) =
        com.construct.messenger.data.repository.FindUserResult.NotFound
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
