package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.ui.components.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val account: OwnAccount? = null,
    val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
)

/** Settings root: who you are and whether the stream is up. Actions live on the sub-screens. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    connectionRepository: ConnectionRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> =
        combine(accountRepository.account, connectionRepository.status, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** On every appearance: an alias changed on the Account screen shows on return. */
    fun refresh() {
        viewModelScope.launch { accountRepository.refresh() }
    }
}
