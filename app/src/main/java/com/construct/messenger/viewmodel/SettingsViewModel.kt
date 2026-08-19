package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.UserProfile
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

data class SettingsUiState(
    val profile: UserProfile? = null,
    val discoverable: Boolean = false,
    val busy: Boolean = false,
)

sealed interface SettingsEvent {
    data object SignedOut : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val contactsRepository: ContactsRepository,
    private val keystoreManager: KeystoreManager,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    private val events = MutableSharedFlow<SettingsEvent>()
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()
    val eventsFlow: SharedFlow<SettingsEvent> = events.asSharedFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val id = keystoreManager.getUserId() ?: return
        val profile = contactsRepository.getProfile(id)
        state.update {
            it.copy(profile = profile, discoverable = profile?.discoverable ?: false)
        }
    }

    fun toggleDiscoverable() {
        if (state.value.busy) return
        val next = !state.value.discoverable
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val applied = contactsRepository.setDiscoverable(next)
            state.update { it.copy(busy = false, discoverable = applied && next) }
        }
    }

    fun signOut() {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            authRepository.logout()
            events.emit(SettingsEvent.SignedOut)
        }
    }
}
