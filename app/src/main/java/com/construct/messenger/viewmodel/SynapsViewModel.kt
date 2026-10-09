package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.repository.AcceptInviteResult
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.FindUserResult
import com.construct.messenger.data.repository.IncomingContactRequest
import com.construct.messenger.ui.screens.synaps.ContactMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SynapsUiState(
    val contacts: List<Contact> = emptyList(),
    val incomingRequests: List<IncomingContactRequest> = emptyList(),
    val query: String = "",
    val paste: String = "",
    val status: String? = null,
    val lastMintedLink: String? = null,
    val busy: Boolean = false,
    val blocked: List<Contact> = emptyList(),
    /** Activity per contact id (blocked ones too) — where each sits in the cloud and its ring. */
    val metrics: Map<String, ContactMetrics> = emptyMap(),
) {
    val filtered: List<Contact>
        get() = contacts.filter(::matches)

    /** The cloud: contacts and blocked ones (red ring, no separate section — as iOS), filtered by the query. */
    val cloud: List<Contact>
        get() = (contacts + blocked).distinctBy { it.userId }.filter(::matches)

    private fun matches(contact: Contact): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() ||
            contact.displayName.lowercase().contains(q) ||
            contact.username.lowercase().contains(q)
    }
}

@HiltViewModel
class SynapsViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    chatsRepository: ChatsRepository,
    private val pendingInvites: PendingInviteStore,
) : ViewModel() {
    private val form = MutableStateFlow(SynapsUiState())

    val uiState: StateFlow<SynapsUiState> = combine(
        contactsRepository.contacts,
        contactsRepository.incomingRequests,
        contactsRepository.blocked.onStart { emit(emptyList()) },
        chatsRepository.activity.onStart { emit(emptyList()) },
        form,
    ) { contacts, incoming, blocked, activity, rest ->
        val ids = (contacts + blocked).map { it.userId }.distinct()
        rest.copy(
            contacts = contacts,
            incomingRequests = incoming,
            blocked = blocked,
            metrics = ContactMetrics.byContact(ids, activity, System.currentTimeMillis()),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SynapsUiState())

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
        viewModelScope.launch { contactsRepository.refreshRequests() }
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

    fun findAndRequest() {
        val raw = form.value.query.trim().removePrefix("@")
        if (raw.isEmpty() || form.value.busy) return
        form.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            when (val found = contactsRepository.findByUsername(raw)) {
                is FindUserResult.Found -> {
                    val sent = contactsRepository.sendContactRequest(found.userId)
                    form.update {
                        it.copy(
                            busy = false,
                            status = if (sent) "@$raw" else "request failed",
                        )
                    }
                }
                FindUserResult.NotFound -> form.update {
                    it.copy(busy = false, status = "not found")
                }
                is FindUserResult.Failed -> form.update {
                    it.copy(busy = false, status = found.reason)
                }
            }
        }
    }

    fun acceptRequest(request: IncomingContactRequest) {
        if (form.value.busy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = contactsRepository.acceptRequest(request.requestId, request.fromUserId)
            form.update { it.copy(busy = false, status = if (ok) request.displayName else "accept failed") }
        }
    }
}
