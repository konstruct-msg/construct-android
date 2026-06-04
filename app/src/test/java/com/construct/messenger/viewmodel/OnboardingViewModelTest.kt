package com.construct.messenger.viewmodel

import com.construct.messenger.data.model.AuthState
import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.data.repository.AuthRepository
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
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initializeIdentityEmitsNavigateToMain() = runTest {
        val viewModel = OnboardingViewModel(MockAuthRepository())
        val event = async { viewModel.events.first() }

        viewModel.initializeIdentity()
        advanceUntilIdle()

        assertEquals(OnboardingEvent.NavigateToMain, event.await())
        assertFalse(viewModel.uiState.value.isInitializing)
    }

    @Test
    fun initializeIdentityFailureSetsErrorAndDoesNotEmitNavigation() = runTest {
        val errorMessage = "identity init failed"
        val viewModel = OnboardingViewModel(ThrowingAuthRepository(errorMessage))
        val event = async { withTimeoutOrNull(100) { viewModel.events.first() } }

        viewModel.initializeIdentity()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isInitializing)
        assertEquals(errorMessage, viewModel.uiState.value.errorMessage)
        assertNull(event.await())
    }

    @Test
    fun initializeIdentityIgnoresRapidDuplicateCalls() = runTest {
        val repository = DelayedCountingAuthRepository()
        val viewModel = OnboardingViewModel(repository)
        val firstEvent = async { viewModel.events.first() }

        viewModel.initializeIdentity()
        viewModel.initializeIdentity()
        advanceUntilIdle()

        assertEquals(1, repository.initializeCalls)
        assertEquals(OnboardingEvent.NavigateToMain, firstEvent.await())
        assertNull(withTimeoutOrNull(100) { viewModel.events.first() })
    }

    private class ThrowingAuthRepository(
        private val errorMessage: String
    ) : AuthRepository {
        private val mutableAuthState = MutableStateFlow(AuthState())

        override val authState: StateFlow<AuthState> = mutableAuthState

        override suspend fun initializeIdentity() {
            throw IllegalStateException(errorMessage)
        }
    }

    private class DelayedCountingAuthRepository : AuthRepository {
        private val mutableAuthState = MutableStateFlow(AuthState())
        var initializeCalls: Int = 0
            private set

        override val authState: StateFlow<AuthState> = mutableAuthState

        override suspend fun initializeIdentity() {
            initializeCalls += 1
            delay(250)
            mutableAuthState.value = AuthState(isInitialized = true)
        }
    }
}
