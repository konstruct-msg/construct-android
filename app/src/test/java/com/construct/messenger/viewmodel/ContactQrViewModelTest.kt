package com.construct.messenger.viewmodel

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.UserProfile
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.test.MainDispatcherRule
import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class ContactQrViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val keystore: KeystoreManager = mock { on { getUserId() } doReturn userId }
    private var minted = 0
    private val repo: ContactsRepository = mock()

    private fun invite(n: Int, link: String = "konstruct://add?invite=p$n") =
        MintedInvite(jti = "j$n", issuedAtEpochSec = 0, ttlSeconds = 300, payload = "p$n", deepLink = link)

    private fun viewModel(username: String? = null): ContactQrViewModel {
        wheneverBlocking { repo.getProfile(any()) }.thenReturn(
            UserProfile(userId = userId, displayName = "", username = username.orEmpty()),
        )
        wheneverBlocking { repo.mintQr(any()) }.thenAnswer { invite(++minted) }
        var links = 0
        wheneverBlocking { repo.mintLink(any()) }.thenAnswer { ++links; invite(100 + links, "link-$links") }
        return ContactQrViewModel(repo, keystore)
    }

    @Test
    fun `an alias is shown as @alias, otherwise the generated name`() = runTest {
        val withAlias = viewModel("silent_fox")
        advanceUntilIdle()
        assertEquals("@silent_fox", withAlias.uiState.value.displayName)

        val without = viewModel(null)
        advanceUntilIdle()
        assertEquals(DisplayNameGenerator.generate(userId), without.uiState.value.displayName)
    }

    @Test
    fun `a visible code is re-minted every 30 seconds and not after it is hidden`() = runTest {
        val vm = viewModel()
        vm.startRotating()
        runCurrent()
        assertEquals("p1", vm.uiState.value.payload)

        advanceTimeBy(ContactQrViewModel.ROTATE_MS)
        runCurrent()
        assertEquals("p2", vm.uiState.value.payload)

        vm.stopRotating()
        advanceTimeBy(ContactQrViewModel.ROTATE_MS * 3)
        runCurrent()
        verifyBlocking(repo, times(2)) { mintQr(any()) }
    }

    @Test
    fun `new code mints at once and restarts the clock`() = runTest {
        val vm = viewModel()
        vm.startRotating()
        runCurrent()
        advanceTimeBy(ContactQrViewModel.ROTATE_MS - 1_000)

        vm.newCode()
        runCurrent()
        assertEquals("p2", vm.uiState.value.payload)

        // The old schedule would have fired a second later; the new one waits a full interval.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("p2", vm.uiState.value.payload)
        vm.stopRotating()
    }

    @Test
    fun `two taps leave the newest link on the clipboard`() = runTest {
        val vm = viewModel()
        val copied = mutableListOf<String>()
        val collector = launch { vm.copiedLinks.collect { copied += it } }
        runCurrent()

        vm.copyLink(nowMs = 1_000)
        vm.copyLink(nowMs = 2_000)
        advanceUntilIdle()

        assertEquals("link-2", copied.last())
        assertEquals(2, vm.uiState.value.copiedCount)
        collector.cancel()
    }

    /** iOS `copyDebounce`: a bounce of the finger is one tap, one link. Mutation: drop the check — this reddens. */
    @Test
    fun `a tap within the debounce mints nothing more`() = runTest {
        val vm = viewModel()

        vm.copyLink(nowMs = 1_000)
        vm.copyLink(nowMs = 1_100)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.copiedCount)
    }

    /** It was the link that failed: the code on screen still works. Mutation: set `failed` again — this reddens. */
    @Test
    fun `a failed copy keeps the code and says so`() = runTest {
        val vm = viewModel()
        vm.startRotating()
        runCurrent()
        val shown = vm.uiState.value.payload
        wheneverBlocking { repo.mintLink() }.thenThrow(IllegalStateException("offline"))

        vm.copyLink(nowMs = 1_000)
        runCurrent()

        assertTrue(vm.uiState.value.copyFailed)
        assertEquals(false, vm.uiState.value.failed)
        assertEquals(shown, vm.uiState.value.payload)
        vm.stopRotating()
    }

    @Test
    fun `a failed mint shows the failure instead of a stale code`() = runTest {
        val vm = viewModel()
        wheneverBlocking { repo.mintQr(any()) }.thenThrow(IllegalStateException("no identity"))
        vm.startRotating()
        runCurrent()

        assertTrue(vm.uiState.value.failed)
        assertEquals(null, vm.uiState.value.payload)
        vm.stopRotating()
    }
}
