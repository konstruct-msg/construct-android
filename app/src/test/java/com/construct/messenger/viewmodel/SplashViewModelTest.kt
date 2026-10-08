package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.data.mock.MockOrientationStore
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun unauthenticatedUserRoutesToOnboarding() = runTest {
        val repository = MockAuthRepository()
        val viewModel = SplashViewModel(repository, MockOrientationStore(), org.mockito.kotlin.mock())

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Onboarding, viewModel.uiState.value.route)
    }

    @Test
    fun initializedButNotOrientedRoutesToOrientation() = runTest {
        val repository = MockAuthRepository()
        repository.initializeIdentity(username = null)
        val viewModel = SplashViewModel(repository, MockOrientationStore(initiallyCompleted = false), org.mockito.kotlin.mock())

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Orientation, viewModel.uiState.value.route)
    }

    @Test
    fun initializedAndOrientedUserRoutesToMain() = runTest {
        val repository = MockAuthRepository()
        repository.initializeIdentity(username = null)
        val viewModel = SplashViewModel(repository, MockOrientationStore(initiallyCompleted = true), org.mockito.kotlin.mock())

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Main, viewModel.uiState.value.route)
    }

    /** A removed device is told and erased; it does not land on onboarding, where login fails again. */
    @Test
    fun removedDeviceRoutesToRemovedAndErases() = runTest {
        val repository = org.mockito.kotlin.mock<com.construct.messenger.data.repository.AuthRepository> {
            on { authState } doReturn kotlinx.coroutines.flow.MutableStateFlow(com.construct.messenger.data.model.AuthState(removed = true))
        }
        val appLock = org.mockito.kotlin.mock<com.construct.messenger.data.repository.AppLockRepository>()
        val viewModel = SplashViewModel(repository, MockOrientationStore(initiallyCompleted = true), appLock)

        viewModel.eraseRemovedDevice()
        org.mockito.kotlin.verify(appLock, org.mockito.kotlin.never()).eraseDevice()

        viewModel.decideNextRoute()
        advanceUntilIdle()
        assertEquals(SplashRoute.Removed, viewModel.uiState.value.route)

        viewModel.eraseRemovedDevice()
        org.mockito.kotlin.verify(appLock).eraseDevice()
    }
}
