package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityUiState(
    val hasUsername: Boolean = false,
    val discoverable: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Security: for now, whether the alias can be found by exact search.
 *
 * **Canon:** iOS `SecurityView` → Discovery. Turning search on is confirmed first (the UI asks);
 * turning it off is not — becoming harder to find needs no warning.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                state.update {
                    it.copy(
                        hasUsername = !account?.username.isNullOrEmpty(),
                        discoverable = account?.discoverable ?: false,
                    )
                }
            }
        }
        viewModelScope.launch { accountRepository.refresh() }
    }

    fun setDiscoverable(enabled: Boolean) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val applied = accountRepository.setDiscoverable(enabled)
            state.update { it.copy(busy = false, failed = !applied) }
        }
    }
}
