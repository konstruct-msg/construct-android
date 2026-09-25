package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.data.repository.UsernameChange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UsernameError { LENGTH, UNAVAILABLE, FAILED }

data class AccountUiState(
    val account: OwnAccount? = null,
    val editing: Boolean = false,
    val draftUsername: String = "",
    val saving: Boolean = false,
    val usernameError: UsernameError? = null,
    val signingOut: Boolean = false,
)

sealed interface AccountEvent {
    data object SignedOut : AccountEvent
}

/**
 * The Account screen: alias, fingerprint, account id, sign-out.
 *
 * **Canon:** iOS `AccountSettingsView`. Editing is a mode — "edit" opens the alias for change,
 * "save" checks it and leaves the mode only when the server took it.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val state = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = state.asStateFlow()

    private val events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 1)
    val eventsFlow: SharedFlow<AccountEvent> = events.asSharedFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account -> state.update { it.copy(account = account) } }
        }
        viewModelScope.launch { accountRepository.refresh() }
    }

    fun startEditing() {
        state.update {
            it.copy(editing = true, draftUsername = it.account?.username.orEmpty(), usernameError = null)
        }
    }

    fun cancelEditing() {
        state.update { it.copy(editing = false, usernameError = null) }
    }

    fun onDraftChange(value: String) {
        val cleaned = value.lowercase().filterNot(Char::isWhitespace)
            .take(AccountRepository.USERNAME_LENGTH.last)
        state.update { it.copy(draftUsername = cleaned, usernameError = null) }
    }

    fun save() {
        val current = state.value
        if (current.saving) return
        if (current.draftUsername == current.account?.username) {
            cancelEditing()
            return
        }
        state.update { it.copy(saving = true, usernameError = null) }
        viewModelScope.launch {
            val error = when (accountRepository.changeUsername(current.draftUsername)) {
                is UsernameChange.Saved -> null
                UsernameChange.InvalidLength -> UsernameError.LENGTH
                is UsernameChange.Unavailable -> UsernameError.UNAVAILABLE
                UsernameChange.Failed -> UsernameError.FAILED
            }
            state.update { it.copy(saving = false, usernameError = error, editing = error != null) }
        }
    }

    fun signOut() {
        if (state.value.signingOut) return
        state.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            authRepository.logout()
            events.emit(AccountEvent.SignedOut)
        }
    }
}
