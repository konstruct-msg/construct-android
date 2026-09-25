package com.construct.messenger.viewmodel

import com.construct.messenger.test.MainDispatcherRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecurityViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `no alias means nothing to be found by`() {
        val vm = SecurityViewModel(FakeAccountRepository(username = ""))
        assertFalse(vm.uiState.value.hasUsername)
    }

    @Test
    fun `an applied change shows as the new state`() {
        val vm = SecurityViewModel(FakeAccountRepository(username = "fox"))
        vm.setDiscoverable(true)
        assertTrue(vm.uiState.value.discoverable)
        assertFalse(vm.uiState.value.failed)
    }

    @Test
    fun `a refused change keeps the old state and says it failed`() {
        val repo = FakeAccountRepository(username = "fox").apply { discoverableApplies = false }
        val vm = SecurityViewModel(repo)
        vm.setDiscoverable(true)
        assertFalse(vm.uiState.value.discoverable)
        assertTrue(vm.uiState.value.failed)
        assertFalse(vm.uiState.value.busy)
    }
}
