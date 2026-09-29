package com.construct.messenger.viewmodel

import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * iOS's undo-send pattern for deleting the account: the tap opens a ten-second window in which
 * the same button aborts; only when it closes is the server asked, and only its yes erases.
 * Mutation: drop the countdown loop in `startDeletion` — the abort test reddens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val auth = mock<AuthRepository>()
    private val appLock = mock<AppLockRepository>()
    private fun viewModel() = AccountViewModel(FakeAccountRepository(), auth, mock(), appLock)

    @Test
    fun `aborting inside the window never reaches the server`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startDeletion()
        advanceTimeBy(9_000)
        vm.abortDeletion()
        advanceTimeBy(5_000)
        runCurrent()
        verify(auth, never()).deleteAccount()
        verify(appLock, never()).eraseDevice()
        assertEquals(Deletion.Idle, vm.uiState.value.deletion)
    }

    @Test
    fun `when the window closes the server is asked, and its yes erases the device`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startDeletion()
        advanceTimeBy(10_001)
        runCurrent()
        verify(auth).deleteAccount()
        verify(appLock).eraseDevice()
    }
}
