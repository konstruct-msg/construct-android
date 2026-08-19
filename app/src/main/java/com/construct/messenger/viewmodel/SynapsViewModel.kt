package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.repository.AcceptInviteResult
import com.construct.messenger.data.repository.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SynapsUiState(
    val contacts: List<Contact> = emptyList(),
    val query: String = "",
    val paste: String = "",
    val status: String? = null,
    val lastMintedLink: String? = null,
    val busy: Boolean = false,
) {
    val filtered: List<Contact>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return contacts
            return contacts.filter {
                it.displayName.lowercase().contains(q) ||
                    it.username.lowercase().contains(q)
            }
        }
}

@HiltViewModel
class SynapsViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val pendingInvites: PendingInviteStore,
) : ViewModel() {
    private val form = MutableStateFlow(SynapsUiState())

    val uiState: StateFlow<SynapsUiState> = combine(
        contactsRepository.contacts,
        form,
    ) { contacts, rest -> rest.copy(contacts = contacts) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SynapsUiState())

    init {
        viewModelScope.launch {
            pendingInvites.pending.collect { raw ->
                if (raw != null) {
                    pendingInvites.take()
                    form.update { it.copy(paste = raw) }
                    accept()
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        form.update { it.copy(query = value) }
    }

    fun onPasteChange(value: String) {
        form.update { it.copy(paste = value, status = null) }
    }

    fun shareInvite() {
        if (form.value.busy) return
        form.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            try {
                val minted = contactsRepository.mintLink()
                form.update {
                    it.copy(busy = false, lastMintedLink = minted.deepLink, status = minted.deepLink)
                }
            } catch (e: Exception) {
                form.update { it.copy(busy = false, status = e.message ?: "mint failed") }
            }
        }
    }

    fun accept() {
        val raw = form.value.paste.trim()
        if (raw.isEmpty() || form.value.busy) return
        form.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            when (val result = contactsRepository.accept(raw)) {
                is AcceptInviteResult.Ok -> form.update {
                    it.copy(busy = false, paste = "", status = result.contact.displayName)
                }
                is AcceptInviteResult.Failed -> form.update {
                    it.copy(busy = false, status = result.reason)
                }
            }
        }
    }
}
