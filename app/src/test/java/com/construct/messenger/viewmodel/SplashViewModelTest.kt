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

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun unauthenticatedUserRoutesToOnboarding() = runTest {
        val repository = MockAuthRepository()
        val viewModel = SplashViewModel(repository, MockOrientationStore())

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Onboarding, viewModel.uiState.value.route)
    }

    @Test
    fun initializedButNotOrientedRoutesToOrientation() = runTest {
        val repository = MockAuthRepository()
        repository.initializeIdentity(username = null)
        val viewModel = SplashViewModel(repository, MockOrientationStore(initiallyCompleted = false))

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Orientation, viewModel.uiState.value.route)
    }

    @Test
    fun initializedAndOrientedUserRoutesToMain() = runTest {
        val repository = MockAuthRepository()
        repository.initializeIdentity(username = null)
        val viewModel = SplashViewModel(repository, MockOrientationStore(initiallyCompleted = true))

        viewModel.decideNextRoute()
        advanceUntilIdle()

        assertEquals(SplashRoute.Main, viewModel.uiState.value.route)
    }
}
